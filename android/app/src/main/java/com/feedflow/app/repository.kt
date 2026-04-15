package com.feedflow.app

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Local-first repository. All RSS fetching and parsing happens on device.
 * Data is persisted in Room. No server needed.
 */
class FeedRepository(context: Context) {

    private val db = AppDatabase.get(context)
    private val feedDao = db.feedDao()
    private val articleDao = db.articleDao()
    private val prefs = context.getSharedPreferences("feedflow_prefs", Context.MODE_PRIVATE)

    init {
        RssParser.init(context)
        AppLogger.init(context)
        AppLogger.i("FeedRepository initialized")
    }

    // ---- Theme preference ---------------------------------------------------

    fun getThemeMode(): String = prefs.getString("theme_mode", "system") ?: "system"

    fun setThemeMode(mode: String) {
        prefs.edit().putString("theme_mode", mode).apply()
    }

    // ---- Log settings -------------------------------------------------------

    fun getLogDir(): String = AppLogger.getLogDir()

    fun setLogDir(path: String) {
        prefs.edit().putString("log_dir", path).apply()
        AppLogger.setLogDir(path)
    }

    fun isLogEnabled(): Boolean = AppLogger.isEnabled()

    fun setLogEnabled(on: Boolean) {
        prefs.edit().putBoolean("log_enabled", on).apply()
        AppLogger.setEnabled(on)
    }

    val feedsFlow: Flow<List<Feed>> = feedDao.allFeeds().map { list ->
        list.map { it.toModel() }
    }

    suspend fun addFeed(url: String): Result<Feed> = withContext(Dispatchers.IO) {
        AppLogger.i("Adding feed: $url")
        try {
            val parsed = RssParser.fetch(url)
            val feedId = RssParser.md5(url)
            val autoTags = detectFeedTags(url, parsed.title)
            val entity = FeedEntity(
                id = feedId, title = parsed.title.ifBlank { url },
                url = url, siteUrl = parsed.siteUrl, description = parsed.description,
                tags = autoTags.ifBlank { null },
            )
            feedDao.upsertFeed(entity)
            articleDao.insertAll(parsed.articles.map { it.toEntity(feedId) })
            AppLogger.i("Feed added: ${parsed.title} (${parsed.articles.size} articles, tags=$autoTags)")
            Result.success(entity.toModel())
        } catch (e: Exception) {
            AppLogger.e("Failed to add feed: $url", e)
            Result.failure(e)
        }
    }

    suspend fun refreshFeed(feedId: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val feed = feedDao.getFeed(feedId) ?: return@withContext Result.failure(Exception("不存在"))
            val parsed = RssParser.fetch(feed.url)
            val arts = parsed.articles.map { it.toEntity(feedId) }
            articleDao.insertAll(arts)
            enrichMikanThumbnails(arts, feed.url)
            feedDao.upsertFeed(feed.copy(title = parsed.title.ifBlank { feed.title }, lastError = null))
            Result.success(arts.size)
        } catch (e: Exception) {
            feedDao.getFeed(feedId)?.let { feedDao.upsertFeed(it.copy(lastError = e.message)) }
            Result.failure(e)
        }
    }

    suspend fun refreshAll(): Int = coroutineScope {
        val feeds = feedDao.allFeedsList()
        AppLogger.i("Refreshing all feeds (${feeds.size} total)")
        val semaphore = Semaphore(4)
        val results = feeds.map { feed ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    refreshFeed(feed.id).getOrDefault(0)
                }
            }
        }
        val total = results.awaitAll().sum()
        AppLogger.i("Refresh complete: $total new articles")
        total
    }

    suspend fun deleteFeed(id: String) = feedDao.deleteFeedById(id)

    private suspend fun enrichWithFeedInfo(entities: List<ArticleEntity>): List<Article> {
        val feedMap = feedDao.allFeedsList().associateBy { it.id }
        return entities.map { entity ->
            val feed = feedMap[entity.feedId]
            entity.toModel().copy(feedTitle = feed?.title, feedIcon = feed?.iconUrl)
        }
    }

    suspend fun getArticles(limit: Int = 20, offset: Int = 0) =
        enrichWithFeedInfo(articleDao.allArticles(limit, offset))

    suspend fun getUnreadArticles(limit: Int = 20, offset: Int = 0) =
        enrichWithFeedInfo(articleDao.unreadArticles(limit, offset))

    suspend fun getStarredArticles(limit: Int = 20, offset: Int = 0) =
        enrichWithFeedInfo(articleDao.starredArticles(limit, offset))

    suspend fun getFeedArticles(feedId: String, limit: Int = 20, offset: Int = 0) =
        enrichWithFeedInfo(articleDao.articlesByFeed(feedId, limit, offset))

    suspend fun searchArticles(query: String, limit: Int = 20, offset: Int = 0) =
        enrichWithFeedInfo(articleDao.searchArticles(query, limit, offset))

    suspend fun markArticleRead(id: String) = articleDao.markRead(id)

    suspend fun toggleArticleStar(id: String): Article? {
        articleDao.toggleStar(id)
        return articleDao.getArticle(id)?.toModel()
    }

    suspend fun markFeedAllRead(feedId: String) = articleDao.markAllRead(feedId)

    suspend fun getStats() = Stats(
        feeds = feedDao.feedCount(),
        articles = articleDao.articleCount(),
        unread = articleDao.unreadCount(),
    )

    suspend fun getArticle(id: String): Article? =
        articleDao.getArticle(id)?.toModel()

    suspend fun getFeeds(): List<Feed> = feedDao.allFeedsList().map { it.toModel() }

    suspend fun updateFeedTags(feedId: String, tags: List<String>) {
        feedDao.updateTags(feedId, tags.joinToString(",").ifBlank { null })
    }

    suspend fun getAllTags(): List<String> {
        return feedDao.allTags().flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    // ---- Bangumi poster support (mikan feeds) --------------------------------

    private val bangumiCache = ConcurrentHashMap<String, String?>()
    private val MIKAN_TITLE = Regex("""\[.*?]\s*(.+?)\s*(?:/.*?)?\s*-\s*\d+""")

    suspend fun enrichMikanThumbnails(articles: List<ArticleEntity>, feedUrl: String) {
        if (!feedUrl.contains("mikan", ignoreCase = true)) return
        withContext(Dispatchers.IO) {
            articles.filter { it.thumbnailUrl == null }.forEach { article ->
                val animeName = extractAnimeName(article.title) ?: return@forEach
                val cover = bangumiCache.getOrPut(animeName) { fetchBangumiCover(animeName) }
                if (cover != null) {
                    articleDao.updateThumbnail(article.id, cover)
                }
            }
        }
    }

    private fun extractAnimeName(title: String): String? {
        val match = MIKAN_TITLE.find(title)
        if (match != null) return match.groupValues[1].trim()
        // Fallback: split by " - digit" and clean brackets
        val parts = title.split(Regex("""\s*-\s*\d+"""), 2)
        if (parts.isNotEmpty()) {
            val name = parts[0].replace(Regex("""^\[.*?]\s*"""), "").trim()
            if (name.length > 2) return name
        }
        return null
    }

    private fun fetchBangumiCover(name: String): String? {
        return try {
            val encoded = URLEncoder.encode(name, "UTF-8")
            val url = "https://api.bgm.tv/search/subject/$encoded?type=2&responseGroup=small&max_results=1"
            val request = Request.Builder().url(url)
                .header("User-Agent", "FeedFlow/1.0 (RSS Reader)")
                .build()
            val resp = RssParser.getClient().newCall(request).execute()
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string() ?: "{}")
                val list = json.optJSONArray("list")
                if (list != null && list.length() > 0) {
                    list.getJSONObject(0).optJSONObject("images")?.optString("large")
                } else null
            } else null
        } catch (_: Exception) { null }
    }
}

private fun ParsedArticle.toEntity(feedId: String) = ArticleEntity(
    id = RssParser.md5((url ?: title) + feedId),
    feedId = feedId, title = title, url = url, author = author,
    contentHtml = compressText(contentHtml), summary = summary,
    thumbnailUrl = thumbnailUrl, publishedAt = publishedAt,
)

private fun FeedEntity.toModel() = Feed(
    id = id, title = title, url = url, siteUrl = siteUrl,
    description = description, iconUrl = iconUrl, lastError = lastError, unreadCount = 0,
    tags = tags?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
)

private fun ArticleEntity.toModel() = Article(
    id = id, feedId = feedId, title = title, url = url, author = author,
    contentHtml = decompressText(contentHtml), summary = summary, thumbnailUrl = thumbnailUrl,
    publishedAt = if (publishedAt > 0) java.time.Instant.ofEpochMilli(publishedAt).toString() else null,
    isRead = isRead, isStarred = isStarred,
)

// GZip transparent compression for content_html (typically 5-10x ratio on HTML)
private const val GZ_PREFIX = "GZ:"

private fun compressText(text: String?): String? {
    if (text.isNullOrBlank() || text.length < 200) return text
    return try {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(bytes) }
        GZ_PREFIX + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    } catch (_: Exception) { text }
}

private fun decompressText(text: String?): String? {
    if (text == null || !text.startsWith(GZ_PREFIX)) return text
    return try {
        val compressed = Base64.decode(text.removePrefix(GZ_PREFIX), Base64.NO_WRAP)
        GZIPInputStream(ByteArrayInputStream(compressed)).bufferedReader(Charsets.UTF_8).readText()
    } catch (_: Exception) { text }
}

// Auto-detect feed type and assign tags based on URL/title patterns
private fun detectFeedTags(url: String, title: String): String {
    val tags = mutableListOf<String>()
    val lower = (url + " " + title).lowercase()
    when {
        lower.contains("mikan") || lower.contains("bangumi") || lower.contains("anime")
            || lower.contains("蜜柑") -> tags.add("番剧")
        lower.contains("manga") || lower.contains("漫画") || lower.contains("comic") -> tags.add("漫画")
        lower.contains("github") || lower.contains("dev") || lower.contains("hacker")
            || lower.contains("programming") || lower.contains("技术") -> tags.add("技术")
        lower.contains("ai") || lower.contains("llm") || lower.contains("机器学习")
            || lower.contains("openai") || lower.contains("anthropic") -> tags.add("AI")
        lower.contains("news") || lower.contains("新闻") || lower.contains("daily")
            || lower.contains("早报") || lower.contains("日报") -> tags.add("资讯")
        lower.contains("blog") || lower.contains("博客") -> tags.add("博客")
        lower.contains("youtube") || lower.contains("bilibili") || lower.contains("video")
            || lower.contains("视频") -> tags.add("视频")
        lower.contains("podcast") || lower.contains("播客") -> tags.add("播客")
    }
    return tags.joinToString(",")
}
