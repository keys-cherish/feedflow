package com.feedflow.app

import android.content.Context
import android.util.Base64
import com.feedflow.app.download.*
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
    private val bangumiCacheDao = db.bangumiCacheDao()
    private val downloadHistoryDao = db.downloadHistoryDao()
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

    // ---- Download config --------------------------------------------------------

    fun getDownloadConfig(): DownloadConfig = DownloadConfig(
        type = prefs.getString("dl_type", "qbittorrent") ?: "qbittorrent",
        host = prefs.getString("dl_host", "") ?: "",
        username = prefs.getString("dl_username", "") ?: "",
        password = prefs.getString("dl_password", "") ?: "",
        savePath = prefs.getString("dl_save_path", "") ?: "",
        autoDownload = prefs.getBoolean("dl_auto", false),
    )

    fun saveDownloadConfig(cfg: DownloadConfig) {
        prefs.edit()
            .putString("dl_type", cfg.type)
            .putString("dl_host", cfg.host)
            .putString("dl_username", cfg.username)
            .putString("dl_password", cfg.password)
            .putString("dl_save_path", cfg.savePath)
            .putBoolean("dl_auto", cfg.autoDownload)
            .apply()
    }

    fun createDownloadClient(cfg: DownloadConfig = getDownloadConfig()): DownloadClient? {
        if (cfg.host.isBlank()) return null
        return when (cfg.type) {
            "qbittorrent" -> QBittorrentClient(cfg)
            "aria2" -> Aria2Client(cfg)
            "transmission" -> TransmissionClient(cfg)
            else -> null
        }
    }

    suspend fun testDownloadConnection(): Boolean {
        val client = createDownloadClient() ?: return false
        return withContext(Dispatchers.IO) { client.testConnection() }
    }

    suspend fun downloadArticle(articleId: String): Result<String> = withContext(Dispatchers.IO) {
        val article = articleDao.getArticle(articleId) ?: return@withContext Result.failure(Exception("文章不存在"))
        val url = article.enclosureUrl ?: return@withContext Result.failure(Exception("无种子链接"))
        val cfg = getDownloadConfig()
        val client = createDownloadClient(cfg) ?: return@withContext Result.failure(Exception("未配置下载客户端"))
        val result = client.addTorrent(url, cfg.savePath.ifBlank { null })
        if (result.isSuccess) {
            downloadHistoryDao.markDownloaded(
                DownloadHistoryEntity(articleId = articleId, torrentUrl = url)
            )
        }
        result
    }

    suspend fun isArticleDownloaded(articleId: String): Boolean =
        downloadHistoryDao.isDownloaded(articleId)

    suspend fun getDownloadedArticleIds(): Set<String> =
        downloadHistoryDao.allDownloadedArticleIds().toSet()

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
            // Auto-download new episodes from mikan feeds
            autoDownloadNewEpisodes(arts, feed.url)
            // Auto-tag feeds that have no tags yet
            val updatedTags = if (feed.tags.isNullOrBlank()) {
                val auto = detectFeedTags(feed.url, parsed.title.ifBlank { feed.title })
                auto.ifBlank { null }
            } else feed.tags
            // Use safe UPDATE (not REPLACE which triggers CASCADE delete on articles!)
            feedDao.updateFeedMeta(
                id = feedId,
                title = parsed.title.ifBlank { feed.title },
                lastError = null,
                tags = updatedTags,
            )
            Result.success(arts.size)
        } catch (e: Exception) {
            feedDao.getFeed(feedId)?.let {
                feedDao.updateFeedMeta(id = feedId, title = it.title, lastError = e.message, tags = it.tags)
            }
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
        val downloadedIds = downloadHistoryDao.allDownloadedArticleIds().toSet()
        return entities.map { entity ->
            val feed = feedMap[entity.feedId]
            val isMikan = feed?.url?.contains("mikan", ignoreCase = true) == true
                    || feed?.tags?.contains("番剧") == true
                    || (feed?.url != null && isAnimeFeed(feed.url))
            entity.toModel().copy(
                feedTitle = feed?.title,
                feedIcon = feed?.iconUrl,
                isMikan = isMikan,
                isDownloaded = entity.id in downloadedIds,
            )
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

    suspend fun getFeedIdsByTag(tag: String): List<String> = withContext(Dispatchers.IO) {
        feedDao.allFeedsList()
            .filter { it.tags?.split(",")?.map { t -> t.trim() }?.contains(tag) == true }
            .map { it.id }
    }

    suspend fun getArticlesByTag(tag: String, limit: Int = 20, offset: Int = 0): List<Article> {
        val feedIds = getFeedIdsByTag(tag)
        if (feedIds.isEmpty()) return emptyList()
        return enrichWithFeedInfo(articleDao.articlesByFeeds(feedIds, limit, offset))
    }

    // ---- Cache management ----------------------------------------------------

    suspend fun getCacheStats(): CacheInfo = withContext(Dispatchers.IO) {
        val articleCount = articleDao.articleCount()
        val feedCount = feedDao.feedCount()
        val contentSize = articleDao.totalContentSize() ?: 0L
        val dbFile = db.openHelper.writableDatabase.path?.let { java.io.File(it) }
        val dbSize = dbFile?.length() ?: 0L
        CacheInfo(dbSizeBytes = dbSize, contentSizeBytes = contentSize, articleCount = articleCount, feedCount = feedCount)
    }

    suspend fun clearOldCache(daysOld: Int = 30): Int = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - daysOld * 24 * 60 * 60 * 1000L
        val cleaned = articleDao.clearOldContent(cutoff)
        AppLogger.i("Cache cleared: $cleaned articles older than $daysOld days")
        cleaned
    }

    suspend fun clearAllArticles() = withContext(Dispatchers.IO) {
        articleDao.deleteAll()
        AppLogger.i("All articles deleted")
    }

    // ---- Auto-download for mikan feeds ----------------------------------------

    /**
     * Get aggregated anime list from mikan feeds, grouped by parsed anime name.
     * Each AnimeInfo contains Bangumi metadata + episode list.
     */
    suspend fun getAnimeList(): List<AnimeInfo> = withContext(Dispatchers.IO) {
        warmBangumiCache()
        val mikanFeedIds = feedDao.allFeedsList()
            .filter {
                it.url.contains("mikan", ignoreCase = true)
                        || it.tags?.contains("番剧") == true
                        || isAnimeFeed(it.url)
            }
            .map { it.id }
        if (mikanFeedIds.isEmpty()) return@withContext emptyList()

        val allArticles = mikanFeedIds.flatMap { articleDao.articlesByFeed(it, limit = 200) }
        val downloadedIds = downloadHistoryDao.allDownloadedArticleIds().toSet()

        // Group by parsed anime name
        val grouped = mutableMapOf<String, MutableList<Pair<ArticleEntity, ParsedAnimeTitle>>>()
        for (art in allArticles) {
            val parsed = parseMikanTitle(art.title)
            val key = parsed.title.lowercase().trim()
            if (key.isBlank()) continue
            grouped.getOrPut(key) { mutableListOf() }.add(art to parsed)
        }

        grouped.map { (_, entries) ->
            val firstName = entries.first().second.title
            val cache = bangumiCacheDao.getCover(firstName)

            val episodes = entries
                .sortedByDescending { it.first.publishedAt }
                .map { (art, parsed) ->
                    AnimeEpisode(
                        articleId = art.id,
                        episode = parsed.episode,
                        fansub = parsed.fansub,
                        resolution = parsed.resolution,
                        fileSize = parsed.fileSize,
                        publishedAt = if (art.publishedAt > 0) java.time.Instant.ofEpochMilli(art.publishedAt).toString() else null,
                        enclosureUrl = art.enclosureUrl,
                        contentLength = art.contentLength,
                        isDownloaded = art.id in downloadedIds,
                    )
                }

            AnimeInfo(
                name = cache?.bgmName ?: firstName,
                coverUrl = cache?.coverUrl,
                bgmId = cache?.bgmId,
                bgmName = cache?.bgmName,
                summary = cache?.summary,
                epsCount = cache?.epsCount,
                airDate = cache?.airDate,
                rating = cache?.rating,
                episodes = episodes,
            )
        }.sortedByDescending { it.episodes.firstOrNull()?.publishedAt }
    }

    /** Get single anime detail by name (used when navigating to detail page). */
    suspend fun getAnimeDetail(animeName: String): AnimeInfo? {
        val all = getAnimeList()
        return all.find { it.name.equals(animeName, ignoreCase = true) }
    }

    private suspend fun autoDownloadNewEpisodes(articles: List<ArticleEntity>, feedUrl: String) {
        val cfg = getDownloadConfig()
        if (!cfg.autoDownload) return
        // Support all anime RSS sources, not just mikan
        if (!isAnimeFeed(feedUrl)) return

        val client = createDownloadClient(cfg) ?: return
        val downloadedIds = downloadHistoryDao.allDownloadedArticleIds().toSet()

        val toDownload = articles.filter { art ->
            !art.enclosureUrl.isNullOrBlank() && art.id !in downloadedIds
        }
        if (toDownload.isEmpty()) return

        AppLogger.i("Auto-download: ${toDownload.size} new episodes from $feedUrl")
        for (art in toDownload) {
            try {
                val result = client.addTorrent(art.enclosureUrl!!, cfg.savePath.ifBlank { null })
                if (result.isSuccess) {
                    downloadHistoryDao.markDownloaded(
                        DownloadHistoryEntity(articleId = art.id, torrentUrl = art.enclosureUrl)
                    )
                    AppLogger.i("Auto-downloaded: ${art.title}")
                } else {
                    AppLogger.e("Auto-download failed: ${art.title} - ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                AppLogger.e("Auto-download error: ${art.title}", e)
            }
        }
    }

    // ---- Bangumi poster support (mikan feeds) --------------------------------

    private val bangumiMemCache = ConcurrentHashMap<String, String?>()
    private var bangumiCacheWarmed = false

    private suspend fun warmBangumiCache() {
        if (bangumiCacheWarmed) return
        withContext(Dispatchers.IO) {
            bangumiCacheDao.getAll().forEach { entity ->
                bangumiMemCache[entity.animeName] = entity.coverUrl
            }
            bangumiCacheWarmed = true
        }
    }

    suspend fun enrichMikanThumbnails(articles: List<ArticleEntity>, feedUrl: String) {
        // Enrich thumbnails for any anime feed, not just mikan
        if (!isAnimeFeed(feedUrl) && !feedUrl.contains("mikan", ignoreCase = true)) return
        warmBangumiCache()
        withContext(Dispatchers.IO) {
            articles.filter { it.thumbnailUrl == null }.forEach { article ->
                val animeName = extractAnimeName(article.title) ?: return@forEach
                val cover = bangumiMemCache.getOrPut(animeName) {
                    // Check Room first
                    val cached = bangumiCacheDao.getCover(animeName)
                    if (cached != null) {
                        cached.coverUrl
                    } else {
                        val entity = fetchBangumiCover(animeName)
                        // Persist to Room regardless (null cover means "looked up, not found")
                        bangumiCacheDao.upsertCover(
                            entity ?: BangumiCacheEntity(animeName = animeName, coverUrl = null)
                        )
                        entity?.coverUrl
                    }
                }
                if (cover != null) {
                    articleDao.updateThumbnail(article.id, cover)
                }
            }
        }
    }

    private fun extractAnimeName(title: String): String? {
        val parsed = parseMikanTitle(title)
        val name = parsed.title.trim()
        return if (name.length >= 2) name else null
    }

    private fun fetchBangumiCover(name: String): BangumiCacheEntity? {
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
                    val item = list.getJSONObject(0)
                    val coverUrl = item.optJSONObject("images")?.optString("large")
                    val bgmId = item.optInt("id", 0)
                    val bgmName = item.optString("name_cn").ifBlank { item.optString("name") }
                    val summary = item.optString("summary").ifBlank { null }?.take(500)
                    val epsCount = item.optInt("eps_count", 0).let { if (it > 0) it else null }
                    val airDate = item.optString("air_date").ifBlank { null }
                    val rating = item.optJSONObject("rating")?.optDouble("score", 0.0)?.toFloat()
                        ?.let { if (it > 0f) it else null }
                    BangumiCacheEntity(
                        animeName = name,
                        coverUrl = coverUrl,
                        bgmId = if (bgmId > 0) bgmId else null,
                        bgmName = bgmName.ifBlank { null },
                        summary = summary,
                        epsCount = epsCount,
                        airDate = airDate,
                        rating = rating,
                    )
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
    enclosureUrl = enclosureUrl, contentLength = contentLength,
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
    isRead = isRead, isStarred = isStarred, enclosureUrl = enclosureUrl,
    contentLength = contentLength,
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
        // All anime torrent RSS sources
        lower.contains("mikan") || lower.contains("bangumi") || lower.contains("anime")
            || lower.contains("蜜柑") || lower.contains("nyaa")
            || lower.contains("dmhy") || lower.contains("acg.rip")
            || lower.contains("bangumi.moe") -> tags.add("番剧")
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

/** Check if a feed URL is an anime torrent source (mikan, nyaa, dmhy, acg.rip, bangumi.moe). */
private fun isAnimeFeed(feedUrl: String): Boolean {
    val lower = feedUrl.lowercase()
    return lower.contains("mikan") || lower.contains("nyaa")
            || lower.contains("dmhy") || lower.contains("acg.rip")
            || lower.contains("bangumi.moe")
}
