package com.feedflow.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------------------
// DataStore extension -- one per-process singleton
// ---------------------------------------------------------------------------

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "feedflow_prefs")

/**
 * Single source of truth for all data operations.
 *
 * Manages:
 * - Persistent preferences via DataStore (server URL, auth token, theme)
 * - A lazily-rebuilt Retrofit instance that follows the current server URL
 * - Clean suspend wrappers around every API call with unified error handling
 */
class FeedRepository(private val context: Context) {

    // ---- Preference keys --------------------------------------------------

    companion object {
        val KEY_SERVER_URL = stringPreferencesKey("server_url")
        val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode") // "light" | "dark" | "system"
    }

    // ---- Preferences as Flows ---------------------------------------------

    val serverUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_URL] ?: ""
    }

    val authTokenFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTH_TOKEN] ?: ""
    }

    val themeModeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: "system"
    }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[KEY_SERVER_URL] = url.trimEnd('/') }
        rebuildApi() // base URL changed -> recreate Retrofit
    }

    suspend fun setAuthToken(token: String) {
        context.dataStore.edit { it[KEY_AUTH_TOKEN] = token }
        rebuildApi() // token changed -> recreate interceptor
    }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[KEY_THEME_MODE] = mode }
    }

    // ---- Retrofit management ----------------------------------------------

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    /** Current Retrofit API proxy. Null when no server URL is configured. */
    private val _api = MutableStateFlow<FeedFlowApi?>(null)
    val apiReady: StateFlow<FeedFlowApi?> = _api.asStateFlow()

    /** Build (or rebuild) the Retrofit instance from current DataStore values. */
    suspend fun rebuildApi() {
        val baseUrl = context.dataStore.data.first()[KEY_SERVER_URL] ?: ""
        val token = context.dataStore.data.first()[KEY_AUTH_TOKEN] ?: ""

        if (baseUrl.isBlank()) {
            _api.value = null
            return
        }

        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val authInterceptor = Interceptor { chain ->
            val req = chain.request().newBuilder().apply {
                if (token.isNotBlank()) {
                    addHeader("Authorization", "Bearer $token")
                }
            }.build()
            chain.proceed(req)
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val contentType = "application/json".toMediaType()

        val retrofit = Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()

        _api.value = retrofit.create(FeedFlowApi::class.java)
    }

    /** Convenience getter that throws a readable error when API is not ready. */
    private fun api(): FeedFlowApi {
        return _api.value ?: throw IllegalStateException("请先在设置中配置服务器地址")
    }

    // ---- Unified call wrapper ---------------------------------------------

    /**
     * Wraps an API call in try/catch and converts exceptions into a failed
     * [ApiResponse] so callers can always pattern-match on `.success`.
     */
    private suspend fun <T> safeCall(block: suspend FeedFlowApi.() -> ApiResponse<T>): ApiResponse<T> {
        return try {
            api().block()
        } catch (e: IllegalStateException) {
            ApiResponse(success = false, error = e.message)
        } catch (e: Exception) {
            ApiResponse(success = false, error = e.localizedMessage ?: "网络请求失败")
        }
    }

    // ---- Articles ---------------------------------------------------------

    suspend fun getArticles(limit: Int = 20, offset: Int = 0): ApiResponse<List<Article>> =
        safeCall { getArticles(limit, offset) }

    suspend fun getUnreadArticles(limit: Int = 20, offset: Int = 0): ApiResponse<List<Article>> =
        safeCall { getUnreadArticles(limit, offset) }

    suspend fun getStarredArticles(limit: Int = 20, offset: Int = 0): ApiResponse<List<Article>> =
        safeCall { getStarredArticles(limit, offset) }

    suspend fun searchArticles(
        query: String,
        regex: Boolean = false,
        limit: Int = 20,
        offset: Int = 0,
    ): ApiResponse<List<Article>> = safeCall { searchArticles(query, regex, limit, offset) }

    suspend fun markArticleRead(id: String): ApiResponse<Article> =
        safeCall { markArticleRead(id) }

    suspend fun toggleArticleStar(id: String): ApiResponse<Article> =
        safeCall { toggleArticleStar(id) }

    // ---- Feeds ------------------------------------------------------------

    suspend fun getFeeds(): ApiResponse<List<Feed>> = safeCall { getFeeds() }

    suspend fun addFeed(url: String, folderId: String? = null): ApiResponse<Feed> =
        safeCall { addFeed(AddFeedRequest(url, folderId)) }

    suspend fun deleteFeed(id: String): ApiResponse<Unit> = safeCall { deleteFeed(id) }

    suspend fun refreshFeed(id: String): ApiResponse<Feed> = safeCall { refreshFeed(id) }

    suspend fun getFeedArticles(
        feedId: String,
        limit: Int = 20,
        offset: Int = 0,
    ): ApiResponse<List<Article>> = safeCall { getFeedArticles(feedId, limit, offset) }

    suspend fun markFeedAllRead(feedId: String): ApiResponse<Unit> =
        safeCall { markFeedAllRead(feedId) }

    // ---- Global refresh ---------------------------------------------------

    suspend fun refreshAll(): ApiResponse<Unit> = safeCall { refreshAll() }

    // ---- Stats ------------------------------------------------------------

    suspend fun getStats(): ApiResponse<Stats> = safeCall { getStats() }

    // ---- Settings ---------------------------------------------------------

    suspend fun getRemoteSettings(): ApiResponse<SettingsData> = safeCall { getSettings() }

    suspend fun updateRemoteSettings(settings: SettingsData): ApiResponse<SettingsData> =
        safeCall { updateSettings(settings) }

    // ---- Auth -------------------------------------------------------------

    suspend fun login(username: String, password: String): ApiResponse<LoginResponse> =
        safeCall { login(LoginRequest(username, password)) }

    // ---- Bangumi ----------------------------------------------------------

    suspend fun searchBangumi(query: String): ApiResponse<List<BangumiResult>> =
        safeCall { searchBangumi(query) }

    // ---- Folders ----------------------------------------------------------

    suspend fun getFolders(): ApiResponse<List<Folder>> = safeCall { getFolders() }
}
