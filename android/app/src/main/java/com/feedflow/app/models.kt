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
)

data class Stats(
    val feeds: Long,
    val articles: Long,
    val unread: Long,
)
