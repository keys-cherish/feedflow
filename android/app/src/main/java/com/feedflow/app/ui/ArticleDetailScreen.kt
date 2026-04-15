package com.feedflow.app.ui
import com.feedflow.app.*

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Article detail -- in-app reading experience
// ---------------------------------------------------------------------------

/**
 * Full-screen article reader. Shows the article content inside the app
 * instead of redirecting to an external browser.
 *
 * Content priority: contentHtml > summary (as HTML) > plain text fallback.
 * HTML content is rendered via Android WebView with JavaScript disabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleDetailScreen(
    articleId: String,
    repo: FeedRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var article by remember { mutableStateOf<Article?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Load article from DB and mark as read
    LaunchedEffect(articleId) {
        article = repo.getArticle(articleId)
        if (article != null) {
            repo.markArticleRead(articleId)
            // Reflect read state locally
            article = article?.copy(isRead = true)
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = article?.feedTitle ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // Star toggle in top bar
                    if (article != null) {
                        val starred = article!!.isStarred
                        IconButton(onClick = {
                            scope.launch {
                                val updated = repo.toggleArticleStar(articleId)
                                if (updated != null) {
                                    article = article?.copy(isStarred = updated.isStarred)
                                }
                            }
                        }) {
                            Icon(
                                imageVector = if (starred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                contentDescription = if (starred) "取消收藏" else "收藏",
                                tint = if (starred) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            article == null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "文章不存在",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                val art = article!!
                val htmlContent = art.contentHtml
                val hasHtml = !htmlContent.isNullOrBlank()

                if (hasHtml) {
                    // HTML content: use WebView for the entire reading experience
                    ArticleWebView(
                        article = art,
                        htmlBody = htmlContent!!,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                } else {
                    // Plain text fallback: scrollable column
                    ArticlePlainText(
                        article = art,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// WebView renderer -- wraps article HTML in a styled page
// ---------------------------------------------------------------------------

@Composable
private fun ArticleWebView(
    article: Article,
    htmlBody: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Read theme colors to inject into HTML CSS
    val textColor = MaterialTheme.colorScheme.onSurface
    val bgColor = MaterialTheme.colorScheme.background
    val linkColor = MaterialTheme.colorScheme.primary
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant

    val textHex = colorToHex(textColor.toArgb())
    val bgHex = colorToHex(bgColor.toArgb())
    val linkHex = colorToHex(linkColor.toArgb())
    val metaHex = colorToHex(metaColor.toArgb())

    val displayTitle = getDisplayTitle(article)
    val timeStr = formatRelativeTime(article.publishedAt)
    val feedName = article.feedTitle ?: ""
    val metaLine = if (feedName.isNotBlank() && timeStr.isNotBlank()) {
        "$feedName &middot; $timeStr"
    } else {
        feedName + timeStr
    }

    // Build the "open in browser" button only if there's a URL
    val browserButton = if (!article.url.isNullOrBlank()) {
        """<a class="browser-btn" href="${article.url}">在浏览器中打开原文</a>"""
    } else {
        ""
    }

    val fullHtml = """
    <!DOCTYPE html>
    <html>
    <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0">
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: -apple-system, "Noto Sans SC", "PingFang SC", sans-serif;
            font-size: 16px;
            line-height: 1.8;
            color: $textHex;
            background: $bgHex;
            padding: 16px;
            word-wrap: break-word;
            overflow-wrap: break-word;
        }
        h1.title {
            font-size: 22px;
            line-height: 1.4;
            font-weight: 700;
            margin-bottom: 8px;
        }
        .meta {
            font-size: 13px;
            color: $metaHex;
            margin-bottom: 20px;
            padding-bottom: 16px;
            border-bottom: 1px solid ${metaHex}33;
        }
        .content { margin-bottom: 32px; }
        .content p { margin-bottom: 1em; }
        .content img {
            max-width: 100%;
            height: auto;
            border-radius: 8px;
            margin: 8px 0;
        }
        .content a { color: $linkHex; text-decoration: none; }
        .content a:hover { text-decoration: underline; }
        .content pre, .content code {
            font-size: 14px;
            background: ${metaHex}18;
            border-radius: 4px;
            padding: 2px 6px;
        }
        .content pre {
            padding: 12px;
            overflow-x: auto;
            margin: 12px 0;
        }
        .content blockquote {
            border-left: 3px solid $linkHex;
            padding: 4px 16px;
            margin: 12px 0;
            color: $metaHex;
        }
        .content h1, .content h2, .content h3, .content h4 {
            margin-top: 1.2em;
            margin-bottom: 0.6em;
        }
        .browser-btn {
            display: block;
            text-align: center;
            padding: 12px 24px;
            margin: 8px 0 32px 0;
            border: 1px solid ${linkHex}66;
            border-radius: 24px;
            color: $linkHex;
            text-decoration: none;
            font-size: 14px;
        }
    </style>
    </head>
    <body>
        <h1 class="title">${escapeHtml(displayTitle)}</h1>
        <div class="meta">$metaLine</div>
        <div class="content">$htmlBody</div>
        $browserButton
    </body>
    </html>
    """.trimIndent()

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                // Security: disable JS since we're just rendering article HTML
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
                settings.allowFileAccess = false

                // Appearance
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true

                // Intercept link clicks to open in external browser
                webViewClient = object : WebViewClient() {
                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        url?.let {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
                        }
                        return true
                    }
                }

                loadDataWithBaseURL(null, fullHtml, "text/html", "utf-8", null)
            }
        },
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------
// Plain text fallback
// ---------------------------------------------------------------------------

@Composable
private fun ArticlePlainText(
    article: Article,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Title
        Text(
            text = getDisplayTitle(article),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(8.dp))

        // Feed name + time
        val feedName = article.feedTitle ?: ""
        val timeStr = formatRelativeTime(article.publishedAt)
        val metaText = listOfNotNull(
            feedName.ifBlank { null },
            timeStr.ifBlank { null },
        ).joinToString(" \u00B7 ")

        if (metaText.isNotBlank()) {
            Text(
                text = metaText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }

        // Content: use summary as plain text
        val textContent = (article.summary ?: "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (textContent.isNotBlank()) {
            Text(
                text = textContent,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.3f),
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(
                text = "无正文内容",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))

        // "Open in browser" button
        if (!article.url.isNullOrBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                FilledTonalButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(article.url))
                        )
                    },
                ) {
                    Icon(
                        Icons.Default.OpenInBrowser,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("在浏览器中打开原文")
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Convert ARGB int to "#RRGGBB" hex string for CSS. */
private fun colorToHex(argb: Int): String {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return String.format("#%02X%02X%02X", r, g, b)
}

/** Minimal HTML escaping for embedding text in HTML attributes/tags. */
private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
