package com.feedflow.app

/**
 * UI-facing models. Decoupled from Room entities so screens don't depend on DB directly.
 */

data class Feed(
    val id: String,
    val title: String,
    val url: String,
    val siteUrl: String? = null,
    val description: String? = null,
    val iconUrl: String? = null,
    val lastError: String? = null,
    val unreadCount: Int = 0,
    val tags: List<String> = emptyList(),
)

data class Article(
    val id: String,
    val feedId: String,
    val title: String,
    val url: String? = null,
    val author: String? = null,
    val contentHtml: String? = null,
    val summary: String? = null,
    val thumbnailUrl: String? = null,
    val publishedAt: String? = null,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val feedTitle: String? = null,
    val feedIcon: String? = null,
    val isMikan: Boolean = false,
    val enclosureUrl: String? = null,
    val isDownloaded: Boolean = false,
    val contentLength: Long = 0L,
)

/**
 * Aggregated anime info — groups articles by anime name for the "番剧" view.
 */
data class AnimeInfo(
    val name: String,
    val coverUrl: String? = null,
    val bgmId: Int? = null,
    val bgmName: String? = null,
    val summary: String? = null,
    val epsCount: Int? = null,
    val airDate: String? = null,
    val rating: Float? = null,
    val episodes: List<AnimeEpisode> = emptyList(),
)

data class AnimeEpisode(
    val articleId: String,
    val episode: String?,
    val fansub: String?,
    val resolution: String?,
    val fileSize: String?,
    val publishedAt: String?,
    val enclosureUrl: String?,
    val contentLength: Long = 0L,
    val isDownloaded: Boolean = false,
)

data class Stats(
    val feeds: Long,
    val articles: Long,
    val unread: Long,
)

data class CacheInfo(
    val dbSizeBytes: Long,
    val contentSizeBytes: Long,
    val articleCount: Long,
    val feedCount: Long,
) {
    fun dbSizeFormatted(): String = formatBytes(dbSizeBytes)
    fun contentSizeFormatted(): String = formatBytes(contentSizeBytes)
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
