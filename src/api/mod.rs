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
use crate::models::{AppSettings, Folder};
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
        // 健康检查
        .route("/health", get(health_check))
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
