package com.feedflow.app.ui
import com.feedflow.app.*

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private enum class ArticleFilter(val label: String) {
    ALL("全部"),
    UNREAD("未读"),
    STARRED("收藏"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(repo: FeedRepository, onArticleClick: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val articles = remember { mutableStateListOf<Article>() }
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var offset by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(ArticleFilter.ALL) }
    val pageSize = 20

    // Tag filter state
    var availableTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedTag by remember { mutableStateOf<String?>(null) }

    // Reader overlay state
    var selectedArticle by remember { mutableStateOf<Article?>(null) }
    val readerArticle = remember { mutableStateOf<Article?>(null) }
    if (selectedArticle != null) readerArticle.value = selectedArticle

    // Search state
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchResults = remember { mutableStateListOf<Article>() }

    val listState = rememberLazyListState()

    // Theme colors for reader CSS
    val textColor = MaterialTheme.colorScheme.onSurface
    val bgColor = MaterialTheme.colorScheme.surface
    val linkColor = MaterialTheme.colorScheme.primary
    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant

    suspend fun fetchPage(pageOffset: Int): List<Article> {
        return try {
            if (selectedTag != null) {
                repo.getArticlesByTag(selectedTag!!, limit = pageSize, offset = pageOffset)
            } else when (filter) {
                ArticleFilter.ALL -> repo.getArticles(limit = pageSize, offset = pageOffset)
                ArticleFilter.UNREAD -> repo.getUnreadArticles(limit = pageSize, offset = pageOffset)
                ArticleFilter.STARRED -> repo.getStarredArticles(limit = pageSize, offset = pageOffset)
            }
        } catch (e: Exception) {
            snackbarHostState.showSnackbar("加载失败: ${e.message}")
            emptyList()
        }
    }

    fun refreshData() {
        scope.launch {
            isRefreshing = true
            offset = 0
            hasMore = true
            val data = fetchPage(0)
            // Replace atomically — don't clear first to avoid blank flash
            articles.clear()
            articles.addAll(data)
            offset = data.size
            hasMore = data.size >= pageSize
            isRefreshing = false
        }
    }

    fun loadMore() {
        if (isLoadingMore || !hasMore) return
        scope.launch {
            isLoadingMore = true
            val data = fetchPage(offset)
            articles.addAll(data)
            offset += data.size
            hasMore = data.size >= pageSize
            isLoadingMore = false
        }
    }

    fun doSearch(query: String) {
        if (query.isBlank()) { searchResults.clear(); return }
        scope.launch {
            try {
                val results = repo.searchArticles(query, limit = 50)
                searchResults.clear()
                searchResults.addAll(results)
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("搜索失败: ${e.message}")
            }
        }
    }

    LaunchedEffect(filter, selectedTag) {
        isLoading = true
        offset = 0
        hasMore = true
        availableTags = repo.getAllTags()
        val data = fetchPage(0)
        articles.clear()
        articles.addAll(data)
        offset = data.size
        hasMore = data.size >= pageSize
        isLoading = false
    }

    val reachedBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= articles.size - 5
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { reachedBottom }
            .distinctUntilChanged()
            .collect { atBottom -> if (atBottom) loadMore() }
    }

    // Preloaded HTML cache for visible articles (avoids WebView build delay on click)
    val preloadedHtml = remember { mutableMapOf<String, String>() }

    fun buildArticleHtml(a: Article): String {
        val dt = getDisplayTitle(a)
        val raw = a.contentHtml
            ?: a.summary?.let { "<p>${it.replace(Regex("<[^>]+>"), "").trim()}</p>" } ?: ""
        val textHex = String.format("#%06X", textColor.toArgb() and 0xFFFFFF)
        val bgHex = String.format("#%06X", bgColor.toArgb() and 0xFFFFFF)
        val linkHex = String.format("#%06X", linkColor.toArgb() and 0xFFFFFF)
        val mHex = String.format("#%06X", metaColor.toArgb() and 0xFFFFFF)
        val fn = a.feedTitle ?: ""
        val ts = formatRelativeTime(a.publishedAt)
        val ml = if (fn.isNotBlank() && ts.isNotBlank()) "$fn &middot; $ts" else fn + ts
        val et = dt.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=3.0">
            <style>
            *{box-sizing:border-box;margin:0;padding:0}
            body{font-family:-apple-system,"Noto Sans SC","PingFang SC",sans-serif;font-size:16px;line-height:1.8;color:$textHex;background:$bgHex;padding:16px 20px 48px;word-wrap:break-word;overflow-wrap:anywhere;-webkit-text-size-adjust:100%}
            h1.title{font-size:20px;line-height:1.4;font-weight:700;margin-bottom:8px}
            .meta{font-size:13px;color:$mHex;margin-bottom:20px;padding-bottom:14px;border-bottom:1px solid ${mHex}30}
            .content p{margin-bottom:1em}
            .content h1,.content h2,.content h3,.content h4,.content h5,.content h6{margin-top:1.5em;margin-bottom:0.6em;line-height:1.4;font-weight:600}
            .content h1{font-size:1.4em}.content h2{font-size:1.25em}.content h3{font-size:1.1em}
            .content img{max-width:100%;height:auto;border-radius:8px;margin:8px 0;display:block}
            .content img[src=""],.content img:not([src]){display:none}
            .content a{color:$linkHex;text-decoration:none;word-break:break-all}
            .content pre{overflow-x:auto;background:${mHex}15;padding:12px;border-radius:6px;margin:12px 0;line-height:1.5;font-size:14px}
            .content code{font-family:"SF Mono",Menlo,Consolas,monospace;font-size:0.9em;background:${mHex}15;border-radius:3px;padding:2px 6px}
            .content pre code{background:none;padding:0}
            .content blockquote{border-left:3px solid $linkHex;padding:8px 16px;margin:12px 0;color:$mHex;background:${mHex}08;border-radius:0 4px 4px 0}
            .content blockquote p{margin-bottom:0.5em}.content blockquote p:last-child{margin-bottom:0}
            .content ul,.content ol{padding-left:24px;margin-bottom:1em}
            .content li{margin-bottom:0.4em;line-height:1.7}
            .content table{border-collapse:collapse;width:100%;margin:12px 0;font-size:14px}
            .content th,.content td{border:1px solid ${mHex}30;padding:8px 12px;text-align:left}
            .content th{background:${mHex}10;font-weight:600}
            .content hr{border:none;border-top:1px solid ${mHex}20;margin:1.5em 0}
            .content input[type="checkbox"]{margin-right:8px;vertical-align:middle}
            .content video,.content iframe{max-width:100%;border-radius:8px;margin:8px 0}
            .content strong{font-weight:600}.content em{font-style:italic}
            .content sup{font-size:0.75em}.content figure{margin:12px 0}
            .content figcaption{font-size:0.85em;color:$mHex;text-align:center;margin-top:4px}
            .end-marker{text-align:center;color:${mHex};font-size:13px;padding:32px 0 16px;border-top:1px solid ${mHex}20;margin-top:24px}
            </style></head><body>
            <h1 class="title">$et</h1>
            <div class="meta">$ml</div>
            <div class="content">$raw</div>
            <div class="end-marker">— 已到底部 —</div>
            </body></html>
        """.trimIndent()
    }

    // Preload visible articles' HTML on scroll
    LaunchedEffect(Unit) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                articles.getOrNull(info.index - 1) // offset by filter chip item
            }
        }.distinctUntilChanged().collect { visibleArticles ->
            visibleArticles.forEach { a ->
                if (a.id !in preloadedHtml && a.contentHtml != null) {
                    preloadedHtml[a.id] = buildArticleHtml(a)
                    // Cap cache at 20 entries
                    if (preloadedHtml.size > 20) {
                        preloadedHtml.keys.firstOrNull()?.let { preloadedHtml.remove(it) }
                    }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isSearching) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it; doSearch(it) },
                            placeholder = { Text("搜索文章（支持正则）", style = MaterialTheme.typography.bodyMedium) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Text("FeedFlow", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    if (isSearching) {
                        IconButton(onClick = {
                            isSearching = false
                            searchQuery = ""
                            searchResults.clear()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭搜索")
                        }
                    } else {
                        IconButton(onClick = { isSearching = true }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = {
                            scope.launch {
                                isRefreshing = true
                                try {
                                    val count = repo.refreshAll()
                                    // Reload articles inline (no nested coroutine)
                                    offset = 0
                                    hasMore = true
                                    val data = fetchPage(0)
                                    articles.clear()
                                    articles.addAll(data)
                                    offset = data.size
                                    hasMore = data.size >= pageSize
                                    snackbarHostState.showSnackbar("刷新完成，新增 $count 篇文章")
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("刷新失败: ${e.message}")
                                }
                                isRefreshing = false
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新所有订阅源")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { refreshData() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // Show search results or normal feed
                val displayList = if (isSearching && searchQuery.isNotBlank()) searchResults else articles

                if (!isSearching) {
                    item(key = "filters") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            ArticleFilter.entries.forEach { f ->
                                FilterChip(
                                    selected = filter == f,
                                    onClick = { filter = f; selectedTag = null },
                                    label = { Text(f.label) },
                                )
                            }
                            // Tag filter chips
                            availableTags.forEach { tag ->
                                FilterChip(
                                    selected = selectedTag == tag,
                                    onClick = {
                                        selectedTag = if (selectedTag == tag) null else tag
                                    },
                                    label = { Text(tag) },
                                )
                            }
                        }
                    }
                } else if (searchQuery.isNotBlank() && searchResults.isEmpty()) {
                    item(key = "no_results") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("无搜索结果", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (!isSearching && isLoading && articles.isEmpty()) {
                    item(key = "loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                } else if (!isSearching && !isLoading && articles.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "暂无文章",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                items(displayList, key = { it.id }) { article ->
                    AnimatedVisibility(visible = true, enter = fadeIn()) {
                        ArticleCard(
                            article = article,
                            onClick = {
                                // Mark as read
                                scope.launch { repo.markArticleRead(article.id) }
                                val idx = articles.indexOfFirst { it.id == article.id }
                                if (idx >= 0) articles[idx] = articles[idx].copy(isRead = true)
                                // Open BottomSheet reader instead of navigating
                                selectedArticle = article
                            },
                            onStarToggle = {
                                scope.launch {
                                    val updated = repo.toggleArticleStar(article.id)
                                    if (updated != null) {
                                        val idx = articles.indexOfFirst { it.id == article.id }
                                        if (idx >= 0) articles[idx] = articles[idx].copy(isStarred = updated.isStarred)
                                        snackbarHostState.showSnackbar(
                                            if (updated.isStarred) "已收藏" else "已取消收藏"
                                        )
                                    }
                                }
                            },
                            onDownload = { articleId ->
                                scope.launch {
                                    val result = repo.downloadArticle(articleId)
                                    val idx = articles.indexOfFirst { it.id == articleId }
                                    if (result.isSuccess) {
                                        if (idx >= 0) articles[idx] = articles[idx].copy(isDownloaded = true)
                                        snackbarHostState.showSnackbar("已推送下载")
                                    } else {
                                        snackbarHostState.showSnackbar("下载失败: ${result.exceptionOrNull()?.message}")
                                    }
                                }
                            },
                        )
                    }
                }

                if (isLoadingMore) {
                    item(key = "loading_more") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                    }
                }
            }
        }
    }

    // Article reader overlay — AFTER Scaffold so it draws on top
    BackHandler(selectedArticle != null) { selectedArticle = null }
    AnimatedVisibility(
        visible = selectedArticle != null,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        val article = readerArticle.value ?: return@AnimatedVisibility

        // Pull-down-to-dismiss state
        var dragOffsetY by remember { mutableStateOf(0f) }
        val dismissThreshold = 300f // px, ~150dp on most screens

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = with(androidx.compose.ui.platform.LocalDensity.current) { dragOffsetY.coerceAtLeast(0f).toDp() }),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                // Top bar with integrated drag handle
                Surface(
                    tonalElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                dragOffsetY = (dragOffsetY + delta).coerceAtLeast(0f)
                            },
                            onDragStopped = {
                                if (dragOffsetY > dismissThreshold) {
                                    selectedArticle = null
                                }
                                dragOffsetY = 0f
                            },
                        ),
                ) {
                    Column {
                        // Drag handle indicator
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                            )
                        }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        IconButton(onClick = { selectedArticle = null }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                        Text(
                            text = buildString {
                                article.feedTitle?.let { append(it) }
                                article.publishedAt?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append(formatRelativeTime(it))
                                }
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val updated = repo.toggleArticleStar(article.id)
                                    if (updated != null) {
                                        selectedArticle = article.copy(isStarred = updated.isStarred)
                                        val idx = articles.indexOfFirst { it.id == article.id }
                                        if (idx >= 0) articles[idx] = articles[idx].copy(isStarred = updated.isStarred)
                                        snackbarHostState.showSnackbar(
                                            if (updated.isStarred) "已收藏" else "已取消收藏"
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                if (article.isStarred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                                contentDescription = "收藏",
                                tint = if (article.isStarred) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!article.url.isNullOrBlank()) {
                            IconButton(
                                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.url))) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = "在浏览器中打开",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    } // end inner Column (handle + Row)
                }

                // Use preloaded HTML if available, otherwise build on the fly
                val fullHtml = preloadedHtml[article.id] ?: buildArticleHtml(article)

                // WebView — spinner covers until content is actually painted
                var webViewReady by remember(article.id) { mutableStateOf(false) }

                Box(Modifier.fillMaxWidth().weight(1f)) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = false
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.builtInZoomControls = false
                                settings.domStorageEnabled = true
                                settings.cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
                                setBackgroundColor(bgColor.toArgb())
                                // Start invisible — spinner covers
                                visibility = android.view.View.INVISIBLE
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                                        return true
                                    }
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        // Wait for actual render (next frame + 150ms buffer)
                                        view?.postDelayed({
                                            view.visibility = android.view.View.VISIBLE
                                            webViewReady = true
                                        }, 150)
                                    }
                                }
                                loadDataWithBaseURL(article.url, fullHtml, "text/html", "utf-8", null)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Spinner on top until WebView is rendered
                    if (!webViewReady) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
    } // end Box
}
