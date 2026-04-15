package com.feedflow.app.ui
import com.feedflow.app.*

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import java.net.URLDecoder
import java.net.URLEncoder

private data class NavItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val navItems = listOf(
    NavItem("首页", Icons.Filled.Home, Icons.Outlined.Home),
    NavItem("番剧", Icons.Filled.PlayCircle, Icons.Outlined.PlayCircle),
    NavItem("订阅", Icons.Filled.RssFeed, Icons.Outlined.RssFeed),
    NavItem("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
)

// Route constants
private object Routes {
    const val HOME = "home"
    const val ANIME = "anime"
    const val FEEDS = "feeds"
    const val SETTINGS = "settings"
    const val ARTICLE_DETAIL = "article/{articleId}"
    const val ANIME_DETAIL = "anime_detail/{animeName}"

    fun articleDetail(articleId: String) = "article/$articleId"
    fun animeDetail(animeName: String) = "anime_detail/${URLEncoder.encode(animeName, "UTF-8")}"
}

// Map tab index to route for bottom nav
private val tabRoutes = listOf(Routes.HOME, Routes.ANIME, Routes.FEEDS, Routes.SETTINGS)

@Composable
fun FeedFlowApp() {
    val context = LocalContext.current
    val repo = remember { FeedRepository(context.applicationContext) }
    var themeMode by remember { mutableStateOf(repo.getThemeMode()) }

    FeedFlowTheme(themeMode = themeMode) {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        // Determine if bottom bar should be visible (hide on detail screens)
        val showBottomBar = currentRoute in tabRoutes

        // Track selected tab -- derive from current route when on a tab screen
        val selectedTab = rememberSaveable { mutableIntStateOf(0) }
        if (currentRoute != null && currentRoute in tabRoutes) {
            selectedTab.intValue = tabRoutes.indexOf(currentRoute)
        }

        Scaffold(
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        navItems.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = selectedTab.intValue == index,
                                onClick = {
                                    if (selectedTab.intValue != index) {
                                        selectedTab.intValue = index
                                        navController.navigate(tabRoutes[index]) {
                                            // Pop back to home to avoid stacking tab destinations
                                            popUpTo(Routes.HOME) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
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
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(bottom = if (showBottomBar) innerPadding.calculateBottomPadding() else innerPadding.calculateBottomPadding()),
                // Disable default crossfade to keep things snappy
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
            ) {
                composable(Routes.HOME) {
                    HomeScreen(
                        repo = repo,
                        onArticleClick = { articleId ->
                            navController.navigate(Routes.articleDetail(articleId))
                        },
                    )
                }

                composable(Routes.ANIME) {
                    AnimeScreen(
                        repo = repo,
                        onAnimeClick = { animeName ->
                            navController.navigate(Routes.animeDetail(animeName))
                        },
                    )
                }

                composable(Routes.FEEDS) {
                    FeedsScreen(repo = repo)
                }

                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        repo = repo,
                        onThemeChanged = { newMode ->
                            repo.setThemeMode(newMode)
                            themeMode = newMode
                        },
                    )
                }

                composable(
                    route = Routes.ARTICLE_DETAIL,
                    arguments = listOf(navArgument("articleId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val articleId = backStackEntry.arguments?.getString("articleId") ?: return@composable
                    ArticleDetailScreen(
                        articleId = articleId,
                        repo = repo,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(
                    route = Routes.ANIME_DETAIL,
                    arguments = listOf(navArgument("animeName") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val encodedName = backStackEntry.arguments?.getString("animeName") ?: return@composable
                    val animeName = URLDecoder.decode(encodedName, "UTF-8")
                    AnimeDetailScreen(
                        animeName = animeName,
                        repo = repo,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
