package com.feedflow.app.ui
import com.feedflow.app.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// ---------------------------------------------------------------------------
// Smart title extraction — when title is just a date, use summary instead
// ---------------------------------------------------------------------------

private val DATE_PATTERN = Regex("""^\d{4}[-/\.]\d{1,2}[-/\.]\d{1,2}$""")
private val DATE_PREFIX = Regex("""^\d{4}[-/\.]\d{1,2}[-/\.]\d{1,2}\s*""")
private val STRIP_HTML = Regex("""<[^>]+>""")

fun getDisplayTitle(article: Article): String {
    val title = article.title.trim()
    // If title IS a date (e.g. "2026-04-15"), extract real title from summary
    if (title.isEmpty() || DATE_PATTERN.matches(title)) {
        val source = (article.summary ?: "")
            .replace(STRIP_HTML, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Strip leading dates and common prefixes
        val cleaned = source
            .replace(DATE_PREFIX, "")
            .replace(Regex("^视频版[：:：]?\\s*"), "")
            .trim()
        // Take first sentence as title
        val firstSentence = cleaned.split(Regex("[\\n。]"))
            .firstOrNull { it.trim().length > 5 }
            ?.trim()
        if (firstSentence != null && firstSentence.length > 5) {
            return if (firstSentence.length > 80) firstSentence.take(77) + "..." else firstSentence
        }
    }
    // If title starts with a date, strip the date prefix
    val stripped = title.replace(DATE_PREFIX, "").trim()
    if (stripped.length > 5) return stripped
    return title.ifEmpty { "（无标题）" }
}

/** Clean summary text: strip HTML, remove content that duplicates the title, remove date prefixes */
fun getCleanSummary(article: Article, displayTitle: String): String? {
    val raw = article.summary ?: return null
    var text = raw
        .replace(STRIP_HTML, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    // Remove leading dates
    text = text.replace(DATE_PREFIX, "").trim()
    // Remove the display title if it appears at the start of summary
    if (text.startsWith(displayTitle)) {
        text = text.removePrefix(displayTitle).trim()
    }
    // Remove original title if it appears
    val origTitle = article.title.trim()
    if (origTitle.isNotEmpty() && text.startsWith(origTitle)) {
        text = text.removePrefix(origTitle).trim()
    }
    // Strip leading punctuation
    text = text.trimStart('：', ':', '，', ',', '、', ' ')
    return text.ifBlank { null }
}

// ---------------------------------------------------------------------------
// Article card -- the primary content unit in the timeline & feed detail
// ---------------------------------------------------------------------------

/**
 * A chat-bubble-style card showing an RSS article in the timeline.
 *
 * Layout (top to bottom):
 *   feed icon + feed name + relative time
 *   article title (bold)
 *   summary snippet (2 lines max)
 *   optional thumbnail
 *   star toggle at bottom-right
 */
@Composable
fun ArticleCard(
    article: Article,
    onClick: () -> Unit,
    onStarToggle: () -> Unit,
    onDownload: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (article.isMikan) {
        AnimeArticleCard(article, onClick, onStarToggle, onDownload, modifier)
    } else {
        StandardArticleCard(article, onClick, onStarToggle, modifier)
    }
}

// ---------------------------------------------------------------------------
// Anime card — left cover + right info with badges
// ---------------------------------------------------------------------------

@Composable
private fun AnimeArticleCard(
    article: Article,
    onClick: () -> Unit,
    onStarToggle: () -> Unit,
    onDownload: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val parsed = remember(article.title) { parseMikanTitle(article.title) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)),
    ) {
        Row(modifier = Modifier.padding(10.dp)) {
            // Left: vertical anime cover
            if (!article.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = article.thumbnailUrl,
                    contentDescription = parsed.title,
                    modifier = Modifier
                        .width(80.dp)
                        .height(120.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(12.dp))
            }

            // Right: info
            Column(modifier = Modifier.weight(1f)) {
                // Anime title
                Text(
                    text = parsed.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (article.isRead) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))

                // Episode info
                if (parsed.episode != null) {
                    Text(
                        text = "第 ${parsed.episode} 集" + (parsed.season?.let { " · 第${it}季" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                }

                // Badges row: fansub + resolution + size + download
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    parsed.fansub?.let { AnimeBadge(it) }
                    parsed.resolution?.let { AnimeBadge(it) }
                    // File size badge
                    val sizeStr = parsed.fileSize ?: formatContentLength(article.contentLength)
                    if (sizeStr != null) AnimeBadge(sizeStr)
                    // Download icon for articles with torrent enclosure
                    if (!article.enclosureUrl.isNullOrBlank()) {
                        IconButton(
                            onClick = {
                                if (!article.isDownloaded) onDownload?.invoke(article.id)
                            },
                            modifier = Modifier.size(24.dp),
                        ) {
                            Icon(
                                imageVector = if (article.isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                                contentDescription = if (article.isDownloaded) "已下载" else "下载",
                                tint = if (article.isDownloaded) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Feed + time
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = (article.feedTitle ?: "") +
                                (formatRelativeTime(article.publishedAt).let { if (it.isNotBlank()) " · $it" else "" }),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onStarToggle, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = if (article.isStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            contentDescription = if (article.isStarred) "取消收藏" else "收藏",
                            tint = if (article.isStarred) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Standard article card (original layout)
// ---------------------------------------------------------------------------

@Composable
private fun StandardArticleCard(
    article: Article,
    onClick: () -> Unit,
    onStarToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {

            // -- Header: feed icon + name + time --
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Feed icon (remote or placeholder)
                if (!article.feedIcon.isNullOrBlank()) {
                    AsyncImage(
                        model = article.feedIcon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.RssFeed,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))

                Text(
                    text = article.feedTitle ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                Text(
                    text = formatRelativeTime(article.publishedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.height(8.dp))

            // -- Title (bold, clearly separated) --
            val displayTitle = getDisplayTitle(article)
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (article.isRead) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            // -- Summary (de-duplicated, visually separated from title) --
            val cleanSnippet = getCleanSummary(article, displayTitle)
            if (!cleanSnippet.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = cleanSnippet,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                )
            }

            // -- Optional thumbnail (flush with card edges, no container) --
            if (!article.thumbnailUrl.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                AsyncImage(
                    model = article.thumbnailUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            }

            // -- Star button --
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onStarToggle, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (article.isStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                        contentDescription = if (article.isStarred) "取消收藏" else "收藏",
                        tint = if (article.isStarred) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Time formatting helper
// ---------------------------------------------------------------------------

/**
 * Parses an ISO-8601 timestamp and returns a human-readable Chinese relative
 * time string like "3分钟前", "2小时前", "昨天", etc.
 */
fun formatRelativeTime(isoTimestamp: String?): String {
    if (isoTimestamp.isNullOrBlank()) return ""

    val instant = try {
        Instant.parse(isoTimestamp)
    } catch (_: DateTimeParseException) {
        try {
            // Fallback: some servers return timestamps without the trailing Z
            DateTimeFormatter.ISO_DATE_TIME.parse(isoTimestamp, Instant::from)
        } catch (_: Exception) {
            return isoTimestamp.take(10) // just show the date portion as-is
        }
    }

    val now = Instant.now()
    val dur = Duration.between(instant, now)
    val minutes = dur.toMinutes()

    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 1440 -> "${minutes / 60}小时前"
        minutes < 2880 -> "昨天"
        minutes < 43200 -> "${minutes / 1440}天前"
        else -> isoTimestamp.take(10) // fallback to date string
    }
}

private fun formatContentLength(bytes: Long): String? {
    if (bytes <= 0) return null
    return when {
        bytes < 1024L * 1024 -> "%.0f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
