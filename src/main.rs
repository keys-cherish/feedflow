mod api;
mod config;
mod db;
mod feed;
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

    // 构建路由
    let app = api::create_router(state)
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
    info!("---------------------------------------------------");
    info!("  POST   /api/feeds              - Subscribe RSS");
    info!("  GET    /api/feeds              - List all feeds");
    info!("  DELETE /api/feeds/{{id}}         - Unsubscribe");
    info!("  POST   /api/feeds/{{id}}/refresh - Refresh feed");
    info!("  GET    /api/feeds/{{id}}/articles- Feed articles");
    info!("  GET    /api/articles            - All articles");
    info!("  GET    /api/articles/unread     - Unread articles");
    info!("  PUT    /api/articles/{{id}}/read - Mark read");
    info!("  PUT    /api/articles/{{id}}/star - Star/unstar");
    info!("  POST   /api/refresh             - Refresh all");
    info!("  GET    /api/stats               - Statistics");
    info!("  POST   /api/folders             - Create folder");
    info!("  GET    /api/folders             - List folders");
    info!("  PUT    /api/folders/{{id}}       - Update folder");
    info!("  DELETE /api/folders/{{id}}       - Delete folder");
    info!("  POST   /api/opml/import         - Import OPML");
    info!("  GET    /api/opml/export         - Export OPML");
    info!("  GET    /api/settings            - 获取设置");
    info!("  PUT    /api/settings            - 更新设置");
    info!("  POST   /api/articles/{{id}}/summarize - AI 摘要");
    info!("  GET    /                        - Web UI");
    info!("---------------------------------------------------");

    axum::serve(listener, app).await?;

    Ok(())
}
