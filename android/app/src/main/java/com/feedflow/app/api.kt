package com.feedflow.app

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface covering the full FeedFlow REST API surface.
 *
 * Every endpoint returns the generic [ApiResponse] wrapper so the caller can
 * check `.success` / `.error` uniformly.
 */
interface FeedFlowApi {

    // ----- Articles --------------------------------------------------------

    @GET("/api/articles")
    suspend fun getArticles(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): ApiResponse<List<Article>>

    @GET("/api/articles/unread")
    suspend fun getUnreadArticles(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): ApiResponse<List<Article>>

    @GET("/api/articles/starred")
    suspend fun getStarredArticles(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): ApiResponse<List<Article>>

    @GET("/api/articles/search")
    suspend fun searchArticles(
        @Query("q") query: String,
        @Query("regex") regex: Boolean = false,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): ApiResponse<List<Article>>

    @PUT("/api/articles/{id}/read")
    suspend fun markArticleRead(
        @Path("id") articleId: String,
    ): ApiResponse<Article>

    @PUT("/api/articles/{id}/star")
    suspend fun toggleArticleStar(
        @Path("id") articleId: String,
    ): ApiResponse<Article>

    // ----- Feeds -----------------------------------------------------------

    @GET("/api/feeds")
    suspend fun getFeeds(): ApiResponse<List<Feed>>

    @POST("/api/feeds")
    suspend fun addFeed(
        @Body request: AddFeedRequest,
    ): ApiResponse<Feed>

    @DELETE("/api/feeds/{id}")
    suspend fun deleteFeed(
        @Path("id") feedId: String,
    ): ApiResponse<Unit>

    @POST("/api/feeds/{id}/refresh")
    suspend fun refreshFeed(
        @Path("id") feedId: String,
    ): ApiResponse<Feed>

    @GET("/api/feeds/{id}/articles")
    suspend fun getFeedArticles(
        @Path("id") feedId: String,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): ApiResponse<List<Article>>

    @POST("/api/feeds/{id}/read-all")
    suspend fun markFeedAllRead(
        @Path("id") feedId: String,
    ): ApiResponse<Unit>

    // ----- Global refresh --------------------------------------------------

    @POST("/api/refresh")
    suspend fun refreshAll(): ApiResponse<Unit>

    // ----- Stats -----------------------------------------------------------

    @GET("/api/stats")
    suspend fun getStats(): ApiResponse<Stats>

    // ----- Settings --------------------------------------------------------

    @GET("/api/settings")
    suspend fun getSettings(): ApiResponse<SettingsData>

    @PUT("/api/settings")
    suspend fun updateSettings(
        @Body settings: SettingsData,
    ): ApiResponse<SettingsData>

    // ----- Auth ------------------------------------------------------------

    @POST("/api/auth/login")
    suspend fun login(
        @Body request: LoginRequest,
    ): ApiResponse<LoginResponse>

    // ----- Bangumi ---------------------------------------------------------

    @GET("/api/bangumi/search")
    suspend fun searchBangumi(
        @Query("q") query: String,
    ): ApiResponse<List<BangumiResult>>

    // ----- Folders ---------------------------------------------------------

    @GET("/api/folders")
    suspend fun getFolders(): ApiResponse<List<Folder>>

    // ----- OPML ------------------------------------------------------------

    @Multipart
    @POST("/api/opml/import")
    suspend fun importOpml(
        @Part file: MultipartBody.Part,
    ): ApiResponse<Unit>

    @GET("/api/opml/export")
    suspend fun exportOpml(): ResponseBody
}
