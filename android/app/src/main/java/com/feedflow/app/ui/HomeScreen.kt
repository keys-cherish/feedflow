package com.feedflow.app.ui
import com.feedflow.app.*

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Filter mode for the top chips
// ---------------------------------------------------------------------------

private enum class ArticleFilter(val label: String) {
    ALL("全部"),
    UNREAD("未读"),
    STARRED("收藏"),
}

// ---------------------------------------------------------------------------
// Home screen -- chat-style timeline of latest RSS articles
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(repo: FeedRepository) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // --- State ----
    val articles = remember { mutableStateListOf<Article>() }
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var offset by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(ArticleFilter.ALL) }
    val pageSize = 20

    val listState = rememberLazyListState()

    // --- Data fetching ----

    /** Fetch a page of articles based on the current filter. */
    suspend fun fetchPage(pageOffset: Int): List<Article> {
        val resp = when (filter) {
            ArticleFilter.ALL -> repo.getArticles(limit = pageSize, offset = pageOffset)
            ArticleFilter.UNREAD -> repo.getUnreadArticles(limit = pageSize, offset = pageOffset)
            ArticleFilter.STARRED -> repo.getStarredArticles(limit = pageSize, offset = pageOffset)
        }
        return if (resp.success) resp.data.orEmpty() else {
            snackbarHostState.showSnackbar(resp.error ?: "加载失败")
            emptyList()
        }
    }

    /** Load first page (for initial load or pull-to-refresh). */
    fun refreshData() {
        scope.launch {
            isRefreshing = true
            offset = 0
            hasMore = true
            val data = fetchPage(0)
            articles.clear()
            articles.addAll(data)
            offset = data.size
            hasMore = data.size >= pageSize
            isRefreshing = false
        }
    }

    /** Append next page. */
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

    // Initial load
    LaunchedEffect(filter) {
        isLoading = true
        offset = 0
        hasMore = true
        val data = fetchPage(0)
        articles.clear()
        articles.addAll(data)
        offset = data.size
        hasMore = data.size >= pageSize
        isLoading = false
    }

    // Detect scroll near bottom for pagination
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

    // --- UI ----

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "FeedFlow",
                        fontWeight = FontWeight.Bold,
                    )
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            repo.refreshAll()
                            refreshData()
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新所有订阅源")
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // Filter chips
                item(key = "filters") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ArticleFilter.entries.forEach { f ->
                            FilterChip(
                                selected = filter == f,
                                onClick = { filter = f },
                                label = { Text(f.label) },
                            )
                        }
                    }
                }

                // Empty state / loading
                if (isLoading && articles.isEmpty()) {
                    item(key = "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 64.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (!isLoading && articles.isEmpty()) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 64.dp),
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

                // Article cards
                items(articles, key = { it.id }) { article ->
                    AnimatedVisibility(visible = true, enter = fadeIn()) {
                        ArticleCard(
                            article = article,
                            onClick = {
                                // Mark read then open in browser
                                scope.launch { repo.markArticleRead(article.id) }
                                val idx = articles.indexOfFirst { it.id == article.id }
                                if (idx >= 0) articles[idx] = articles[idx].copy(isRead = true)

                                article.url?.let { url ->
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    context.startActivity(intent)
                                }
                            },
                            onStarToggle = {
                                scope.launch {
                                    val resp = repo.toggleArticleStar(article.id)
                                    if (resp.success && resp.data != null) {
                                        val idx = articles.indexOfFirst { it.id == article.id }
                                        if (idx >= 0) {
                                            articles[idx] = articles[idx].copy(isStarred = resp.data.isStarred)
                                        }
                                    }
                                }
                            },
                        )
                    }
                }

                // Loading more indicator
                if (isLoadingMore) {
                    item(key = "loading_more") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}
