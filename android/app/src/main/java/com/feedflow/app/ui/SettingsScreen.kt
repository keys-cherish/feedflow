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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Settings screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: FeedRepository,
    onThemeChanged: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Read current values from DataStore
    val serverUrl by repo.serverUrlFlow.collectAsState(initial = "")
    val authToken by repo.authTokenFlow.collectAsState(initial = "")
    val themeMode by repo.themeModeFlow.collectAsState(initial = "system")

    // Local editing state -- initialized from DataStore on first compose
    var editServerUrl by remember { mutableStateOf("") }
    var editAuthToken by remember { mutableStateOf("") }
    var isSaving by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<Stats?>(null) }

    // Sync DataStore values into local state once they arrive
    LaunchedEffect(serverUrl) { if (editServerUrl.isEmpty()) editServerUrl = serverUrl }
    LaunchedEffect(authToken) { if (editAuthToken.isEmpty()) editAuthToken = authToken }

    // Load stats
    LaunchedEffect(Unit) {
        val resp = repo.getStats()
        if (resp.success) stats = resp.data
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

            // ===== Server connection =====
            SectionHeader(icon = Icons.Default.Storage, title = "服务器连接")
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = editServerUrl,
                onValueChange = { editServerUrl = it },
                label = { Text("服务器地址") },
                placeholder = { Text("http://192.168.1.100:8080") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = editAuthToken,
                onValueChange = { editAuthToken = it },
                label = { Text("认证令牌 (Token)") },
                placeholder = { Text("Bearer token 或留空") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    scope.launch {
                        isSaving = true
                        repo.setServerUrl(editServerUrl.trim())
                        repo.setAuthToken(editAuthToken.trim())
                        // Test connection
                        val resp = repo.getStats()
                        if (resp.success) {
                            stats = resp.data
                            snackbarHostState.showSnackbar("连接成功")
                        } else {
                            snackbarHostState.showSnackbar("连接失败: ${resp.error ?: "未知错误"}")
                        }
                        isSaving = false
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("保存并测试连接")
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ===== Theme =====
            SectionHeader(icon = Icons.Default.Palette, title = "外观主题")
            Spacer(Modifier.height(8.dp))

            val themeOptions = listOf(
                "system" to "跟随系统",
                "light" to "浅色模式",
                "dark" to "深色模式",
            )

            themeOptions.forEach { (value, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                repo.setThemeMode(value)
                                onThemeChanged(value)
                            }
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = themeMode == value,
                        onClick = {
                            scope.launch {
                                repo.setThemeMode(value)
                                onThemeChanged(value)
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    if (value == "dark") {
                        Icon(
                            Icons.Default.DarkMode,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // ===== Stats / About =====
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
                        "版本 0.1.0",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "自托管 RSS 阅读器，支持 AI 摘要、Bangumi 追番",
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
}

// ---------------------------------------------------------------------------
// Small helper composables
// ---------------------------------------------------------------------------

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
