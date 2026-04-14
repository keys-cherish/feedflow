package com.feedflow.app.ui
import com.feedflow.app.*

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Discover screen -- add RSS feed + Bangumi anime search
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(repo: FeedRepository) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // --- Add RSS state ---
    var feedUrl by remember { mutableStateOf("") }
    var isAddingFeed by remember { mutableStateOf(false) }

    // --- Bangumi search state ---
    var bangumiQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    val bangumiResults = remember { mutableStateListOf<BangumiResult>() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("发现", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // ----- Section: Add RSS feed -----

            Text(
                text = "添加订阅源",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = feedUrl,
                onValueChange = { feedUrl = it },
                label = { Text("RSS / Atom 链接") },
                placeholder = { Text("https://example.com/feed.xml") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        isAddingFeed = true
                        val resp = repo.addFeed(feedUrl.trim())
                        if (resp.success) {
                            snackbarHostState.showSnackbar("订阅成功: ${resp.data?.title ?: feedUrl}")
                            feedUrl = ""
                        } else {
                            snackbarHostState.showSnackbar(resp.error ?: "订阅失败")
                        }
                        isAddingFeed = false
                    }
                },
                enabled = feedUrl.isNotBlank() && !isAddingFeed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isAddingFeed) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("订阅")
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ----- Section: Bangumi search -----

            Text(
                text = "Bangumi 番剧搜索",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "搜索番剧并快速订阅对应的 RSS 更新",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = bangumiQuery,
                    onValueChange = { bangumiQuery = it },
                    label = { Text("搜索番剧名称") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isSearching = true
                            val resp = repo.searchBangumi(bangumiQuery.trim())
                            bangumiResults.clear()
                            if (resp.success) {
                                bangumiResults.addAll(resp.data.orEmpty())
                                if (resp.data.isNullOrEmpty()) {
                                    snackbarHostState.showSnackbar("未找到相关番剧")
                                }
                            } else {
                                snackbarHostState.showSnackbar(resp.error ?: "搜索失败")
                            }
                            isSearching = false
                        }
                    },
                    enabled = bangumiQuery.isNotBlank() && !isSearching,
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // --- Search results grid ---
            if (bangumiResults.isNotEmpty()) {
                // We use a fixed-height grid inside the scroll column.
                // Calculate needed height: 2 columns, each item ~200dp tall.
                val rows = (bangumiResults.size + 1) / 2
                val gridHeight = (rows * 220).dp

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(0.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(gridHeight),
                    userScrollEnabled = false, // outer column handles scroll
                ) {
                    items(bangumiResults, key = { it.bgmId }) { item ->
                        BangumiCard(
                            item = item,
                            onSubscribe = {
                                val rssUrl = item.rssUrl
                                if (!rssUrl.isNullOrBlank()) {
                                    scope.launch {
                                        val resp = repo.addFeed(rssUrl)
                                        if (resp.success) {
                                            snackbarHostState.showSnackbar("已订阅: ${item.bgmName}")
                                        } else {
                                            snackbarHostState.showSnackbar(resp.error ?: "订阅失败")
                                        }
                                    }
                                } else {
                                    scope.launch {
                                        snackbarHostState.showSnackbar("该番剧暂无 RSS 源")
                                    }
                                }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp)) // bottom padding
        }
    }
}

// ---------------------------------------------------------------------------
// Bangumi result card
// ---------------------------------------------------------------------------

@Composable
private fun BangumiCard(
    item: BangumiResult,
    onSubscribe: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column {
            // Cover image
            if (item.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = item.bgmName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "暂无封面",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = item.bgmName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onSubscribe,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("订阅", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
