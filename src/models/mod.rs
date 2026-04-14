use serde::{Deserialize, Serialize};
use chrono::{DateTime, Utc};
use uuid::Uuid;

/// RSS 订阅源
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Feed {
    pub id: Uuid,
    pub title: String,
    pub url: String,           // Feed URL
    pub site_url: Option<String>, // 网站主页 URL
    pub description: Option<String>,
    pub icon_url: Option<String>,
    pub language: Option<String>,
    pub etag: Option<String>,          // HTTP ETag (条件请求)
    pub last_modified: Option<String>, // HTTP Last-Modified
    pub last_fetched_at: Option<DateTime<Utc>>,
    pub fetch_interval_secs: i64,      // 自适应抓取间隔(秒)
    pub error_count: i32,              // 连续错误计数
    pub last_error: Option<String>,
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

/// 文章条目
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Article {
    pub id: Uuid,
    pub feed_id: Uuid,
    pub guid: Option<String>,      // Feed 提供的唯一标识
    pub title: String,
    pub url: Option<String>,       // 文章链接
    pub author: Option<String>,
    pub content_html: Option<String>,  // HTML 内容
    pub content_text: Option<String>,  // 纯文本内容
    pub summary: Option<String>,       // 原始摘要
    pub thumbnail_url: Option<String>,
    pub published_at: Option<DateTime<Utc>>,
    pub is_read: bool,
    pub is_starred: bool,
    pub ai_summary: Option<String>,    // AI 生成的摘要
    pub ai_tags: Option<String>,       // AI 标签 (JSON array)
    pub created_at: DateTime<Utc>,
    pub updated_at: DateTime<Utc>,
}

/// 订阅源分组/文件夹
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Folder {
    pub id: Uuid,
    pub name: String,
    pub parent_id: Option<Uuid>,
    pub sort_order: i32,
    pub created_at: DateTime<Utc>,
}

/// Feed 与 Folder 的关联
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FeedFolder {
    pub feed_id: Uuid,
    pub folder_id: Uuid,
}

/// Folder with feed count (for API listing)
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FolderWithCount {
    pub id: Uuid,
    pub name: String,
    pub parent_id: Option<Uuid>,
    pub sort_order: i32,
    pub created_at: DateTime<Utc>,
    pub feed_count: i64,
}

/// Result of OPML import operation
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct OpmlImportResult {
    pub feeds_imported: u64,
    pub folders_created: u64,
    pub errors: u64,
}

/// 应用设置（AI API 配置等）
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AppSettings {
    pub ai_enabled: bool,
    pub ai_provider: String,
    pub ai_api_key: String,
    pub ai_api_url: String,
    pub ai_model: String,
    pub ai_summary_prompt: String,
    pub bangumi_token: String,
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            ai_enabled: false,
            ai_provider: "openai".to_string(),
            ai_api_key: String::new(),
            ai_api_url: "https://api.openai.com/v1".to_string(),
            ai_model: "gpt-4o-mini".to_string(),
            ai_summary_prompt: "请用中文简要总结以下文章内容，不超过200字：\n\n".to_string(),
            bangumi_token: String::new(),
        }
    }
}

/// 抓取结果
#[derive(Debug)]
pub struct FetchResult {
    #[allow(dead_code)]
    pub feed_id: Uuid,
    pub new_articles: Vec<Article>,
    pub updated_title: Option<String>,
    pub updated_description: Option<String>,
    pub updated_site_url: Option<String>,
    pub updated_icon_url: Option<String>,
    pub etag: Option<String>,
    pub last_modified: Option<String>,
}
