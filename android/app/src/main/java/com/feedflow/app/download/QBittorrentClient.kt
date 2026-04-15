package com.feedflow.app.download

import com.feedflow.app.AppLogger
import com.feedflow.app.RssParser
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray

/**
 * qBittorrent WebUI API client.
 * - Auth: POST /api/v2/auth/login
 * - Add:  POST /api/v2/torrents/add (multipart, urls + savepath + tags)
 * - List: GET  /api/v2/torrents/info?tag=feedflow
 */
class QBittorrentClient(private val config: DownloadConfig) : DownloadClient {

    private val client = RssParser.getClient()
    private val baseUrl get() = config.host.trimEnd('/')

    private var cookie: String? = null

    private suspend fun login(): Boolean {
        val body = FormBody.Builder()
            .add("username", config.username)
            .add("password", config.password)
            .build()
        val request = Request.Builder()
            .url("$baseUrl/api/v2/auth/login")
            .post(body)
            .build()
        return try {
            val resp = client.newCall(request).execute()
            val text = resp.body?.string() ?: ""
            if (resp.isSuccessful && text.contains("Ok", ignoreCase = true)) {
                cookie = resp.header("set-cookie")?.split(";")?.firstOrNull()
                true
            } else {
                AppLogger.e("qBit login failed: $text")
                false
            }
        } catch (e: Exception) {
            AppLogger.e("qBit login error", e)
            false
        }
    }

    private fun buildRequest(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        cookie?.let { builder.header("Cookie", it) }
        return builder
    }

    override suspend fun testConnection(): Boolean {
        return login()
    }

    override suspend fun addTorrent(torrentUrl: String, savePath: String?): Result<String> {
        if (cookie == null && !login()) return Result.failure(Exception("登录失败"))

        val path = savePath?.ifBlank { null } ?: config.savePath.ifBlank { null }
        val bodyBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("urls", torrentUrl)
            .addFormDataPart("tags", "feedflow")
        if (path != null) bodyBuilder.addFormDataPart("savepath", path)

        val request = buildRequest("$baseUrl/api/v2/torrents/add")
            .post(bodyBuilder.build())
            .build()

        return try {
            val resp = client.newCall(request).execute()
            if (resp.isSuccessful) {
                AppLogger.i("qBit torrent added: $torrentUrl")
                Result.success(torrentUrl)
            } else {
                val msg = "qBit add failed: HTTP ${resp.code}"
                AppLogger.e(msg)
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            AppLogger.e("qBit add error", e)
            Result.failure(e)
        }
    }

    override suspend fun getTaskIds(): Set<String> {
        if (cookie == null && !login()) return emptySet()
        val request = buildRequest("$baseUrl/api/v2/torrents/info?tag=feedflow")
            .get().build()
        return try {
            val resp = client.newCall(request).execute()
            if (resp.isSuccessful) {
                val arr = JSONArray(resp.body?.string() ?: "[]")
                (0 until arr.length()).map { arr.getJSONObject(it).optString("hash", "") }
                    .filter { it.isNotBlank() }.toSet()
            } else emptySet()
        } catch (e: Exception) {
            AppLogger.e("qBit getTaskIds error", e)
            emptySet()
        }
    }
}
