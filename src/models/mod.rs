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
    pub enclosure_url: Option<String>,   // 附件/种子下载链接
    pub content_length: i64,             // 附件大小(字节)
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
    pub webhook_enabled: bool,
    pub webhook_url: String,
    pub webhook_template: String,
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
            webhook_enabled: false,
            webhook_url: String::new(),
            webhook_template: r#"{"text": "FeedFlow: {{count}} 篇新文章"}"#.to_string(),
        }
    }
}

/// 缓存统计
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheStats {
    pub db_size_bytes: u64,
    pub article_count: i64,
    pub feed_count: i64,
    pub cover_cache_count: i64,
    pub oldest_article: Option<String>,
}

/// 缓存清理结果
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CacheClearResult {
    pub articles_cleaned: u64,
    pub cover_cache_cleared: u64,
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

/// 下载配置
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DownloadConfig {
    pub client_type: String,    // "qbittorrent" / "aria2" / "transmission"
    pub host: String,           // "http://192.168.1.100:8080"
    pub username: String,
    pub password: String,
    pub save_path: String,
    pub auto_download: bool,
}

impl Default for DownloadConfig {
    fn default() -> Self {
        Self {
            client_type: "qbittorrent".to_string(),
            host: String::new(),
            username: String::new(),
            password: String::new(),
            save_path: String::new(),
            auto_download: false,
        }
    }
}

/// 下载历史记录
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DownloadHistory {
    pub article_id: String,
    pub torrent_url: String,
    pub downloaded_at: DateTime<Utc>,
    pub status: String,  // "sent" / "failed"
}

/// 番剧聚合信息
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnimeInfo {
    pub name: String,
    pub cover_url: Option<String>,
    pub bgm_id: Option<String>,
    pub bgm_name: Option<String>,
    pub summary: Option<String>,
    pub eps_count: Option<i32>,
    pub air_date: Option<String>,
    pub rating: Option<f64>,
    pub episodes: Vec<AnimeEpisode>,
}

/// 番剧单集
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AnimeEpisode {
    pub article_id: String,
    pub title: String,
    pub episode: Option<String>,
    pub fansub: Option<String>,
    pub resolution: Option<String>,
    pub file_size: Option<String>,
    pub enclosure_url: Option<String>,
    pub content_length: i64,
    pub published_at: Option<String>,
    pub is_downloaded: bool,
}
