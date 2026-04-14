mod api;
mod auth;
mod config;
mod db;
mod feed;
pub mod mikan;
mod models;
mod services;
mod opml;

use std::sync::Arc;

use anyhow::Result;
use tokio::net::TcpListener;
use tower_http::cors::{Any, CorsLayer};
use tower_http::trace::TraceLayer;
use tracing::{info, warn};

use api::AppState;
use config::AppConfig;
use db::Database;
use services::FeedService;

#[tokio::main]
async fn main() -> Result<()> {
    // 加载配置
    let config = AppConfig::from_env();

    // 初始化日志
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| config.log_level.parse().unwrap()),
        )
        .with_target(false)
        .init();

    info!("FeedFlow v{} starting...", env!("CARGO_PKG_VERSION"));
    info!("Database: {}", config.db_path);
    info!("Listening on: {}", config.listen_addr());

    // 初始化数据库
    let db = Arc::new(Database::open(&config.db_path)?);

    // 初始化服务
    let feed_service = FeedService::new(Arc::clone(&db))?;

    // 构建应用状态
    let state = Arc::new(AppState {
        db: Arc::clone(&db),
        feed_service,
    });

    // 启动后台自动刷新任务
    if config.auto_refresh_interval_secs > 0 {
        let refresh_state = Arc::clone(&state);
        let interval = config.auto_refresh_interval_secs;
        tokio::spawn(async move {
            info!(
                "Auto-refresh enabled: every {} seconds",
                interval
            );
            loop {
                tokio::time::sleep(std::time::Duration::from_secs(interval)).await;
                info!("Starting auto-refresh...");
                match refresh_state.feed_service.refresh_all_feeds().await {
                    Ok((new, errors)) => {
                        info!(
                            "Auto-refresh complete: {} new articles, {} errors",
                            new, errors
                        );
                    }
                    Err(e) => {
                        warn!("Auto-refresh failed: {}", e);
                    }
                }
            }
        });
    }

    // 构建路由（含认证中间件）
    let app = api::create_router(state)
        .layer(axum::middleware::from_fn(auth::auth_middleware))
        .layer(
            CorsLayer::new()
                .allow_origin(Any)
                .allow_methods(Any)
                .allow_headers(Any),
        )
        .layer(TraceLayer::new_for_http());

    // 启动 HTTP 服务
    let listener = TcpListener::bind(config.listen_addr()).await?;
    info!("FeedFlow is ready!");
    info!("  Open http://{} in your browser", config.listen_addr());
    info!("---------------------------------------------------");

    // 自动打开浏览器（仅 Windows/macOS/Linux 桌面环境）
    let url = format!("http://127.0.0.1:{}", config.port);
    tokio::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_millis(500)).await;
        let _ = open_browser(&url);
    });

    axum::serve(listener, app).await?;

    Ok(())
}

/// 跨平台打开浏览器
fn open_browser(url: &str) -> std::io::Result<()> {
    #[cfg(target_os = "windows")]
    {
        std::process::Command::new("cmd").args(["/c", "start", url]).spawn()?;
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("open").arg(url).spawn()?;
    }
    #[cfg(target_os = "linux")]
    {
        std::process::Command::new("xdg-open").arg(url).spawn()?;
    }
    Ok(())
}
