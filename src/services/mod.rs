use std::sync::Arc;

use anyhow::Result;
use chrono::Utc;
use tracing::{error, info, warn};
use uuid::Uuid;

use crate::db::Database;
use crate::feed::FeedFetcher;
use crate::models::Feed;

/// Feed 服务 — 业务逻辑层，协调抓取引擎与数据库
pub struct FeedService {
    db: Arc<Database>,
    fetcher: FeedFetcher,
}

impl FeedService {
    pub fn new(db: Arc<Database>) -> Result<Self> {
        let fetcher = FeedFetcher::new()?;
        Ok(Self { db, fetcher })
    }

    /// 订阅新的 RSS 源
    /// 1. 自动发现 Feed URL（如果给的是网页地址）
    /// 2. 抓取并解析 Feed
    /// 3. 存入数据库
    pub async fn subscribe(&self, url: &str) -> Result<Feed> {
        // 检查是否已订阅
        let normalized_url = normalize_feed_url(url);
        if let Some(existing) = self.db.get_feed_by_url(&normalized_url)? {
            info!(feed_url = %normalized_url, "Feed already subscribed");
            return Ok(existing);
        }

        // 尝试自动发现 Feed URL（失败时回退到原始 URL）
        let feed_url = match self.fetcher.discover_feed(&normalized_url).await {
            Ok(urls) if !urls.is_empty() => urls[0].clone(),
            Ok(_) => normalized_url.clone(),
            Err(e) => {
                warn!(url = %normalized_url, error = %e, "Feed discovery failed, trying URL directly");
                normalized_url.clone()
            }
        };

        // 再次检查发现的 URL 是否已订阅
        if feed_url != normalized_url {
            if let Some(existing) = self.db.get_feed_by_url(&feed_url)? {
                info!(feed_url = %feed_url, "Discovered feed already subscribed");
                return Ok(existing);
            }
        }

        // 创建 Feed 记录
        let now = Utc::now();
        let mut feed = Feed {
            id: Uuid::new_v4(),
            title: feed_url.clone(), // 临时标题，抓取后更新
            url: feed_url,
            site_url: None,
            description: None,
            icon_url: None,
            language: None,
            etag: None,
            last_modified: None,
            last_fetched_at: None,
            fetch_interval_secs: 900, // 默认 15 分钟
            error_count: 0,
            last_error: None,
            created_at: now,
            updated_at: now,
        };

        // 首次抓取
        match self.fetcher.fetch_feed(&feed).await {
            Ok(Some(result)) => {
                // 更新 Feed 元信息
                if let Some(ref title) = result.updated_title {
                    feed.title = title.clone();
                }
                feed.site_url = result.updated_site_url.clone();
                feed.description = result.updated_description.clone();
                feed.icon_url = result.updated_icon_url.clone();
                feed.etag = result.etag.clone();
                feed.last_modified = result.last_modified.clone();
                feed.last_fetched_at = Some(Utc::now());

                // 存入数据库
                self.db.insert_feed(&feed)?;

                // 存入文章
                let mut new_count = 0;
                for article in &result.new_articles {
                    if self.db.insert_article_if_new(article)? {
                        new_count += 1;
                    }
                }

                info!(
                    feed_title = %feed.title,
                    feed_url = %feed.url,
                    new_articles = new_count,
                    "Feed subscribed successfully"
                );
            }
            Ok(None) => {
                // 304 — 不太可能在首次抓取时发生，但还是处理
                self.db.insert_feed(&feed)?;
                warn!(feed_url = %feed.url, "First fetch returned 304");
            }
            Err(e) => {
                // 首次抓取失败，仍然保存 Feed（用户可以手动重试）
                feed.error_count = 1;
                feed.last_error = Some(e.to_string());
                self.db.insert_feed(&feed)?;
                warn!(feed_url = %feed.url, error = %e, "First fetch failed, feed saved anyway");
            }
        }

        Ok(feed)
    }

    /// 刷新单个 Feed — 抓取最新内容
    pub async fn refresh_feed(&self, feed: &Feed) -> Result<u64> {
        match self.fetcher.fetch_feed(feed).await {
            Ok(Some(result)) => {
                // 更新 Feed 元信息
                self.db.update_feed_metadata(
                    &feed.id,
                    result.updated_title.as_deref(),
                    result.updated_description.as_deref(),
                    result.updated_site_url.as_deref(),
                    result.updated_icon_url.as_deref(),
                )?;

                // 更新抓取状态
                self.db.update_feed_fetch_status(
                    &feed.id,
                    result.etag.as_deref(),
                    result.last_modified.as_deref(),
                    0, // 重置错误计数
                    None,
                )?;

                // 存入新文章
                let mut new_count = 0u64;
                for article in &result.new_articles {
                    if self.db.insert_article_if_new(article)? {
                        new_count += 1;
                    }
                }

                if new_count > 0 {
                    info!(
                        feed_url = %feed.url,
                        new_articles = new_count,
                        "Feed refreshed with new articles"
                    );
                }

                Ok(new_count)
            }
            Ok(None) => {
                // 304 Not Modified
                self.db.update_feed_fetch_status(
                    &feed.id,
                    feed.etag.as_deref(),
                    feed.last_modified.as_deref(),
                    0,
                    None,
                )?;
                Ok(0)
            }
            Err(e) => {
                let new_error_count = feed.error_count + 1;
                self.db.update_feed_fetch_status(
                    &feed.id,
                    feed.etag.as_deref(),
                    feed.last_modified.as_deref(),
                    new_error_count,
                    Some(&e.to_string()),
                )?;
                error!(
                    feed_url = %feed.url,
                    error = %e,
                    error_count = new_error_count,
                    "Feed refresh failed"
                );
                Err(e)
            }
        }
    }

    /// 刷新所有 Feed
    pub async fn refresh_all_feeds(&self) -> Result<(u64, u64)> {
        let feeds = self.db.get_all_feeds()?;
        let mut total_new = 0u64;
        let mut error_count = 0u64;

        for feed in &feeds {
            match self.refresh_feed(feed).await {
                Ok(new) => total_new += new,
                Err(_) => error_count += 1,
            }
        }

        info!(
            feeds_count = feeds.len(),
            new_articles = total_new,
            errors = error_count,
            "All feeds refreshed"
        );

        Ok((total_new, error_count))
    }

    /// 从 OPML 导入 Feed 和文件夹
    pub async fn import_opml(
        &self,
        outlines: &[crate::opml::OpmlOutline],
    ) -> Result<crate::models::OpmlImportResult> {
        let mut feeds_imported = 0u64;
        let mut folders_created = 0u64;
        let mut errors = 0u64;

        for outline in outlines {
            if let Some(ref xml_url) = outline.xml_url {
                match self.subscribe(xml_url).await {
                    Ok(_) => feeds_imported += 1,
                    Err(e) => {
                        warn!(url = %xml_url, error = %e, "OPML import: failed to subscribe feed");
                        errors += 1;
                    }
                }
            } else if !outline.children.is_empty() {
                let folder = crate::models::Folder {
                    id: Uuid::new_v4(),
                    name: outline.text.clone(),
                    parent_id: None,
                    sort_order: 0,
                    created_at: Utc::now(),
                };

                match self.db.insert_folder(&folder) {
                    Ok(_) => {
                        folders_created += 1;
                        for child in &outline.children {
                            if let Some(ref xml_url) = child.xml_url {
                                match self.subscribe(xml_url).await {
                                    Ok(feed) => {
                                        feeds_imported += 1;
                                        if let Err(e) =
                                            self.db.add_feed_to_folder(&feed.id, &folder.id)
                                        {
                                            warn!(error = %e, "OPML import: failed to add feed to folder");
                                        }
                                    }
                                    Err(e) => {
                                        warn!(url = %xml_url, error = %e, "OPML import: failed to subscribe feed");
                                        errors += 1;
                                    }
                                }
                            }
                        }
                    }
                    Err(e) => {
                        warn!(name = %outline.text, error = %e, "OPML import: failed to create folder");
                        errors += 1;
                    }
                }
            }
        }

        Ok(crate::models::OpmlImportResult {
            feeds_imported,
            folders_created,
            errors,
        })
    }

    /// AI 摘要：调用配置的 API 为文章生成摘要
    pub async fn summarize_article(&self, article_id: &Uuid) -> Result<String> {
        let settings = self.db.get_app_settings()?;
        if !settings.ai_enabled {
            anyhow::bail!("AI 功能未启用，请在设置中配置 API");
        }
        if settings.ai_api_key.is_empty() {
            anyhow::bail!("API 密钥未配置");
        }

        let article = self
            .db
            .get_article_by_id(article_id)?
            .ok_or_else(|| anyhow::anyhow!("文章不存在"))?;

        if let Some(ref s) = article.ai_summary {
            if !s.is_empty() {
                return Ok(s.clone());
            }
        }

        let content = article
            .content_text
            .as_deref()
            .or(article.summary.as_deref())
            .unwrap_or(&article.title);

        let truncated = if content.len() > 4000 {
            &content[..4000]
        } else {
            content
        };

        let prompt = format!("{}{}", settings.ai_summary_prompt, truncated);

        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(60))
            .build()?;

        let summary = match settings.ai_provider.as_str() {
            "claude" => {
                let resp = client
                    .post(format!(
                        "{}/messages",
                        settings.ai_api_url.trim_end_matches('/')
                    ))
                    .header("x-api-key", &settings.ai_api_key)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .json(&serde_json::json!({
                        "model": settings.ai_model,
                        "max_tokens": 500,
                        "messages": [{"role": "user", "content": prompt}]
                    }))
                    .send()
                    .await?;
                let body: serde_json::Value = resp.json().await?;
                if let Some(err) = body.get("error") {
                    anyhow::bail!(
                        "API 错误: {}",
                        err["message"].as_str().unwrap_or("未知错误")
                    );
                }
                body["content"][0]["text"]
                    .as_str()
                    .unwrap_or("AI 返回为空")
                    .to_string()
            }
            _ => {
                // OpenAI 兼容格式（OpenAI / DeepSeek / 通义千问 / Moonshot 等）
                let resp = client
                    .post(format!(
                        "{}/chat/completions",
                        settings.ai_api_url.trim_end_matches('/')
                    ))
                    .header("Authorization", format!("Bearer {}", settings.ai_api_key))
                    .header("content-type", "application/json")
                    .json(&serde_json::json!({
                        "model": settings.ai_model,
                        "messages": [{"role": "user", "content": prompt}],
                        "max_tokens": 500
                    }))
                    .send()
                    .await?;
                let body: serde_json::Value = resp.json().await?;
                if let Some(err) = body.get("error") {
                    anyhow::bail!(
                        "API 错误: {}",
                        err["message"].as_str().unwrap_or("未知错误")
                    );
                }
                body["choices"][0]["message"]["content"]
                    .as_str()
                    .unwrap_or("AI 返回为空")
                    .to_string()
            }
        };

        self.db.update_article_ai_summary(article_id, &summary)?;
        Ok(summary)
    }
}

/// 规范化 Feed URL
fn normalize_feed_url(url: &str) -> String {
    let mut url = url.trim().to_string();

    // 补全协议
    if !url.starts_with("http://") && !url.starts_with("https://") {
        url = format!("https://{}", url);
    }

    // 去除尾部斜杠
    while url.ends_with('/') {
        url.pop();
    }

    url
}