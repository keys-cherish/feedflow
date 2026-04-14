package com.feedflow.app.ui
import com.feedflow.app.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext

// ---------------------------------------------------------------------------
// Navigation items
// ---------------------------------------------------------------------------

private data class NavItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val navItems = listOf(
    NavItem("首页", Icons.Filled.Home, Icons.Outlined.Home),
    NavItem("订阅", Icons.Filled.RssFeed, Icons.Outlined.RssFeed),
    NavItem("发现", Icons.Filled.Explore, Icons.Outlined.Explore),
    NavItem("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
)

// ---------------------------------------------------------------------------
// Root composable
// ---------------------------------------------------------------------------

/**
 * Top-level composable that wires together theming, navigation, and the
 * shared [FeedRepository] instance.
 *
 * Called directly from [com.feedflow.app.MainActivity.onCreate].
 */
@Composable
fun FeedFlowApp() {
    val context = LocalContext.current

    // Single repository instance shared across all screens
    val repo = remember { FeedRepository(context.applicationContext) }

    // Read theme preference and track it as state for recomposition
    val storedTheme by repo.themeModeFlow.collectAsState(initial = "system")
    var themeMode by remember { mutableStateOf("system") }

    // Keep local theme in sync with DataStore
    LaunchedEffect(storedTheme) { themeMode = storedTheme }

    // Initialize Retrofit on first composition (non-blocking)
    LaunchedEffect(Unit) { repo.rebuildApi() }

    FeedFlowTheme(themeMode = themeMode) {
        val selectedTab = rememberSaveable { mutableIntStateOf(0) }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    navItems.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = selectedTab.intValue == index,
                            onClick = { selectedTab.intValue = index },
                            icon = {
                                Icon(
                                    imageVector = if (selectedTab.intValue == index) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label,
                                )
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            // Apply bottom-nav padding so screens don't render behind it
            Box(modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())) {
                when (selectedTab.intValue) {
                    0 -> HomeScreen(repo = repo)
                    1 -> FeedsScreen(repo = repo)
                    2 -> DiscoverScreen(repo = repo)
                    3 -> SettingsScreen(
                        repo = repo,
                        onThemeChanged = { newMode -> themeMode = newMode },
                    )
                }
            }
        }
    }
}
