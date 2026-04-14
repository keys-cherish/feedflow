use anyhow::{Context, Result};
use chrono::Utc;
use reqwest::header::{HeaderMap, HeaderValue, IF_MODIFIED_SINCE, IF_NONE_MATCH, USER_AGENT};
use tracing::{debug, info};
use uuid::Uuid;

use crate::models::{Article, Feed, FetchResult};

/// RSS 抓取引擎 — 负责从远端拉取 Feed 并解析为统一的数据模型
pub struct FeedFetcher {
    client: reqwest::Client,
}

impl FeedFetcher {
    pub fn new() -> Result<Self> {
        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(30))
            .connect_timeout(std::time::Duration::from_secs(10))
            .gzip(true)
            .brotli(true)
            .deflate(true)
            .redirect(reqwest::redirect::Policy::limited(5))
            .build()
            .context("Failed to build HTTP client")?;

        Ok(Self { client })
    }

    /// 发现 Feed URL — 给定一个网页 URL，尝试自动发现其 RSS/Atom Feed
    pub async fn discover_feed(&self, url: &str) -> Result<Vec<String>> {
        let resp = self
            .client
            .get(url)
            .header(USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            .send()
            .await
            .context("Failed to fetch URL for feed discovery")?;

        let content_type = resp
            .headers()
            .get("content-type")
            .and_then(|v| v.to_str().ok())
            .unwrap_or("")
            .to_lowercase();

        // 如果直接就是 Feed，返回原 URL
        if content_type.contains("xml")
            || content_type.contains("rss")
            || content_type.contains("atom")
            || content_type.contains("feed+json")
        {
            return Ok(vec![url.to_string()]);
        }

        // 否则解析 HTML，查找 <link rel="alternate"> 标签
        let body = resp.text().await?;
        let mut feeds = Vec::new();

        // 简单的正则式查找（不引入完整 HTML parser）
        for line in body.lines() {
            let line_lower = line.to_lowercase();
            if line_lower.contains("application/rss+xml")
                || line_lower.contains("application/atom+xml")
                || line_lower.contains("application/feed+json")
            {
                // 提取 href 属性
                if let Some(href) = extract_href(line) {
                    let feed_url = resolve_url(url, &href)?;
                    feeds.push(feed_url);
                }
            }
        }

        if feeds.is_empty() {
            // 尝试常见路径
            let base = url::Url::parse(url)?;
            let common_paths = ["/feed", "/rss", "/atom.xml", "/feed.xml", "/rss.xml", "/index.xml"];
            for path in &common_paths {
                let candidate = base.join(path)?.to_string();
                if self.probe_feed(&candidate).await {
                    feeds.push(candidate);
                    break; // 找到一个就够了
                }
            }
        }

        Ok(feeds)
    }

    /// 探测一个 URL 是否是有效的 Feed
    async fn probe_feed(&self, url: &str) -> bool {
        match self
            .client
            .head(url)
            .header(USER_AGENT, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            .send()
            .await
        {
            Ok(resp) => {
                let ct = resp
                    .headers()
                    .get("content-type")
                    .and_then(|v| v.to_str().ok())
                    .unwrap_or("")
                    .to_lowercase();
                resp.status().is_success()
                    && (ct.contains("xml") || ct.contains("rss") || ct.contains("atom") || ct.contains("json"))
            }
            Err(_) => false,
        }
    }

    /// 抓取并解析 Feed
    /// 支持条件请求（ETag / Last-Modified），返回 None 表示内容未变化(304)
    pub async fn fetch_feed(&self, feed: &Feed) -> Result<Option<FetchResult>> {
        let mut headers = HeaderMap::new();
        headers.insert(
            USER_AGENT,
            HeaderValue::from_static("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"),
        );

        // 条件请求头 — 避免重复下载未变化的 Feed
        if let Some(ref etag) = feed.etag {
            if let Ok(val) = HeaderValue::from_str(etag) {
                headers.insert(IF_NONE_MATCH, val);
            }
        }
        if let Some(ref lm) = feed.last_modified {
            if let Ok(val) = HeaderValue::from_str(lm) {
                headers.insert(IF_MODIFIED_SINCE, val);
            }
        }

        info!(feed_url = %feed.url, "Fetching feed");

        let resp = self
            .client
            .get(&feed.url)
            .headers(headers)
            .send()
            .await
            .with_context(|| format!("Failed to fetch feed: {}", feed.url))?;

        // 304 Not Modified — 内容未变化
        if resp.status() == reqwest::StatusCode::NOT_MODIFIED {
            debug!(feed_url = %feed.url, "Feed not modified (304)");
            return Ok(None);
        }

        // 非 2xx 状态码
        if !resp.status().is_success() {
            anyhow::bail!(
                "Feed returned HTTP {}: {}",
                resp.status().as_u16(),
                feed.url
            );
        }

        // 提取响应头中的缓存信息
        let new_etag = resp
            .headers()
            .get("etag")
            .and_then(|v| v.to_str().ok())
            .map(String::from);
        let new_last_modified = resp
            .headers()
            .get("last-modified")
            .and_then(|v| v.to_str().ok())
            .map(String::from);

        let body = resp
            .bytes()
            .await
            .context("Failed to read feed response body")?;

        // 限制响应体大小（防止 OOM）
        if body.len() > 10 * 1024 * 1024 {
            anyhow::bail!("Feed response too large: {} bytes", body.len());
        }

        // 使用 feed-rs 解析（自动识别 RSS 2.0 / Atom / JSON Feed）
        let parsed = feed_rs::parser::parse(&body[..])
            .with_context(|| format!("Failed to parse feed: {}", feed.url))?;

        // 转换为统一数据模型
        let now = Utc::now();
        let new_articles: Vec<Article> = parsed
            .entries
            .iter()
            .map(|entry| {
                let title = entry
                    .title
                    .as_ref()
                    .map(|t| t.content.clone())
                    .unwrap_or_else(|| "(无标题)".to_string());

                let content_html = entry
                    .content
                    .as_ref()
                    .and_then(|c| c.body.clone())
                    .or_else(|| entry.summary.as_ref().map(|s| s.content.clone()));

                let content_text = content_html
                    .as_ref()
                    .map(|html| strip_html_tags(html));

                let url = entry
                    .links
                    .first()
                    .map(|l| l.href.clone());

                let author = entry
                    .authors
                    .first()
                    .map(|a| a.name.clone());

                let published_at = entry
                    .published
                    .or(entry.updated)
                    .map(|dt| dt.with_timezone(&Utc));

                let guid = entry.id.clone();

                let thumbnail_url = entry
                    .media
                    .first()
                    .and_then(|m| m.thumbnails.first())
                    .map(|t| t.image.uri.clone());

                // 提取附件/下载链接（种子、播客等）
                let enclosure_url: Option<String> = entry
                    .media
                    .iter()
                    .flat_map(|m| m.content.iter())
                    .filter_map(|c| c.url.as_ref())
                    .next()
                    .map(|u| u.to_string());

                let summary = entry
                    .summary
                    .as_ref()
                    .map(|s| strip_html_tags(&s.content));

                // 处理 HTML 内容，附加下载链接
                let sanitized_html = content_html.map(|h| sanitize_html(&h));
                let final_html = match (&sanitized_html, &enclosure_url) {
                    (Some(html), Some(enc)) => Some(format!(
                        r#"{}<p style="margin-top:12px"><a href="{}" target="_blank" rel="noopener noreferrer">📥 下载链接</a></p>"#,
                        html, enc
                    )),
                    (None, Some(enc)) => Some(format!(
                        r#"<p><a href="{}" target="_blank" rel="noopener noreferrer">📥 下载链接</a></p>"#,
                        enc
                    )),
                    _ => sanitized_html,
                };

                Article {
                    id: Uuid::new_v4(),
                    feed_id: feed.id,
                    guid: Some(guid),
                    title,
                    url,
                    author,
                    content_html: final_html,
                    content_text,
                    summary,
                    thumbnail_url,
                    published_at,
                    is_read: false,
                    is_starred: false,
                    ai_summary: None,
                    ai_tags: None,
                    created_at: now,
                    updated_at: now,
                }
            })
            .collect();

        let updated_title = parsed.title.map(|t| t.content);
        let updated_description = parsed.description.map(|d| d.content);
        let updated_site_url = parsed.links.first().map(|l| l.href.clone());
        let updated_icon_url = parsed.icon.map(|i| i.uri);

        info!(
            feed_url = %feed.url,
            articles_count = new_articles.len(),
            "Feed parsed successfully"
        );

        Ok(Some(FetchResult {
            feed_id: feed.id,
            new_articles,
            updated_title,
            updated_description,
            updated_site_url,
            updated_icon_url,
            etag: new_etag,
            last_modified: new_last_modified,
        }))
    }
}

/// 从 HTML 标签中提取 href 属性值
fn extract_href(tag: &str) -> Option<String> {
    let lower = tag.to_lowercase();
    let href_pos = lower.find("href=")?;
    let after_href = &tag[href_pos + 5..];

    let (quote, rest) = if after_href.starts_with('"') {
        ('"', &after_href[1..])
    } else if after_href.starts_with('\'') {
        ('\'', &after_href[1..])
    } else {
        return None;
    };

    let end = rest.find(quote)?;
    Some(rest[..end].to_string())
}

/// 将相对 URL 解析为绝对 URL
fn resolve_url(base: &str, href: &str) -> Result<String> {
    let base_url = url::Url::parse(base)?;
    let resolved = base_url.join(href)?;
    Ok(resolved.to_string())
}

/// 简单的 HTML 标签剥离（提取纯文本）
fn strip_html_tags(html: &str) -> String {
    let mut result = String::with_capacity(html.len());
    let mut in_tag = false;
    let mut in_entity = false;
    let mut entity = String::new();

    for ch in html.chars() {
        match ch {
            '<' => in_tag = true,
            '>' => {
                in_tag = false;
                // 块级标签后加换行
            }
            '&' if !in_tag => {
                in_entity = true;
                entity.clear();
                entity.push(ch);
            }
            ';' if in_entity => {
                entity.push(ch);
                // 解码常见 HTML 实体
                let decoded = match entity.as_str() {
                    "&amp;" => "&",
                    "&lt;" => "<",
                    "&gt;" => ">",
                    "&quot;" => "\"",
                    "&apos;" => "'",
                    "&#39;" => "'",
                    "&nbsp;" => " ",
                    _ => &entity,
                };
                result.push_str(decoded);
                in_entity = false;
            }
            _ if in_entity => {
                entity.push(ch);
                if entity.len() > 10 {
                    // 不是有效实体，原样输出
                    result.push_str(&entity);
                    in_entity = false;
                }
            }
            _ if !in_tag => result.push(ch),
            _ => {}
        }
    }

    // 压缩连续空白
    let mut prev_space = false;
    result
        .chars()
        .filter(|&c| {
            if c.is_whitespace() {
                if prev_space {
                    return false;
                }
                prev_space = true;
            } else {
                prev_space = false;
            }
            true
        })
        .collect::<String>()
        .trim()
        .to_string()
}

/// HTML 内容清洗（白名单标签，防 XSS）
fn sanitize_html(html: &str) -> String {
    ammonia::Builder::default()
        .add_tags(&["h1", "h2", "h3", "h4", "h5", "h6"])
        .add_tags(&["p", "br", "hr", "blockquote", "pre", "code"])
        .add_tags(&["ul", "ol", "li", "dl", "dt", "dd"])
        .add_tags(&["table", "thead", "tbody", "tr", "th", "td"])
        .add_tags(&["a", "img", "figure", "figcaption"])
        .add_tags(&["strong", "em", "b", "i", "u", "s", "mark", "sub", "sup"])
        .add_tag_attributes("a", &["href", "title"])
        .add_tag_attributes("img", &["src", "alt", "title", "width", "height"])
        .link_rel(Some("noopener noreferrer"))
        .clean(html)
        .to_string()
}
