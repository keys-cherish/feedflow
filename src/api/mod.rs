use std::sync::Arc;

use axum::{
    extract::{Path, Query, State},
    http::StatusCode,
    response::IntoResponse,
    routing::{delete, get, post, put},
    Json, Router,
};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::db::Database;
use crate::models::{AppSettings, DownloadConfig, Folder};
use crate::services::FeedService;

/// 应用共享状态
pub struct AppState {
    pub db: Arc<Database>,
    pub feed_service: FeedService,
}

// ─────────────────────────── Request / Response 类型 ───────────────────────────

#[derive(Deserialize)]
pub struct SubscribeRequest {
    pub url: String,
}

#[derive(Deserialize)]
pub struct PaginationParams {
    pub limit: Option<i64>,
    pub offset: Option<i64>,
}

#[derive(Serialize)]
pub struct ApiResponse<T: Serialize> {
    pub success: bool,
    pub data: Option<T>,
    pub error: Option<String>,
}

impl<T: Serialize> ApiResponse<T> {
    pub fn ok(data: T) -> Json<Self> {
        Json(Self {
            success: true,
            data: Some(data),
            error: None,
        })
    }

    pub fn err(msg: impl Into<String>) -> (StatusCode, Json<Self>) {
        (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(Self {
                success: false,
                data: None,
                error: Some(msg.into()),
            }),
        )
    }
}

#[derive(Serialize)]
pub struct StatsResponse {
    pub feeds: i64,
    pub articles: i64,
    pub unread: i64,
}

#[derive(Deserialize)]
pub struct ReadStatusRequest {
    pub is_read: bool,
}

#[derive(Deserialize)]
pub struct StarredRequest {
    pub is_starred: bool,
}

#[derive(Serialize)]
pub struct RefreshResponse {
    pub new_articles: u64,
    pub errors: u64,
}

#[derive(Deserialize)]
pub struct CreateFolderRequest {
    pub name: String,
    pub parent_id: Option<String>,
}

#[derive(Deserialize)]
pub struct UpdateFolderRequest {
    pub name: String,
    pub sort_order: Option<i32>,
}

#[derive(Deserialize)]
pub struct OpmlImportRequest {
    pub xml: String,
}

#[derive(Deserialize)]
pub struct SearchParams {
    pub q: String,
    pub regex: Option<bool>,
    pub limit: Option<i64>,
    pub offset: Option<i64>,
}

// ─────────────────────────── 路由定义 ───────────────────────────

pub fn create_router(state: Arc<AppState>) -> Router {
    Router::new()
        // Frontend
        .route("/", get(serve_index))
        .route("/favicon.ico", get(favicon))
        // Feed 管理
        .route("/api/feeds", get(list_feeds))
        .route("/api/feeds", post(subscribe_feed))
        .route("/api/feeds/{feed_id}", delete(unsubscribe_feed))
        .route("/api/feeds/{feed_id}/refresh", post(refresh_feed))
        .route("/api/feeds/{feed_id}/articles", get(list_feed_articles))
        .route("/api/feeds/{feed_id}/read-all", post(mark_feed_read))
        // 文章
        .route("/api/articles", get(list_all_articles))
        .route("/api/articles/unread", get(list_unread_articles))
        .route("/api/articles/starred", get(list_starred_articles))
        .route("/api/articles/search", get(search_articles))
        .route("/api/articles/{article_id}/read", put(set_article_read))
        .route("/api/articles/{article_id}/star", put(set_article_starred))
        // Folders
        .route("/api/folders", get(list_folders))
        .route("/api/folders", post(create_folder))
        .route("/api/folders/{folder_id}", put(update_folder_handler))
        .route("/api/folders/{folder_id}", delete(delete_folder_handler))
        .route(
            "/api/folders/{folder_id}/feeds/{feed_id}",
            post(add_feed_to_folder_handler),
        )
        .route(
            "/api/folders/{folder_id}/feeds/{feed_id}",
            delete(remove_feed_from_folder_handler),
        )
        // OPML
        .route("/api/opml/import", post(import_opml_handler))
        .route("/api/opml/export", get(export_opml_handler))
        // 设置
        .route("/api/settings", get(get_settings))
        .route("/api/settings", put(update_settings))
        // AI 摘要
        .route("/api/articles/{article_id}/summarize", post(summarize_article_handler))
        // Bangumi 封面搜索
        .route("/api/bangumi/search", get(bangumi_search))
        // 全局操作
        .route("/api/refresh", post(refresh_all_feeds))
        .route("/api/stats", get(get_stats))
        // 缓存管理
        .route("/api/cache/stats", get(cache_stats))
        .route("/api/cache/clear", post(cache_clear))
        // Webhook 通知测试
        .route("/api/notifications/test", post(test_webhook_handler))
        // 下载管理
        .route("/api/download/config", get(get_download_config))
        .route("/api/download/config", put(save_download_config_handler))
        .route("/api/download/test", post(test_download_connection))
        .route("/api/articles/{article_id}/download", post(download_article))
        .route("/api/download/history", get(get_download_history))
        // 番剧聚合
        .route("/api/anime", get(list_anime))
        // 健康检查
        .route("/health", get(health_check))
        // 认证（预留）
        .route("/api/auth/login", post(login_handler))
        .with_state(state)
}

// ─────────────────────────── Handler 实现 ───────────────────────────

/// 内嵌 Web UI
async fn serve_index() -> axum::response::Html<&'static str> {
    axum::response::Html(include_str!("../../static/index.html"))
}

/// Favicon
async fn favicon() -> impl IntoResponse {
    (
        [("content-type", "image/svg+xml")],
        r#"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><text y=".9em" font-size="90">📡</text></svg>"#,
    )
}

/// 健康检查
async fn health_check() -> &'static str {
    "OK"
}

/// 获取统计信息
async fn get_stats(
    State(state): State<Arc<AppState>>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let (feeds, articles, unread) = state
        .db
        .get_stats()
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok(StatsResponse {
        feeds,
        articles,
        unread,
    }))
}

/// 获取所有订阅源
async fn list_feeds(
    State(state): State<Arc<AppState>>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let feeds = state
        .db
        .get_all_feeds()
        .map_err(|e| ApiResponse::err(e.to_string()))?;
    Ok(ApiResponse::ok(feeds))
}

/// 订阅新的 Feed
async fn subscribe_feed(
    State(state): State<Arc<AppState>>,
    Json(req): Json<SubscribeRequest>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let feed = state
        .feed_service
        .subscribe(&req.url)
        .await
        .map_err(|e| ApiResponse::err(format!("Failed to subscribe: {:#}", e)))?;

    Ok((StatusCode::CREATED, ApiResponse::ok(feed)))
}

/// 取消订阅
async fn unsubscribe_feed(
    State(state): State<Arc<AppState>>,
    Path(feed_id): Path<String>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let feed_id =
        Uuid::parse_str(&feed_id).map_err(|_| ApiResponse::err("Invalid feed ID"))?;

    state
        .db
        .delete_feed(&feed_id)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok("Feed unsubscribed"))
}

/// 刷新单个 Feed
async fn refresh_feed(
    State(state): State<Arc<AppState>>,
    Path(feed_id): Path<String>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let feed_id =
        Uuid::parse_str(&feed_id).map_err(|_| ApiResponse::err("Invalid feed ID"))?;

    let feeds = state
        .db
        .get_all_feeds()
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    let feed = feeds
        .iter()
        .find(|f| f.id == feed_id)
        .ok_or_else(|| ApiResponse::err("Feed not found"))?;

    let new_count = state
        .feed_service
        .refresh_feed(feed)
        .await
        .map_err(|e| ApiResponse::err(format!("Refresh failed: {:#}", e)))?;

    Ok(ApiResponse::ok(RefreshResponse {
        new_articles: new_count,
        errors: 0,
    }))
}

/// 刷新所有 Feed
async fn refresh_all_feeds(
    State(state): State<Arc<AppState>>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let (new_articles, errors) = state
        .feed_service
        .refresh_all_feeds()
        .await
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok(RefreshResponse {
        new_articles,
        errors,
    }))
}

/// 获取某个 Feed 的文章列表
async fn list_feed_articles(
    State(state): State<Arc<AppState>>,
    Path(feed_id): Path<String>,
    Query(params): Query<PaginationParams>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let feed_id =
        Uuid::parse_str(&feed_id).map_err(|_| ApiResponse::err("Invalid feed ID"))?;

    let limit = params.limit.unwrap_or(50).min(200);
    let offset = params.offset.unwrap_or(0);

    let articles = state
        .db
        .get_articles_by_feed(&feed_id, limit, offset)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok(articles))
}

/// 获取所有文章（时间线）
async fn list_all_articles(
    State(state): State<Arc<AppState>>,
    Query(params): Query<PaginationParams>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let limit = params.limit.unwrap_or(50).min(200);
    let offset = params.offset.unwrap_or(0);

    let articles = state
        .db
        .get_all_articles(limit, offset)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok(articles))
}

/// 获取未读文章
async fn list_unread_articles(
    State(state): State<Arc<AppState>>,
    Query(params): Query<PaginationParams>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let limit = params.limit.unwrap_or(50).min(200);
    let offset = params.offset.unwrap_or(0);

    let articles = state
        .db
        .get_unread_articles(limit, offset)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok(articles))
}

/// 获取收藏文章
async fn list_starred_articles(
    State(state): State<Arc<AppState>>,
    Query(params): Query<PaginationParams>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let limit = params.limit.unwrap_or(50).min(200);
    let offset = params.offset.unwrap_or(0);

    let articles = state
        .db
        .get_starred_articles(limit, offset)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok(articles))
}

/// 搜索文章（支持正则表达式）
async fn search_articles(
    State(state): State<Arc<AppState>>,
    Query(params): Query<SearchParams>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let query = params.q.trim().to_string();
    if query.is_empty() {
        return Err(ApiResponse::err("搜索关键词不能为空"));
    }
    let use_regex = params.regex.unwrap_or(false);
    let limit = params.limit.unwrap_or(50).min(200);
    let offset = params.offset.unwrap_or(0);

    let articles = state
        .db
        .search_articles(&query, use_regex, limit, offset)
        .map_err(|e| ApiResponse::err(format!("搜索失败: {:#}", e)))?;

    Ok(ApiResponse::ok(articles))
}

/// 标记文章已读/未读
async fn set_article_read(
    State(state): State<Arc<AppState>>,
    Path(article_id): Path<String>,
    Json(req): Json<ReadStatusRequest>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let article_id =
        Uuid::parse_str(&article_id).map_err(|_| ApiResponse::err("Invalid article ID"))?;

    state
        .db
        .set_article_read(&article_id, req.is_read)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok("OK"))
}

/// 标记文章收藏/取消收藏
async fn set_article_starred(
    State(state): State<Arc<AppState>>,
    Path(article_id): Path<String>,
    Json(req): Json<StarredRequest>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let article_id =
        Uuid::parse_str(&article_id).map_err(|_| ApiResponse::err("Invalid article ID"))?;

    state
        .db
        .set_article_starred(&article_id, req.is_starred)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok("OK"))
}

/// 标记某个 Feed 全部已读
async fn mark_feed_read(
    State(state): State<Arc<AppState>>,
    Path(feed_id): Path<String>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let feed_id =
        Uuid::parse_str(&feed_id).map_err(|_| ApiResponse::err("Invalid feed ID"))?;

    let count = state
        .db
        .mark_feed_all_read(&feed_id)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok(serde_json::json!({
        "marked_read": count
    })))
}

// ─────────────────────────── Folder Handlers ───────────────────────────

/// 创建文件夹
async fn create_folder(
    State(state): State<Arc<AppState>>,
    Json(req): Json<CreateFolderRequest>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let folder = Folder {
        id: Uuid::new_v4(),
        name: req.name,
        parent_id: req.parent_id.and_then(|s| Uuid::parse_str(&s).ok()),
        sort_order: 0,
        created_at: chrono::Utc::now(),
    };

    state
        .db
        .insert_folder(&folder)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok((StatusCode::CREATED, ApiResponse::ok(folder)))
}

/// 获取所有文件夹
async fn list_folders(
    State(state): State<Arc<AppState>>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let folders = state
        .db
        .get_all_folders()
        .map_err(|e| ApiResponse::err(e.to_string()))?;
    Ok(ApiResponse::ok(folders))
}

/// 更新文件夹
async fn update_folder_handler(
    State(state): State<Arc<AppState>>,
    Path(folder_id): Path<String>,
    Json(req): Json<UpdateFolderRequest>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let folder_id =
        Uuid::parse_str(&folder_id).map_err(|_| ApiResponse::err("Invalid folder ID"))?;
    let sort_order = req.sort_order.unwrap_or(0);

    state
        .db
        .update_folder(&folder_id, &req.name, sort_order)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok("OK"))
}

/// 删除文件夹
async fn delete_folder_handler(
    State(state): State<Arc<AppState>>,
    Path(folder_id): Path<String>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let folder_id =
        Uuid::parse_str(&folder_id).map_err(|_| ApiResponse::err("Invalid folder ID"))?;

    state
        .db
        .delete_folder(&folder_id)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok("OK"))
}

/// 将 Feed 加入文件夹
async fn add_feed_to_folder_handler(
    State(state): State<Arc<AppState>>,
    Path((folder_id, feed_id)): Path<(String, String)>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let folder_id =
        Uuid::parse_str(&folder_id).map_err(|_| ApiResponse::err("Invalid folder ID"))?;
    let feed_id =
        Uuid::parse_str(&feed_id).map_err(|_| ApiResponse::err("Invalid feed ID"))?;

    state
        .db
        .add_feed_to_folder(&feed_id, &folder_id)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok("OK"))
}

/// 将 Feed 从文件夹移除
async fn remove_feed_from_folder_handler(
    State(state): State<Arc<AppState>>,
    Path((folder_id, feed_id)): Path<(String, String)>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let folder_id =
        Uuid::parse_str(&folder_id).map_err(|_| ApiResponse::err("Invalid folder ID"))?;
    let feed_id =
        Uuid::parse_str(&feed_id).map_err(|_| ApiResponse::err("Invalid feed ID"))?;

    state
        .db
        .remove_feed_from_folder(&feed_id, &folder_id)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok("OK"))
}

// ─────────────────────────── OPML Handlers ───────────────────────────

/// 导入 OPML
async fn import_opml_handler(
    State(state): State<Arc<AppState>>,
    Json(req): Json<OpmlImportRequest>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let outlines = crate::opml::parse_opml(&req.xml)
        .map_err(|e| ApiResponse::err(format!("Failed to parse OPML: {}", e)))?;

    let result = state
        .feed_service
        .import_opml(&outlines)
        .await
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok(result))
}

/// 导出 OPML
async fn export_opml_handler(
    State(state): State<Arc<AppState>>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let feeds = state
        .db
        .get_all_feeds()
        .map_err(|e| ApiResponse::err(e.to_string()))?;
    let folders = state
        .db
        .get_all_folders()
        .map_err(|e| ApiResponse::err(e.to_string()))?;
    let feed_folder_map = state
        .db
        .get_feed_folder_map()
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    let xml = crate::opml::export_opml(&feeds, &folders, &feed_folder_map)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok((
        [("content-type", "application/xml; charset=utf-8")],
        xml,
    ))
}

// ─────────────────────────── Settings Handlers ───────────────────────────

/// 获取设置（API 密钥脱敏）
async fn get_settings(
    State(state): State<Arc<AppState>>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let mut settings = state
        .db
        .get_app_settings()
        .map_err(|e| ApiResponse::err(e.to_string()))?;
    // 脱敏 API key
    if !settings.ai_api_key.is_empty() {
        let len = settings.ai_api_key.len();
        if len > 8 {
            settings.ai_api_key = format!(
                "{}...{}",
                &settings.ai_api_key[..4],
                &settings.ai_api_key[len - 4..]
            );
        } else {
            settings.ai_api_key = "****".to_string();
        }
    }
    // 脱敏 Bangumi token
    if !settings.bangumi_token.is_empty() {
        let len = settings.bangumi_token.len();
        if len > 8 {
            settings.bangumi_token = format!(
                "{}...{}",
                &settings.bangumi_token[..4],
                &settings.bangumi_token[len - 4..]
            );
        } else {
            settings.bangumi_token = "****".to_string();
        }
    }
    Ok(ApiResponse::ok(settings))
}

/// 更新设置
async fn update_settings(
    State(state): State<Arc<AppState>>,
    Json(settings): Json<AppSettings>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let mut to_save = settings;
    // 如果 key 看起来是脱敏的，保留原值
    if to_save.ai_api_key.contains("...") || to_save.ai_api_key == "****" {
        let existing = state
            .db
            .get_app_settings()
            .map_err(|e| ApiResponse::err(e.to_string()))?;
        to_save.ai_api_key = existing.ai_api_key;
    }
    if to_save.bangumi_token.contains("...") || to_save.bangumi_token == "****" {
        let existing = state
            .db
            .get_app_settings()
            .map_err(|e| ApiResponse::err(e.to_string()))?;
        to_save.bangumi_token = existing.bangumi_token;
    }
    state
        .db
        .save_app_settings(&to_save)
        .map_err(|e| ApiResponse::err(e.to_string()))?;
    Ok(ApiResponse::ok("OK"))
}

/// AI 摘要
async fn summarize_article_handler(
    State(state): State<Arc<AppState>>,
    Path(article_id): Path<String>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let article_id =
        Uuid::parse_str(&article_id).map_err(|_| ApiResponse::err("Invalid article ID"))?;

    let summary = state
        .feed_service
        .summarize_article(&article_id)
        .await
        .map_err(|e| ApiResponse::err(format!("{:#}", e)))?;

    Ok(ApiResponse::ok(serde_json::json!({ "summary": summary })))
}

// ─────────────────────────── Bangumi 封面搜索 ───────────────────────────

#[derive(Deserialize)]
pub struct BangumiSearchParams {
    pub q: String,
}

/// 搜索 Bangumi 获取动漫封面（带缓存）
async fn bangumi_search(
    State(state): State<Arc<AppState>>,
    Query(params): Query<BangumiSearchParams>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let query = params.q.trim().to_string();
    if query.is_empty() {
        return Err(ApiResponse::err("搜索关键词不能为空"));
    }

    // 检查缓存
    if let Ok(Some((cover_url, bgm_id, bgm_name))) = state.db.get_cached_cover(&query) {
        return Ok(ApiResponse::ok(serde_json::json!({
            "cover_url": cover_url,
            "bgm_id": bgm_id,
            "bgm_name": bgm_name,
            "cached": true
        })));
    }

    let settings = state
        .db
        .get_app_settings()
        .map_err(|e| ApiResponse::err(e.to_string()))?;
    if settings.bangumi_token.is_empty() {
        return Err(ApiResponse::err("Bangumi 令牌未配置，请在设置中填入"));
    }

    // 调用 Bangumi API v0
    let client = reqwest::Client::new();
    let resp = client
        .post("https://api.bgm.tv/v0/search/subjects")
        .header(
            "Authorization",
            format!("Bearer {}", settings.bangumi_token),
        )
        .header("User-Agent", "FeedFlow/0.1 (RSS Reader)")
        .json(&serde_json::json!({
            "keyword": query,
            "filter": { "type": [2] }
        }))
        .send()
        .await
        .map_err(|e| ApiResponse::err(format!("Bangumi 请求失败: {}", e)))?;

    let body: serde_json::Value = resp
        .json()
        .await
        .map_err(|e| ApiResponse::err(format!("Bangumi 响应解析失败: {}", e)))?;

    let first = body["data"].as_array().and_then(|arr| arr.first());

    if let Some(item) = first {
        let cover_url = item["images"]["common"]
            .as_str()
            .or_else(|| item["images"]["medium"].as_str())
            .or_else(|| item["images"]["large"].as_str())
            .unwrap_or("");
        let bgm_id = item["id"]
            .as_u64()
            .map(|v| v.to_string())
            .unwrap_or_default();
        let bgm_name = item["name_cn"]
            .as_str()
            .or_else(|| item["name"].as_str())
            .unwrap_or("");

        let _ = state
            .db
            .set_cached_cover(&query, cover_url, &bgm_id, bgm_name);

        Ok(ApiResponse::ok(serde_json::json!({
            "cover_url": cover_url,
            "bgm_id": bgm_id,
            "bgm_name": bgm_name,
            "cached": false
        })))
    } else {
        let _ = state.db.set_cached_cover(&query, "", "", "");
        Ok(ApiResponse::ok(serde_json::json!({
            "cover_url": "",
            "bgm_id": "",
            "bgm_name": "",
            "cached": false
        })))
    }
}

// ─────────────────────────── Auth Handler ───────────────────────────

#[derive(Deserialize)]
pub struct LoginRequest {
    pub password: String,
}

/// 登录接口 — 验证密码并返回 token
/// 当 FEEDFLOW_AUTH_TOKEN 未设置时返回错误提示
async fn login_handler(
    Json(req): Json<LoginRequest>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let token = std::env::var("FEEDFLOW_AUTH_TOKEN").unwrap_or_default();

    if token.is_empty() {
        return Ok(ApiResponse::ok(serde_json::json!({
            "token": "",
            "message": "认证未启用，无需登录"
        })));
    }

    if req.password == token {
        Ok(ApiResponse::ok(serde_json::json!({
            "token": token,
            "message": "登录成功"
        })))
    } else {
        Err(ApiResponse::err("密码错误"))
    }
}

// ─────────────────────────── Cache Management ───────────────────────────

/// 缓存统计
async fn cache_stats(
    State(state): State<Arc<AppState>>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let db_path = state
        .db
        .get_db_path()
        .unwrap_or_else(|_| "feedflow.db".to_string());

    let stats = state
        .db
        .get_cache_stats(&db_path)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok(stats))
}

/// 清理缓存
#[derive(Deserialize)]
pub struct CacheClearRequest {
    pub days_old: Option<i64>,
}

async fn cache_clear(
    State(state): State<Arc<AppState>>,
    Json(req): Json<CacheClearRequest>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let days = req.days_old.unwrap_or(30);
    let result = state
        .db
        .clear_cache(days)
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    Ok(ApiResponse::ok(result))
}

// ─────────────────────────── Webhook Test ───────────────────────────

/// 测试 webhook 通知
async fn test_webhook_handler(
    State(state): State<Arc<AppState>>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let result = state
        .feed_service
        .test_webhook()
        .await
        .map_err(|e| ApiResponse::err(format!("{:#}", e)))?;

    Ok(ApiResponse::ok(serde_json::json!({ "result": result })))
}

// ─────────────────────────── Download Management ───────────────────────────

/// 获取下载配置
async fn get_download_config(
    State(state): State<Arc<AppState>>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let mut config = state
        .db
        .get_download_config()
        .map_err(|e| ApiResponse::err(e.to_string()))?;
    // 脱敏密码
    if !config.password.is_empty() {
        config.password = "****".to_string();
    }
    Ok(ApiResponse::ok(config))
}

/// 保存下载配置
async fn save_download_config_handler(
    State(state): State<Arc<AppState>>,
    Json(mut config): Json<DownloadConfig>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    // 如果密码是脱敏的，保留原值
    if config.password == "****" {
        let existing = state.db.get_download_config()
            .map_err(|e| ApiResponse::err(e.to_string()))?;
        config.password = existing.password;
    }
    state
        .db
        .save_download_config(&config)
        .map_err(|e| ApiResponse::err(e.to_string()))?;
    Ok(ApiResponse::ok("OK"))
}

/// 测试下载客户端连接
async fn test_download_connection(
    State(state): State<Arc<AppState>>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let config = state
        .db
        .get_download_config()
        .map_err(|e| ApiResponse::err(e.to_string()))?;
    if config.host.is_empty() {
        return Err(ApiResponse::err("下载客户端地址未配置"));
    }
    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(10))
        .build()
        .map_err(|e| ApiResponse::err(e.to_string()))?;
    let ok = crate::download::test_connection(&client, &config)
        .await
        .map_err(|e| ApiResponse::err(format!("连接测试失败: {:#}", e)))?;
    Ok(ApiResponse::ok(serde_json::json!({ "connected": ok })))
}

/// 推送文章种子到下载客户端
async fn download_article(
    State(state): State<Arc<AppState>>,
    Path(article_id): Path<String>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let uuid = Uuid::parse_str(&article_id)
        .map_err(|_| ApiResponse::err("Invalid article ID"))?;

    let article = state
        .db
        .get_article_by_id(&uuid)
        .map_err(|e| ApiResponse::err(e.to_string()))?
        .ok_or_else(|| ApiResponse::err("文章不存在"))?;

    let torrent_url = article.enclosure_url
        .as_deref()
        .filter(|u| !u.is_empty())
        .ok_or_else(|| ApiResponse::err("该文章没有可下载的种子/磁力链接"))?;

    let config = state
        .db
        .get_download_config()
        .map_err(|e| ApiResponse::err(e.to_string()))?;
    if config.host.is_empty() {
        return Err(ApiResponse::err("下载客户端未配置，请先在设置中配置"));
    }

    let client = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(15))
        .build()
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    crate::download::add_torrent(&client, &config, torrent_url)
        .await
        .map_err(|e| ApiResponse::err(format!("推送失败: {:#}", e)))?;

    // 记录下载历史
    let _ = state.db.mark_downloaded(&article_id, torrent_url);

    Ok(ApiResponse::ok(serde_json::json!({
        "status": "sent",
        "torrent_url": torrent_url
    })))
}

/// 获取下载历史
async fn get_download_history(
    State(state): State<Arc<AppState>>,
    Query(params): Query<PaginationParams>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let limit = params.limit.unwrap_or(100).min(500);
    let history = state
        .db
        .get_download_history(limit)
        .map_err(|e| ApiResponse::err(e.to_string()))?;
    Ok(ApiResponse::ok(history))
}

// ─────────────────────────── Anime Aggregation ───────────────────────────

/// 番剧聚合列表 — 从动漫类 RSS 源聚合番剧信息
async fn list_anime(
    State(state): State<Arc<AppState>>,
) -> Result<impl IntoResponse, (StatusCode, Json<ApiResponse<()>>)> {
    let feeds = state.db.get_all_feeds()
        .map_err(|e| ApiResponse::err(e.to_string()))?;

    // 筛选动漫类 RSS 源（mikan/nyaa/dmhy/acg.rip/bangumi.moe）
    let anime_feed_ids: Vec<_> = feeds.iter()
        .filter(|f| is_anime_source(&f.url))
        .map(|f| f.id)
        .collect();

    if anime_feed_ids.is_empty() {
        return Ok(ApiResponse::ok(Vec::<crate::models::AnimeInfo>::new()));
    }

    // 获取这些 feed 的所有文章
    let mut all_articles = Vec::new();
    for fid in &anime_feed_ids {
        if let Ok(articles) = state.db.get_articles_by_feed(fid, 500, 0) {
            all_articles.extend(articles);
        }
    }

    // 获取已下载集合
    let downloaded_ids = state.db.get_downloaded_article_ids()
        .unwrap_or_default();

    // 按番剧名聚合
    let mut anime_map: std::collections::HashMap<String, Vec<crate::models::AnimeEpisode>> =
        std::collections::HashMap::new();

    for article in &all_articles {
        let parsed = crate::mikan::parse_anime_title(&article.title);
        if parsed.title.is_empty() { continue; }

        let ep = crate::models::AnimeEpisode {
            article_id: article.id.to_string(),
            title: article.title.clone(),
            episode: parsed.episode,
            fansub: parsed.fansub,
            resolution: parsed.resolution,
            file_size: parsed.file_size,
            enclosure_url: article.enclosure_url.clone(),
            content_length: article.content_length,
            published_at: article.published_at.map(|dt| dt.to_rfc3339()),
            is_downloaded: downloaded_ids.contains(&article.id.to_string()),
        };

        anime_map.entry(parsed.title.clone())
            .or_default()
            .push(ep);
    }

    // 构建 AnimeInfo，查询封面缓存
    let mut anime_list: Vec<crate::models::AnimeInfo> = Vec::new();
    for (name, mut episodes) in anime_map {
        // 按集数排序
        episodes.sort_by(|a, b| {
            let ea = a.episode.as_deref().and_then(|e| e.parse::<i32>().ok()).unwrap_or(0);
            let eb = b.episode.as_deref().and_then(|e| e.parse::<i32>().ok()).unwrap_or(0);
            ea.cmp(&eb)
        });

        // 查询封面缓存
        let (cover_url, bgm_id, bgm_name, summary, eps_count, air_date, rating) =
            state.db.get_cached_cover_full(&name)
                .ok()
                .flatten()
                .unwrap_or_default();

        anime_list.push(crate::models::AnimeInfo {
            name: name.clone(),
            cover_url: if cover_url.is_empty() { None } else { Some(cover_url) },
            bgm_id: if bgm_id.is_empty() { None } else { Some(bgm_id) },
            bgm_name: if bgm_name.is_empty() { None } else { Some(bgm_name) },
            summary,
            eps_count,
            air_date,
            rating,
            episodes,
        });
    }

    // 按番剧名排序
    anime_list.sort_by(|a, b| a.name.cmp(&b.name));

    Ok(ApiResponse::ok(anime_list))
}

/// 判断 RSS 源是否为动漫下载站
fn is_anime_source(url: &str) -> bool {
    let url_lower = url.to_lowercase();
    url_lower.contains("mikan") || url_lower.contains("nyaa")
        || url_lower.contains("dmhy") || url_lower.contains("acg.rip")
        || url_lower.contains("bangumi.moe") || url_lower.contains("acgrip")
}
