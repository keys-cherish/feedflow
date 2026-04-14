package com.feedflow.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Generic API response wrapper -- the server wraps every response in this
// ---------------------------------------------------------------------------

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: String? = null,
)

// ---------------------------------------------------------------------------
// Core domain models
// ---------------------------------------------------------------------------

@Serializable
data class Feed(
    val id: String,
    val title: String,
    val url: String,
    @SerialName("site_url") val siteUrl: String? = null,
    val description: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    @SerialName("folder_id") val folderId: String? = null,
    @SerialName("error_count") val errorCount: Int = 0,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("unread_count") val unreadCount: Int = 0,
)

@Serializable
data class Article(
    val id: String,
    @SerialName("feed_id") val feedId: String,
    val title: String,
    val url: String? = null,
    val author: String? = null,
    @SerialName("content_html") val contentHtml: String? = null,
    val summary: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("is_starred") val isStarred: Boolean = false,
    @SerialName("ai_summary") val aiSummary: String? = null,
    @SerialName("feed_title") val feedTitle: String? = null,
    @SerialName("feed_icon") val feedIcon: String? = null,
)

@Serializable
data class Stats(
    val feeds: Long,
    val articles: Long,
    val unread: Long,
)

@Serializable
data class Folder(
    val id: String,
    val name: String,
    @SerialName("feed_count") val feedCount: Int = 0,
)

@Serializable
data class BangumiResult(
    @SerialName("cover_url") val coverUrl: String = "",
    @SerialName("bgm_id") val bgmId: String = "",
    @SerialName("bgm_name") val bgmName: String = "",
    @SerialName("rss_url") val rssUrl: String? = null,
)

// ---------------------------------------------------------------------------
// Request bodies
// ---------------------------------------------------------------------------

@Serializable
data class AddFeedRequest(
    val url: String,
    @SerialName("folder_id") val folderId: String? = null,
)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val token: String,
)

@Serializable
data class SettingsData(
    @SerialName("ai_enabled") val aiEnabled: Boolean = false,
    @SerialName("ai_provider") val aiProvider: String = "",
    @SerialName("ai_model") val aiModel: String = "",
)

// ---------------------------------------------------------------------------
// Convenience type aliases for common API response shapes
// ---------------------------------------------------------------------------

typealias ArticleListResponse = ApiResponse<List<Article>>
typealias FeedListResponse = ApiResponse<List<Feed>>
typealias FolderListResponse = ApiResponse<List<Folder>>
typealias StatsResponse = ApiResponse<Stats>
typealias BangumiSearchResponse = ApiResponse<List<BangumiResult>>
