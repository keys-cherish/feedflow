package com.feedflow.app.ui
import com.feedflow.app.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.feedflow.app.download.DownloadConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: FeedRepository,
    onThemeChanged: (String) -> Unit,
) {
    var themeMode by remember { mutableStateOf(repo.getThemeMode()) }
    var stats by remember { mutableStateOf<Stats?>(null) }
    var cacheInfo by remember { mutableStateOf<CacheInfo?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showClearAllDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        stats = repo.getStats()
        cacheInfo = repo.getCacheStats()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
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
            // ===== Theme =====
            SectionHeader(icon = Icons.Default.Palette, title = "外观主题")
            Spacer(Modifier.height(8.dp))

            val themeOptions = listOf(
                Triple("system", "跟随系统", Icons.Default.PhoneAndroid),
                Triple("light", "浅色模式", Icons.Default.LightMode),
                Triple("dark", "深色模式", Icons.Default.DarkMode),
            )

            themeOptions.forEach { (value, label, icon) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            themeMode = value
                            onThemeChanged(value)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = themeMode == value,
                        onClick = {
                            themeMode = value
                            onThemeChanged(value)
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ===== Storage / Cache =====
            SectionHeader(icon = Icons.Default.Storage, title = "存储")
            Spacer(Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (cacheInfo != null) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            StatItem(label = "数据库", value = cacheInfo!!.dbSizeFormatted(), modifier = Modifier.weight(1f))
                            StatItem(label = "正文缓存", value = cacheInfo!!.contentSizeFormatted(), modifier = Modifier.weight(1f))
                            StatItem(label = "文章数", value = cacheInfo!!.articleCount.toString(), modifier = Modifier.weight(1f))
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    val count = repo.clearOldCache(30)
                                    cacheInfo = repo.getCacheStats()
                                    snackbarHostState.showSnackbar("已清理 $count 篇旧文章的正文缓存")
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("清理缓存", style = MaterialTheme.typography.labelMedium)
                        }

                        Spacer(Modifier.width(8.dp))

                        OutlinedButton(
                            onClick = { showClearAllDialog = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("清空全部", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "「清理缓存」删除30天前已读且未收藏文章的正文",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ===== Download Settings =====
            DownloadSettingsSection(repo, snackbarHostState, scope)

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ===== Logging =====
            SectionHeader(icon = Icons.Default.Folder, title = "日志")
            Spacer(Modifier.height(8.dp))

            var logEnabled by remember { mutableStateOf(repo.isLogEnabled()) }
            var logDir by remember { mutableStateOf(repo.getLogDir()) }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("启用日志", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = logEnabled,
                    onCheckedChange = { logEnabled = it; repo.setLogEnabled(it) },
                )
            }

            if (logEnabled) {
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = logDir,
                    onValueChange = { logDir = it; repo.setLogDir(it) },
                    label = { Text("日志目录") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ===== About =====
            SectionHeader(icon = Icons.Default.Info, title = "关于")
            Spacer(Modifier.height(8.dp))

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "FeedFlow",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "版本 0.1.1",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "本地 RSS 阅读器，无需服务器，打开即用",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    if (stats != null) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            StatItem(label = "订阅源", value = stats!!.feeds.toString(), modifier = Modifier.weight(1f))
                            StatItem(label = "文章", value = stats!!.articles.toString(), modifier = Modifier.weight(1f))
                            StatItem(label = "未读", value = stats!!.unread.toString(), modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // Clear all confirmation dialog
    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("清空全部文章") },
            text = { Text("将删除所有文章数据，订阅源保留。刷新后会重新拉取。确定继续？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        repo.clearAllArticles()
                        cacheInfo = repo.getCacheStats()
                        stats = repo.getStats()
                        snackbarHostState.showSnackbar("已清空全部文章")
                    }
                    showClearAllDialog = false
                }) { Text("确定", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// Download settings section
// ---------------------------------------------------------------------------

@Composable
private fun DownloadSettingsSection(
    repo: FeedRepository,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var cfg by remember { mutableStateOf(repo.getDownloadConfig()) }
    var testing by remember { mutableStateOf(false) }

    fun save(newCfg: DownloadConfig) {
        cfg = newCfg
        repo.saveDownloadConfig(newCfg)
    }

    SectionHeader(icon = Icons.Default.Download, title = "下载设置")
    Spacer(Modifier.height(8.dp))

    // Auto-download toggle
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("自动下载新番", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = cfg.autoDownload,
            onCheckedChange = { save(cfg.copy(autoDownload = it)) },
        )
    }
    Text(
        "开启后，刷新蜜柑RSS时自动推送种子到下载客户端",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
    )

    Spacer(Modifier.height(12.dp))

    // Client type
    Text("下载客户端", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))
    val clientTypes = listOf(
        "qbittorrent" to "qBittorrent",
        "aria2" to "Aria2",
        "transmission" to "Transmission",
    )
    clientTypes.forEach { (value, label) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { save(cfg.copy(type = value)) }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = cfg.type == value,
                onClick = { save(cfg.copy(type = value)) },
            )
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }

    Spacer(Modifier.height(8.dp))

    // Host
    OutlinedTextField(
        value = cfg.host,
        onValueChange = { save(cfg.copy(host = it.trim())) },
        label = { Text("主机地址") },
        placeholder = { Text("http://192.168.1.100:8080") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
    )

    Spacer(Modifier.height(8.dp))

    // Username + Password
    if (cfg.type != "aria2") {
        OutlinedTextField(
            value = cfg.username,
            onValueChange = { save(cfg.copy(username = it)) },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
    }

    OutlinedTextField(
        value = cfg.password,
        onValueChange = { save(cfg.copy(password = it)) },
        label = { Text(if (cfg.type == "aria2") "Secret (RPC密钥)" else "密码") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
        visualTransformation = PasswordVisualTransformation(),
    )

    Spacer(Modifier.height(8.dp))

    // Save path
    OutlinedTextField(
        value = cfg.savePath,
        onValueChange = { save(cfg.copy(savePath = it)) },
        label = { Text("保存路径") },
        placeholder = { Text("/downloads/anime") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
    )

    Spacer(Modifier.height(12.dp))

    // Test connection button
    FilledTonalButton(
        onClick = {
            testing = true
            scope.launch {
                val ok = repo.testDownloadConnection()
                testing = false
                snackbarHostState.showSnackbar(if (ok) "连接成功 ✓" else "连接失败，请检查配置")
            }
        },
        enabled = cfg.host.isNotBlank() && !testing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (testing) "测试中…" else "测试连接")
    }
}
