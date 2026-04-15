use anyhow::Result;
use chrono::Utc;
use rusqlite::{params, Connection, OptionalExtension};
use std::path::Path;
use std::sync::Mutex;
use uuid::Uuid;

use crate::models::{Article, CacheClearResult, CacheStats, Feed, Folder, FolderWithCount};

/// 数据库管理器 — MVP 阶段使用 SQLite，后续可迁移到 PostgreSQL
pub struct Database {
    conn: Mutex<Connection>,
}

impl Database {
    /// 打开(或创建)数据库，并初始化表结构
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let conn = Connection::open(path)?;

        // 开启 WAL 模式，提升并发读性能
        conn.execute_batch("PRAGMA journal_mode=WAL;")?;
        conn.execute_batch("PRAGMA foreign_keys=ON;")?;
        conn.execute_batch("PRAGMA busy_timeout=5000;")?;

        let db = Self {
            conn: Mutex::new(conn),
        };
        db.init_tables()?;
        db.migrate_columns()?;
        Ok(db)
    }

    /// 创建所有表
    fn init_tables(&self) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS feeds (
                id                TEXT PRIMARY KEY,
                title             TEXT NOT NULL,
                url               TEXT NOT NULL UNIQUE,
                site_url          TEXT,
                description       TEXT,
                icon_url          TEXT,
                language          TEXT,
                etag              TEXT,
                last_modified     TEXT,
                last_fetched_at   TEXT,
                fetch_interval_secs INTEGER NOT NULL DEFAULT 900,
                error_count       INTEGER NOT NULL DEFAULT 0,
                last_error        TEXT,
                created_at        TEXT NOT NULL,
                updated_at        TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS articles (
                id              TEXT PRIMARY KEY,
                feed_id         TEXT NOT NULL REFERENCES feeds(id) ON DELETE CASCADE,
                guid            TEXT,
                title           TEXT NOT NULL,
                url             TEXT,
                author          TEXT,
                content_html    TEXT,
                content_text    TEXT,
                summary         TEXT,
                thumbnail_url   TEXT,
                published_at    TEXT,
                is_read         INTEGER NOT NULL DEFAULT 0,
                is_starred      INTEGER NOT NULL DEFAULT 0,
                ai_summary      TEXT,
                ai_tags         TEXT,
                created_at      TEXT NOT NULL,
                updated_at      TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS folders (
                id          TEXT PRIMARY KEY,
                name        TEXT NOT NULL,
                parent_id   TEXT REFERENCES folders(id),
                sort_order  INTEGER NOT NULL DEFAULT 0,
                created_at  TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS feed_folders (
                feed_id     TEXT NOT NULL REFERENCES feeds(id) ON DELETE CASCADE,
                folder_id   TEXT NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
                PRIMARY KEY (feed_id, folder_id)
            );

            CREATE TABLE IF NOT EXISTS settings (
                key   TEXT PRIMARY KEY,
                value TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS cover_cache (
                query     TEXT PRIMARY KEY,
                cover_url TEXT NOT NULL,
                bgm_id    TEXT,
                bgm_name  TEXT,
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS download_history (
                article_id TEXT NOT NULL PRIMARY KEY,
                torrent_url TEXT NOT NULL,
                downloaded_at TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'sent'
            );

            -- 索引：加速常用查询
            CREATE INDEX IF NOT EXISTS idx_articles_feed_id ON articles(feed_id);
            CREATE INDEX IF NOT EXISTS idx_articles_published_at ON articles(published_at DESC);
            CREATE INDEX IF NOT EXISTS idx_articles_guid ON articles(feed_id, guid);
            CREATE INDEX IF NOT EXISTS idx_articles_is_read ON articles(is_read) WHERE is_read = 0;
            CREATE INDEX IF NOT EXISTS idx_feeds_url ON feeds(url);
            ",
        )?;
        Ok(())
    }

    /// 幂等列迁移 — SQLite 不支持 IF NOT EXISTS 列添加，捕获重复错误即可
    fn migrate_columns(&self) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let alters = [
            "ALTER TABLE articles ADD COLUMN enclosure_url TEXT",
            "ALTER TABLE articles ADD COLUMN content_length INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE cover_cache ADD COLUMN summary TEXT",
            "ALTER TABLE cover_cache ADD COLUMN eps_count INTEGER",
            "ALTER TABLE cover_cache ADD COLUMN air_date TEXT",
            "ALTER TABLE cover_cache ADD COLUMN rating REAL",
        ];
        for sql in &alters {
            let _ = conn.execute(sql, []); // Ignore "duplicate column" errors
        }
        Ok(())
    }

    // ─────────────────────────── Feed CRUD ───────────────────────────

    /// 添加新的订阅源
    pub fn insert_feed(&self, feed: &Feed) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT INTO feeds (id, title, url, site_url, description, icon_url, language,
             etag, last_modified, last_fetched_at, fetch_interval_secs, error_count,
             last_error, created_at, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)",
            params![
                feed.id.to_string(),
                feed.title,
                feed.url,
                feed.site_url,
                feed.description,
                feed.icon_url,
                feed.language,
                feed.etag,
                feed.last_modified,
                feed.last_fetched_at.map(|t| t.to_rfc3339()),
                feed.fetch_interval_secs,
                feed.error_count,
                feed.last_error,
                feed.created_at.to_rfc3339(),
                feed.updated_at.to_rfc3339(),
            ],
        )?;
        Ok(())
    }

    /// 获取所有订阅源
    pub fn get_all_feeds(&self) -> Result<Vec<Feed>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, title, url, site_url, description, icon_url, language,
                    etag, last_modified, last_fetched_at, fetch_interval_secs,
                    error_count, last_error, created_at, updated_at
             FROM feeds ORDER BY title",
        )?;

        let feeds = stmt
            .query_map([], |row| {
                Ok(Feed {
                    id: Uuid::parse_str(&row.get::<_, String>(0)?).unwrap(),
                    title: row.get(1)?,
                    url: row.get(2)?,
                    site_url: row.get(3)?,
                    description: row.get(4)?,
                    icon_url: row.get(5)?,
                    language: row.get(6)?,
                    etag: row.get(7)?,
                    last_modified: row.get(8)?,
                    last_fetched_at: row
                        .get::<_, Option<String>>(9)?
                        .and_then(|s| chrono::DateTime::parse_from_rfc3339(&s).ok())
                        .map(|dt| dt.with_timezone(&Utc)),
                    fetch_interval_secs: row.get(10)?,
                    error_count: row.get(11)?,
                    last_error: row.get(12)?,
                    created_at: chrono::DateTime::parse_from_rfc3339(
                        &row.get::<_, String>(13)?,
                    )
                    .unwrap()
                    .with_timezone(&Utc),
                    updated_at: chrono::DateTime::parse_from_rfc3339(
                        &row.get::<_, String>(14)?,
                    )
                    .unwrap()
                    .with_timezone(&Utc),
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(feeds)
    }

    /// 根据 URL 查找 Feed
    pub fn get_feed_by_url(&self, url: &str) -> Result<Option<Feed>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, title, url, site_url, description, icon_url, language,
                    etag, last_modified, last_fetched_at, fetch_interval_secs,
                    error_count, last_error, created_at, updated_at
             FROM feeds WHERE url = ?1",
        )?;

        let feed = stmt
            .query_row(params![url], |row| {
                Ok(Feed {
                    id: Uuid::parse_str(&row.get::<_, String>(0)?).unwrap(),
                    title: row.get(1)?,
                    url: row.get(2)?,
                    site_url: row.get(3)?,
                    description: row.get(4)?,
                    icon_url: row.get(5)?,
                    language: row.get(6)?,
                    etag: row.get(7)?,
                    last_modified: row.get(8)?,
                    last_fetched_at: row
                        .get::<_, Option<String>>(9)?
                        .and_then(|s| chrono::DateTime::parse_from_rfc3339(&s).ok())
                        .map(|dt| dt.with_timezone(&Utc)),
                    fetch_interval_secs: row.get(10)?,
                    error_count: row.get(11)?,
                    last_error: row.get(12)?,
                    created_at: chrono::DateTime::parse_from_rfc3339(
                        &row.get::<_, String>(13)?,
                    )
                    .unwrap()
                    .with_timezone(&Utc),
                    updated_at: chrono::DateTime::parse_from_rfc3339(
                        &row.get::<_, String>(14)?,
                    )
                    .unwrap()
                    .with_timezone(&Utc),
                })
            })
            .optional()?;

        Ok(feed)
    }

    /// 更新 Feed 的抓取状态
    pub fn update_feed_fetch_status(
        &self,
        feed_id: &Uuid,
        etag: Option<&str>,
        last_modified: Option<&str>,
        error_count: i32,
        last_error: Option<&str>,
    ) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE feeds SET etag = ?2, last_modified = ?3, last_fetched_at = ?4,
             error_count = ?5, last_error = ?6, updated_at = ?7
             WHERE id = ?1",
            params![
                feed_id.to_string(),
                etag,
                last_modified,
                Utc::now().to_rfc3339(),
                error_count,
                last_error,
                Utc::now().to_rfc3339(),
            ],
        )?;
        Ok(())
    }

    /// 更新 Feed 的元信息（标题、描述等）
    pub fn update_feed_metadata(
        &self,
        feed_id: &Uuid,
        title: Option<&str>,
        description: Option<&str>,
        site_url: Option<&str>,
        icon_url: Option<&str>,
    ) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        if let Some(title) = title {
            conn.execute(
                "UPDATE feeds SET title = ?2, updated_at = ?3 WHERE id = ?1",
                params![feed_id.to_string(), title, Utc::now().to_rfc3339()],
            )?;
        }
        if let Some(desc) = description {
            conn.execute(
                "UPDATE feeds SET description = ?2, updated_at = ?3 WHERE id = ?1",
                params![feed_id.to_string(), desc, Utc::now().to_rfc3339()],
            )?;
        }
        if let Some(site) = site_url {
            conn.execute(
                "UPDATE feeds SET site_url = ?2, updated_at = ?3 WHERE id = ?1",
                params![feed_id.to_string(), site, Utc::now().to_rfc3339()],
            )?;
        }
        if let Some(icon) = icon_url {
            conn.execute(
                "UPDATE feeds SET icon_url = ?2, updated_at = ?3 WHERE id = ?1",
                params![feed_id.to_string(), icon, Utc::now().to_rfc3339()],
            )?;
        }
        Ok(())
    }

    /// 删除订阅源（级联删除文章）
    pub fn delete_feed(&self, feed_id: &Uuid) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "DELETE FROM feeds WHERE id = ?1",
            params![feed_id.to_string()],
        )?;
        Ok(())
    }

    // ─────────────────────────── Article CRUD ───────────────────────────

    /// 插入文章（如果 guid 已存在则跳过）
    pub fn insert_article_if_new(&self, article: &Article) -> Result<bool> {
        let conn = self.conn.lock().unwrap();

        // 先检查是否已存在（通过 feed_id + guid 去重）
        if let Some(ref guid) = article.guid {
            let exists: bool = conn.query_row(
                "SELECT EXISTS(SELECT 1 FROM articles WHERE feed_id = ?1 AND guid = ?2)",
                params![article.feed_id.to_string(), guid],
                |row| row.get(0),
            )?;
            if exists {
                return Ok(false); // 已存在，跳过
            }
        }

        conn.execute(
            "INSERT INTO articles (id, feed_id, guid, title, url, author, content_html,
             content_text, summary, thumbnail_url, published_at, is_read, is_starred,
             ai_summary, ai_tags, created_at, updated_at, enclosure_url, content_length)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19)",
            params![
                article.id.to_string(),
                article.feed_id.to_string(),
                article.guid,
                article.title,
                article.url,
                article.author,
                article.content_html,
                article.content_text,
                article.summary,
                article.thumbnail_url,
                article.published_at.map(|t| t.to_rfc3339()),
                article.is_read as i32,
                article.is_starred as i32,
                article.ai_summary,
                article.ai_tags,
                article.created_at.to_rfc3339(),
                article.updated_at.to_rfc3339(),
                article.enclosure_url,
                article.content_length,
            ],
        )?;
        Ok(true) // 新插入
    }

    /// 获取某个 Feed 的文章列表
    pub fn get_articles_by_feed(
        &self,
        feed_id: &Uuid,
        limit: i64,
        offset: i64,
    ) -> Result<Vec<Article>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, feed_id, guid, title, url, author, content_html, content_text,
                    summary, thumbnail_url, published_at, is_read, is_starred,
                    ai_summary, ai_tags, created_at, updated_at, enclosure_url, content_length
             FROM articles WHERE feed_id = ?1
             ORDER BY published_at DESC NULLS LAST, created_at DESC
             LIMIT ?2 OFFSET ?3",
        )?;

        let articles = stmt
            .query_map(params![feed_id.to_string(), limit, offset], |row| {
                Self::row_to_article(row)
            })?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(articles)
    }

    /// 获取所有未读文章
    pub fn get_unread_articles(&self, limit: i64, offset: i64) -> Result<Vec<Article>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, feed_id, guid, title, url, author, content_html, content_text,
                    summary, thumbnail_url, published_at, is_read, is_starred,
                    ai_summary, ai_tags, created_at, updated_at, enclosure_url, content_length
             FROM articles WHERE is_read = 0
             ORDER BY published_at DESC NULLS LAST, created_at DESC
             LIMIT ?1 OFFSET ?2",
        )?;

        let articles = stmt
            .query_map(params![limit, offset], |row| Self::row_to_article(row))?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(articles)
    }

    /// 获取所有收藏文章
    pub fn get_starred_articles(&self, limit: i64, offset: i64) -> Result<Vec<Article>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, feed_id, guid, title, url, author, content_html, content_text,
                    summary, thumbnail_url, published_at, is_read, is_starred,
                    ai_summary, ai_tags, created_at, updated_at, enclosure_url, content_length
             FROM articles WHERE is_starred = 1
             ORDER BY published_at DESC NULLS LAST, created_at DESC
             LIMIT ?1 OFFSET ?2",
        )?;

        let articles = stmt
            .query_map(params![limit, offset], |row| Self::row_to_article(row))?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(articles)
    }

    /// 搜索文章（支持普通文本和正则表达式）
    pub fn search_articles(&self, query: &str, use_regex: bool, limit: i64, offset: i64) -> Result<Vec<Article>> {
        if use_regex {
            // 正则模式：取出文章后在 Rust 侧过滤
            let re = regex::Regex::new(query)
                .map_err(|e| anyhow::anyhow!("Invalid regex: {}", e))?;

            let conn = self.conn.lock().unwrap();
            let mut stmt = conn.prepare(
                "SELECT id, feed_id, guid, title, url, author, content_html, content_text,
                        summary, thumbnail_url, published_at, is_read, is_starred,
                        ai_summary, ai_tags, created_at, updated_at, enclosure_url, content_length
                 FROM articles
                 ORDER BY published_at DESC NULLS LAST, created_at DESC",
            )?;

            let all: Vec<Article> = stmt
                .query_map([], |row| Self::row_to_article(row))?
                .collect::<Result<Vec<_>, _>>()?;

            let filtered: Vec<Article> = all
                .into_iter()
                .filter(|a| {
                    re.is_match(&a.title)
                        || a.content_text.as_deref().map_or(false, |t| re.is_match(t))
                        || a.summary.as_deref().map_or(false, |t| re.is_match(t))
                })
                .skip(offset as usize)
                .take(limit as usize)
                .collect();

            Ok(filtered)
        } else {
            // 普通模式：SQL LIKE
            let pattern = format!("%{}%", query);
            let conn = self.conn.lock().unwrap();
            let mut stmt = conn.prepare(
                "SELECT id, feed_id, guid, title, url, author, content_html, content_text,
                        summary, thumbnail_url, published_at, is_read, is_starred,
                        ai_summary, ai_tags, created_at, updated_at, enclosure_url, content_length
                 FROM articles
                 WHERE title LIKE ?1 OR content_text LIKE ?1 OR summary LIKE ?1
                 ORDER BY published_at DESC NULLS LAST, created_at DESC
                 LIMIT ?2 OFFSET ?3",
            )?;

            let articles = stmt
                .query_map(params![pattern, limit, offset], |row| Self::row_to_article(row))?
                .collect::<Result<Vec<_>, _>>()?;

            Ok(articles)
        }
    }

    /// 获取所有文章（时间线视图）
    pub fn get_all_articles(&self, limit: i64, offset: i64) -> Result<Vec<Article>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT id, feed_id, guid, title, url, author, content_html, content_text,
                    summary, thumbnail_url, published_at, is_read, is_starred,
                    ai_summary, ai_tags, created_at, updated_at, enclosure_url, content_length
             FROM articles
             ORDER BY published_at DESC NULLS LAST, created_at DESC
             LIMIT ?1 OFFSET ?2",
        )?;

        let articles = stmt
            .query_map(params![limit, offset], |row| Self::row_to_article(row))?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(articles)
    }

    /// 标记文章已读/未读
    pub fn set_article_read(&self, article_id: &Uuid, is_read: bool) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE articles SET is_read = ?2, updated_at = ?3 WHERE id = ?1",
            params![
                article_id.to_string(),
                is_read as i32,
                Utc::now().to_rfc3339()
            ],
        )?;
        Ok(())
    }

    /// 标记文章收藏/取消收藏
    pub fn set_article_starred(&self, article_id: &Uuid, is_starred: bool) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE articles SET is_starred = ?2, updated_at = ?3 WHERE id = ?1",
            params![
                article_id.to_string(),
                is_starred as i32,
                Utc::now().to_rfc3339()
            ],
        )?;
        Ok(())
    }

    /// 标记某个 Feed 的所有文章为已读
    pub fn mark_feed_all_read(&self, feed_id: &Uuid) -> Result<u64> {
        let conn = self.conn.lock().unwrap();
        let count = conn.execute(
            "UPDATE articles SET is_read = 1, updated_at = ?2 WHERE feed_id = ?1 AND is_read = 0",
            params![feed_id.to_string(), Utc::now().to_rfc3339()],
        )?;
        Ok(count as u64)
    }

    /// 获取统计信息
    pub fn get_stats(&self) -> Result<(i64, i64, i64)> {
        let conn = self.conn.lock().unwrap();
        let feed_count: i64 =
            conn.query_row("SELECT COUNT(*) FROM feeds", [], |row| row.get(0))?;
        let article_count: i64 =
            conn.query_row("SELECT COUNT(*) FROM articles", [], |row| row.get(0))?;
        let unread_count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM articles WHERE is_read = 0",
            [],
            |row| row.get(0),
        )?;
        Ok((feed_count, article_count, unread_count))
    }

    // ─────────────────────────── Helper ───────────────────────────

    fn row_to_article(row: &rusqlite::Row) -> rusqlite::Result<Article> {
        Ok(Article {
            id: Uuid::parse_str(&row.get::<_, String>(0)?).unwrap(),
            feed_id: Uuid::parse_str(&row.get::<_, String>(1)?).unwrap(),
            guid: row.get(2)?,
            title: row.get(3)?,
            url: row.get(4)?,
            author: row.get(5)?,
            content_html: row.get(6)?,
            content_text: row.get(7)?,
            summary: row.get(8)?,
            thumbnail_url: row.get(9)?,
            published_at: row
                .get::<_, Option<String>>(10)?
                .and_then(|s| chrono::DateTime::parse_from_rfc3339(&s).ok())
                .map(|dt| dt.with_timezone(&chrono::Utc)),
            is_read: row.get::<_, i32>(11)? != 0,
            is_starred: row.get::<_, i32>(12)? != 0,
            ai_summary: row.get(13)?,
            ai_tags: row.get(14)?,
            created_at: chrono::DateTime::parse_from_rfc3339(&row.get::<_, String>(15)?)
                .unwrap()
                .with_timezone(&chrono::Utc),
            updated_at: chrono::DateTime::parse_from_rfc3339(&row.get::<_, String>(16)?)
                .unwrap()
                .with_timezone(&chrono::Utc),
            enclosure_url: row.get(17)?,
            content_length: row.get::<_, Option<i64>>(18)?.unwrap_or(0),
        })
    }

    // ─────────────────────────── Folder CRUD ───────────────────────────

    /// 创建文件夹
    pub fn insert_folder(&self, folder: &Folder) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT INTO folders (id, name, parent_id, sort_order, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                folder.id.to_string(),
                folder.name,
                folder.parent_id.map(|id| id.to_string()),
                folder.sort_order,
                folder.created_at.to_rfc3339(),
            ],
        )?;
        Ok(())
    }

    /// 获取所有文件夹（含 feed 数量）
    pub fn get_all_folders(&self) -> Result<Vec<FolderWithCount>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT f.id, f.name, f.parent_id, f.sort_order, f.created_at,
                    COUNT(ff.feed_id) as feed_count
             FROM folders f
             LEFT JOIN feed_folders ff ON f.id = ff.folder_id
             GROUP BY f.id
             ORDER BY f.sort_order, f.name",
        )?;

        let folders = stmt
            .query_map([], |row| {
                Ok(FolderWithCount {
                    id: Uuid::parse_str(&row.get::<_, String>(0)?).unwrap(),
                    name: row.get(1)?,
                    parent_id: row
                        .get::<_, Option<String>>(2)?
                        .and_then(|s| Uuid::parse_str(&s).ok()),
                    sort_order: row.get(3)?,
                    created_at: chrono::DateTime::parse_from_rfc3339(
                        &row.get::<_, String>(4)?,
                    )
                    .unwrap()
                    .with_timezone(&Utc),
                    feed_count: row.get(5)?,
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(folders)
    }

    /// 更新文件夹名称和排序
    pub fn update_folder(&self, id: &Uuid, name: &str, sort_order: i32) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE folders SET name = ?2, sort_order = ?3 WHERE id = ?1",
            params![id.to_string(), name, sort_order],
        )?;
        Ok(())
    }

    /// 删除文件夹
    pub fn delete_folder(&self, id: &Uuid) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "DELETE FROM folders WHERE id = ?1",
            params![id.to_string()],
        )?;
        Ok(())
    }

    /// 将 Feed 加入文件夹
    pub fn add_feed_to_folder(&self, feed_id: &Uuid, folder_id: &Uuid) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT OR IGNORE INTO feed_folders (feed_id, folder_id) VALUES (?1, ?2)",
            params![feed_id.to_string(), folder_id.to_string()],
        )?;
        Ok(())
    }

    /// 将 Feed 从文件夹移除
    pub fn remove_feed_from_folder(&self, feed_id: &Uuid, folder_id: &Uuid) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "DELETE FROM feed_folders WHERE feed_id = ?1 AND folder_id = ?2",
            params![feed_id.to_string(), folder_id.to_string()],
        )?;
        Ok(())
    }

    /// 获取文件夹中的所有 Feed
    pub fn get_feeds_by_folder(&self, folder_id: &Uuid) -> Result<Vec<Feed>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT f.id, f.title, f.url, f.site_url, f.description, f.icon_url, f.language,
                    f.etag, f.last_modified, f.last_fetched_at, f.fetch_interval_secs,
                    f.error_count, f.last_error, f.created_at, f.updated_at
             FROM feeds f
             INNER JOIN feed_folders ff ON f.id = ff.feed_id
             WHERE ff.folder_id = ?1
             ORDER BY f.title",
        )?;

        let feeds = stmt
            .query_map(params![folder_id.to_string()], |row| {
                Ok(Feed {
                    id: Uuid::parse_str(&row.get::<_, String>(0)?).unwrap(),
                    title: row.get(1)?,
                    url: row.get(2)?,
                    site_url: row.get(3)?,
                    description: row.get(4)?,
                    icon_url: row.get(5)?,
                    language: row.get(6)?,
                    etag: row.get(7)?,
                    last_modified: row.get(8)?,
                    last_fetched_at: row
                        .get::<_, Option<String>>(9)?
                        .and_then(|s| chrono::DateTime::parse_from_rfc3339(&s).ok())
                        .map(|dt| dt.with_timezone(&Utc)),
                    fetch_interval_secs: row.get(10)?,
                    error_count: row.get(11)?,
                    last_error: row.get(12)?,
                    created_at: chrono::DateTime::parse_from_rfc3339(
                        &row.get::<_, String>(13)?,
                    )
                    .unwrap()
                    .with_timezone(&Utc),
                    updated_at: chrono::DateTime::parse_from_rfc3339(
                        &row.get::<_, String>(14)?,
                    )
                    .unwrap()
                    .with_timezone(&Utc),
                })
            })?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(feeds)
    }

    /// 获取所有 feed-folder 关联（用于 OPML 导出）
    pub fn get_feed_folder_map(&self) -> Result<Vec<(String, String)>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare("SELECT feed_id, folder_id FROM feed_folders")?;

        let map = stmt
            .query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })?
            .collect::<Result<Vec<_>, _>>()?;

        Ok(map)
    }

    // ─────────────────────────── Article by ID ───────────────────────────

    /// 根据 ID 获取文章
    pub fn get_article_by_id(&self, id: &Uuid) -> Result<Option<Article>> {
        let conn = self.conn.lock().unwrap();
        let article = conn
            .query_row(
                "SELECT id, feed_id, guid, title, url, author, content_html, content_text,
                        summary, thumbnail_url, published_at, is_read, is_starred,
                        ai_summary, ai_tags, created_at, updated_at, enclosure_url, content_length
                 FROM articles WHERE id = ?1",
                params![id.to_string()],
                |row| Self::row_to_article(row),
            )
            .optional()?;
        Ok(article)
    }

    /// 更新文章 AI 摘要
    pub fn update_article_ai_summary(&self, id: &Uuid, summary: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "UPDATE articles SET ai_summary = ?2, updated_at = ?3 WHERE id = ?1",
            params![id.to_string(), summary, Utc::now().to_rfc3339()],
        )?;
        Ok(())
    }

    // ─────────────────────────── Settings ───────────────────────────

    /// 获取单个设置
    pub fn get_setting(&self, key: &str) -> Result<Option<String>> {
        let conn = self.conn.lock().unwrap();
        let value = conn
            .query_row(
                "SELECT value FROM settings WHERE key = ?1",
                params![key],
                |row| row.get::<_, String>(0),
            )
            .optional()?;
        Ok(value)
    }

    /// 设置单个值
    pub fn set_setting(&self, key: &str, value: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT OR REPLACE INTO settings (key, value) VALUES (?1, ?2)",
            params![key, value],
        )?;
        Ok(())
    }

    /// 读取应用设置
    pub fn get_app_settings(&self) -> Result<crate::models::AppSettings> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare("SELECT key, value FROM settings")?;
        let mut map = std::collections::HashMap::new();
        let rows = stmt.query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })?;
        for row in rows {
            let (k, v) = row?;
            map.insert(k, v);
        }
        drop(stmt);
        drop(conn);

        let d = crate::models::AppSettings::default();
        Ok(crate::models::AppSettings {
            ai_enabled: map.get("ai_enabled").map(|v| v == "true").unwrap_or(d.ai_enabled),
            ai_provider: map.get("ai_provider").cloned().unwrap_or(d.ai_provider),
            ai_api_key: map.get("ai_api_key").cloned().unwrap_or(d.ai_api_key),
            ai_api_url: map.get("ai_api_url").cloned().unwrap_or(d.ai_api_url),
            ai_model: map.get("ai_model").cloned().unwrap_or(d.ai_model),
            ai_summary_prompt: map.get("ai_summary_prompt").cloned().unwrap_or(d.ai_summary_prompt),
            bangumi_token: map.get("bangumi_token").cloned().unwrap_or(d.bangumi_token),
            webhook_enabled: map.get("webhook_enabled").map(|v| v == "true").unwrap_or(d.webhook_enabled),
            webhook_url: map.get("webhook_url").cloned().unwrap_or(d.webhook_url),
            webhook_template: map.get("webhook_template").cloned().unwrap_or(d.webhook_template),
        })
    }

    /// 保存应用设置
    pub fn save_app_settings(&self, s: &crate::models::AppSettings) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let pairs = [
            ("ai_enabled", s.ai_enabled.to_string()),
            ("ai_provider", s.ai_provider.clone()),
            ("ai_api_key", s.ai_api_key.clone()),
            ("ai_api_url", s.ai_api_url.clone()),
            ("ai_model", s.ai_model.clone()),
            ("ai_summary_prompt", s.ai_summary_prompt.clone()),
            ("bangumi_token", s.bangumi_token.clone()),
            ("webhook_enabled", s.webhook_enabled.to_string()),
            ("webhook_url", s.webhook_url.clone()),
            ("webhook_template", s.webhook_template.clone()),
        ];
        for (k, v) in &pairs {
            conn.execute(
                "INSERT OR REPLACE INTO settings (key, value) VALUES (?1, ?2)",
                params![k, v],
            )?;
        }
        Ok(())
    }

    // ─────────────────────────── Cover Cache (Bangumi) ───────────────────────────

    /// 查询封面缓存
    pub fn get_cached_cover(&self, query: &str) -> Result<Option<(String, String, String)>> {
        let conn = self.conn.lock().unwrap();
        let result = conn
            .query_row(
                "SELECT cover_url, bgm_id, bgm_name FROM cover_cache WHERE query = ?1",
                params![query],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, String>(2)?,
                    ))
                },
            )
            .optional()?;
        Ok(result)
    }

    /// 缓存封面
    pub fn set_cached_cover(
        &self,
        query: &str,
        cover_url: &str,
        bgm_id: &str,
        bgm_name: &str,
    ) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT OR REPLACE INTO cover_cache (query, cover_url, bgm_id, bgm_name, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![query, cover_url, bgm_id, bgm_name, Utc::now().to_rfc3339()],
        )?;
        Ok(())
    }

    // ─────────────────────────── Cache Management ───────────────────────────

    /// 获取缓存统计信息
    pub fn get_cache_stats(&self, db_path: &str) -> Result<CacheStats> {
        let conn = self.conn.lock().unwrap();
        let article_count: i64 =
            conn.query_row("SELECT COUNT(*) FROM articles", [], |row| row.get(0))?;
        let feed_count: i64 =
            conn.query_row("SELECT COUNT(*) FROM feeds", [], |row| row.get(0))?;
        let cover_cache_count: i64 =
            conn.query_row("SELECT COUNT(*) FROM cover_cache", [], |row| row.get(0))?;
        let oldest_article: Option<String> = conn
            .query_row(
                "SELECT MIN(published_at) FROM articles WHERE published_at IS NOT NULL",
                [],
                |row| row.get(0),
            )
            .optional()?
            .flatten();

        // Get DB file size
        let db_size = std::fs::metadata(db_path)
            .map(|m| m.len())
            .unwrap_or(0);

        Ok(CacheStats {
            db_size_bytes: db_size,
            article_count,
            feed_count,
            cover_cache_count,
            oldest_article,
        })
    }

    /// 清理旧已读文章的 content_html（保留元数据），清空 cover_cache
    pub fn clear_cache(&self, days_old: i64) -> Result<CacheClearResult> {
        let conn = self.conn.lock().unwrap();
        let cutoff = (Utc::now() - chrono::Duration::days(days_old)).to_rfc3339();

        // Clear content_html for old, read, non-starred articles
        let articles_cleaned = conn.execute(
            "UPDATE articles SET content_html = NULL, content_text = NULL, updated_at = ?1
             WHERE is_read = 1 AND is_starred = 0 AND created_at < ?2
             AND (content_html IS NOT NULL OR content_text IS NOT NULL)",
            params![Utc::now().to_rfc3339(), cutoff],
        )? as u64;

        // Clear cover_cache
        let cover_cleared = conn.execute("DELETE FROM cover_cache", [])? as u64;

        Ok(CacheClearResult {
            articles_cleaned,
            cover_cache_cleared: cover_cleared,
        })
    }

    /// 获取数据库文件路径（通过 PRAGMA database_list）
    pub fn get_db_path(&self) -> Result<String> {
        let conn = self.conn.lock().unwrap();
        let path: String = conn.query_row(
            "PRAGMA database_list",
            [],
            |row| row.get(2),
        )?;
        Ok(path)
    }

    // ─────────────────────────── Download History ───────────────────────────

    /// 检查文章是否已下载
    pub fn is_downloaded(&self, article_id: &str) -> Result<bool> {
        let conn = self.conn.lock().unwrap();
        let exists: bool = conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM download_history WHERE article_id = ?1 AND status = 'sent')",
            params![article_id],
            |row| row.get(0),
        )?;
        Ok(exists)
    }

    /// 记录下载历史
    pub fn mark_downloaded(&self, article_id: &str, torrent_url: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT OR REPLACE INTO download_history (article_id, torrent_url, downloaded_at, status) VALUES (?1, ?2, ?3, 'sent')",
            params![article_id, torrent_url, Utc::now().to_rfc3339()],
        )?;
        Ok(())
    }

    /// 获取所有已下载文章 ID 集合
    pub fn get_downloaded_article_ids(&self) -> Result<std::collections::HashSet<String>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare("SELECT article_id FROM download_history WHERE status = 'sent'")?;
        let ids = stmt.query_map([], |row| row.get::<_, String>(0))?
            .filter_map(|r| r.ok())
            .collect();
        Ok(ids)
    }

    /// 获取下载历史（按时间倒序）
    pub fn get_download_history(&self, limit: i64) -> Result<Vec<crate::models::DownloadHistory>> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare(
            "SELECT article_id, torrent_url, downloaded_at, status FROM download_history ORDER BY downloaded_at DESC LIMIT ?1"
        )?;
        let items = stmt.query_map(params![limit], |row| {
            Ok(crate::models::DownloadHistory {
                article_id: row.get(0)?,
                torrent_url: row.get(1)?,
                downloaded_at: chrono::DateTime::parse_from_rfc3339(&row.get::<_, String>(2)?)
                    .unwrap().with_timezone(&Utc),
                status: row.get(3)?,
            })
        })?.collect::<Result<Vec<_>, _>>()?;
        Ok(items)
    }

    // ─────────────────────────── Download Config ───────────────────────────

    /// 读取下载配置（存储在 settings 表，dl_ 前缀）
    pub fn get_download_config(&self) -> Result<crate::models::DownloadConfig> {
        let conn = self.conn.lock().unwrap();
        let mut stmt = conn.prepare("SELECT key, value FROM settings WHERE key LIKE 'dl_%'")?;
        let mut map = std::collections::HashMap::new();
        let rows = stmt.query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
        })?;
        for row in rows { let (k, v) = row?; map.insert(k, v); }
        let d = crate::models::DownloadConfig::default();
        Ok(crate::models::DownloadConfig {
            client_type: map.get("dl_type").cloned().unwrap_or(d.client_type),
            host: map.get("dl_host").cloned().unwrap_or(d.host),
            username: map.get("dl_username").cloned().unwrap_or(d.username),
            password: map.get("dl_password").cloned().unwrap_or(d.password),
            save_path: map.get("dl_save_path").cloned().unwrap_or(d.save_path),
            auto_download: map.get("dl_auto").map(|v| v == "true").unwrap_or(d.auto_download),
        })
    }

    /// 保存下载配置
    pub fn save_download_config(&self, cfg: &crate::models::DownloadConfig) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        let pairs = [
            ("dl_type", cfg.client_type.as_str()),
            ("dl_host", cfg.host.as_str()),
            ("dl_username", cfg.username.as_str()),
            ("dl_password", cfg.password.as_str()),
            ("dl_save_path", cfg.save_path.as_str()),
            ("dl_auto", if cfg.auto_download { "true" } else { "false" }),
        ];
        for (k, v) in &pairs {
            conn.execute("INSERT OR REPLACE INTO settings (key, value) VALUES (?1, ?2)", params![k, v])?;
        }
        Ok(())
    }

    // ─────────────────────────── Extended Cover Cache ───────────────────────────

    /// 查询完整封面缓存（含 Bangumi 详情）
    pub fn get_cached_cover_full(&self, query: &str) -> Result<Option<(String, String, String, Option<String>, Option<i32>, Option<String>, Option<f64>)>> {
        let conn = self.conn.lock().unwrap();
        let result = conn.query_row(
            "SELECT cover_url, COALESCE(bgm_id,''), COALESCE(bgm_name,''), summary, eps_count, air_date, rating FROM cover_cache WHERE query = ?1",
            params![query],
            |row| Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, Option<String>>(3)?,
                row.get::<_, Option<i32>>(4)?,
                row.get::<_, Option<String>>(5)?,
                row.get::<_, Option<f64>>(6)?,
            )),
        ).optional()?;
        Ok(result)
    }

    /// 缓存完整封面数据（含 Bangumi 详情）
    pub fn set_cached_cover_full(&self, query: &str, cover_url: &str, bgm_id: &str, bgm_name: &str,
        summary: Option<&str>, eps_count: Option<i32>, air_date: Option<&str>, rating: Option<f64>) -> Result<()> {
        let conn = self.conn.lock().unwrap();
        conn.execute(
            "INSERT OR REPLACE INTO cover_cache (query, cover_url, bgm_id, bgm_name, summary, eps_count, air_date, rating, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            params![query, cover_url, bgm_id, bgm_name, summary, eps_count, air_date, rating, Utc::now().to_rfc3339()],
        )?;
        Ok(())
    }
}
