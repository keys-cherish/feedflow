package com.feedflow.app.ui
import com.feedflow.app.*

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedsScreen(repo: FeedRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val feeds = remember { mutableStateListOf<Feed>() }
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var selectedFeed by remember { mutableStateOf<Feed?>(null) }

    var showAddDialog by remember { mutableStateOf(false) }
    var newFeedUrl by remember { mutableStateOf("") }
    var isAdding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }

    // Tag editing state
    var editingTagFeed by remember { mutableStateOf<Feed?>(null) }
    var editingTags by remember { mutableStateOf("") }

    fun loadFeeds() {
        scope.launch {
            isRefreshing = true
            try {
                val count = repo.refreshAll()
                val list = repo.getFeeds()
                feeds.clear()
                feeds.addAll(list)
                snackbarHostState.showSnackbar("刷新完成，新增 $count 篇文章")
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("刷新失败: ${e.message}")
            }
            isRefreshing = false
        }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        try {
            feeds.addAll(repo.getFeeds())
        } catch (_: Exception) { }
        isLoading = false
    }

    if (selectedFeed != null) {
        FeedArticleListScreen(
            feed = selectedFeed!!,
            repo = repo,
            onBack = { selectedFeed = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("订阅源", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加订阅源")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { loadFeeds() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                isLoading && feeds.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                !isLoading && feeds.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "暂无订阅源",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "点击右下角 + 添加",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(feeds, key = { it.id }) { feed ->
                            FeedItem(
                                feed = feed,
                                onClick = { selectedFeed = feed },
                                onDelete = {
                                    scope.launch {
                                        try {
                                            repo.deleteFeed(feed.id)
                                            feeds.removeAll { it.id == feed.id }
                                        } catch (e: Exception) {
                                            snackbarHostState.showSnackbar("删除失败: ${e.message}")
                                        }
                                    }
                                },
                                onLongClick = {
                                    editingTagFeed = feed
                                    editingTags = feed.tags.joinToString(", ")
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; addError = null },
            title = { Text("添加订阅源") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newFeedUrl,
                        onValueChange = { newFeedUrl = it; addError = null },
                        label = { Text("RSS / Atom 链接") },
                        placeholder = { Text("https://example.com/feed.xml") },
                        singleLine = true,
                        isError = addError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (addError != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = addError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isAdding = true
                            addError = null
                            val result = repo.addFeed(newFeedUrl.trim())
                            result.onSuccess { feed ->
                                feeds.add(0, feed)
                                newFeedUrl = ""
                                showAddDialog = false
                                snackbarHostState.showSnackbar("已订阅「${feed.title}」")
                            }.onFailure { e ->
                                addError = e.message ?: "添加失败，请检查链接是否正确"
                            }
                            isAdding = false
                        }
                    },
                    enabled = newFeedUrl.isNotBlank() && !isAdding,
                ) {
                    if (isAdding) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    } else {
                        Text("添加")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    newFeedUrl = ""
                    addError = null
                }) { Text("取消") }
            },
        )
    }

    // Tag editing dialog
    if (editingTagFeed != null) {
        AlertDialog(
            onDismissRequest = { editingTagFeed = null },
            title = { Text("编辑标签") },
            text = {
                Column {
                    Text(
                        editingTagFeed!!.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editingTags,
                        onValueChange = { editingTags = it },
                        label = { Text("标签（逗号分隔）") },
                        placeholder = { Text("技术, AI, 博客") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val feedToUpdate = editingTagFeed ?: return@TextButton
                    val tags = editingTags.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    editingTagFeed = null
                    scope.launch {
                        repo.updateFeedTags(feedToUpdate.id, tags)
                        val idx = feeds.indexOfFirst { it.id == feedToUpdate.id }
                        if (idx >= 0) feeds[idx] = feeds[idx].copy(tags = tags)
                        snackbarHostState.showSnackbar("标签已更新")
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editingTagFeed = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun FeedItem(
    feed: Feed,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else false
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.padding(end = 20.dp),
                )
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .animateContentSize(),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(14.dp),
            ) {
                if (!feed.iconUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = feed.iconUrl,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.RssFeed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = feed.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!feed.description.isNullOrBlank()) {
                        Text(
                            text = feed.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (feed.tags.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            feed.tags.forEach { tag ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.height(24.dp),
                                )
                            }
                        }
                    }
                }

                if (feed.unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (feed.unreadCount > 99) "99+" else feed.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedArticleListScreen(
    feed: Feed,
    repo: FeedRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val articles = remember { mutableStateListOf<Article>() }
    var isLoading by remember { mutableStateOf(true) }
    var isRefreshing by remember { mutableStateOf(false) }

    LaunchedEffect(feed.id) {
        articles.addAll(repo.getFeedArticles(feed.id, limit = 50))
        isLoading = false
    }

    fun refresh() {
        scope.launch {
            isRefreshing = true
            repo.refreshFeed(feed.id)
            val list = repo.getFeedArticles(feed.id, limit = 50)
            articles.clear()
            articles.addAll(list)
            isRefreshing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            repo.markFeedAllRead(feed.id)
                            articles.indices.forEach { i ->
                                articles[i] = articles[i].copy(isRead = true)
                            }
                        }
                    }) {
                        Icon(Icons.Default.DoneAll, contentDescription = "全部标记已读")
                    }
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
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
            onRefresh = { refresh() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                articles.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "该订阅源暂无文章",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(articles, key = { it.id }) { article ->
                            ArticleCard(
                                article = article.copy(
                                    feedTitle = feed.title,
                                    feedIcon = feed.iconUrl,
                                ),
                                onClick = {
                                    scope.launch { repo.markArticleRead(article.id) }
                                    val idx = articles.indexOfFirst { it.id == article.id }
                                    if (idx >= 0) articles[idx] = articles[idx].copy(isRead = true)
                                    article.url?.let { url ->
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                },
                                onStarToggle = {
                                    scope.launch {
                                        val updated = repo.toggleArticleStar(article.id)
                                        if (updated != null) {
                                            val idx = articles.indexOfFirst { it.id == article.id }
                                            if (idx >= 0) articles[idx] = articles[idx].copy(isStarred = updated.isStarred)
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
                }
            }
        }
    }
}
