package com.feedflow.app.download

import com.feedflow.app.AppLogger
import com.feedflow.app.RssParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transmission RPC client.
 * - torrent-add: push torrent URL with labels
 * - torrent-get: list tasks for dedup
 * - Handles 409 CSRF token handshake automatically
 */
class TransmissionClient(private val config: DownloadConfig) : DownloadClient {

    private val client = RssParser.getClient()
    private val rpcUrl get() = config.host.trimEnd('/') + "/transmission/rpc"
    private var sessionId: String? = null

    private fun buildRequest(body: JSONObject): Request {
        val builder = Request.Builder()
            .url(rpcUrl)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "FeedFlow/1.0")
        if (config.username.isNotBlank()) {
            val credentials = okhttp3.Credentials.basic(config.username, config.password)
            builder.header("Authorization", credentials)
        }
        sessionId?.let { builder.header("X-Transmission-Session-Id", it) }
        return builder.build()
    }

    private fun rpcCall(body: JSONObject, retry: Boolean = true): JSONObject? {
        return try {
            val resp = client.newCall(buildRequest(body)).execute()
            if (resp.code == 409 && retry) {
                sessionId = resp.header("X-Transmission-Session-Id")
                return rpcCall(body, retry = false)
            }
            if (resp.isSuccessful) {
                JSONObject(resp.body?.string() ?: "{}")
            } else {
                AppLogger.e("Transmission RPC failed: HTTP ${resp.code}")
                null
            }
        } catch (e: Exception) {
            AppLogger.e("Transmission RPC error", e)
            null
        }
    }

    override suspend fun testConnection(): Boolean {
        val body = JSONObject().apply {
            put("method", "session-get")
        }
        val result = rpcCall(body)
        return result?.optString("result") == "success"
    }

    override suspend fun addTorrent(torrentUrl: String, savePath: String?): Result<String> {
        val args = JSONObject().apply {
            put("filename", torrentUrl)
            val path = savePath?.ifBlank { null } ?: config.savePath.ifBlank { null }
            if (path != null) put("download-dir", path)
            put("labels", JSONArray().put("feedflow"))
        }
        val body = JSONObject().apply {
            put("method", "torrent-add")
            put("arguments", args)
        }

        val result = rpcCall(body)
        if (result?.optString("result") == "success") {
            val added = result.optJSONObject("arguments")
            val hash = added?.optJSONObject("torrent-added")?.optString("hashString")
                ?: added?.optJSONObject("torrent-duplicate")?.optString("hashString")
                ?: torrentUrl
            AppLogger.i("Transmission torrent added: $hash")
            return Result.success(hash)
        }
        val err = result?.optString("result") ?: "Unknown error"
        AppLogger.e("Transmission add failed: $err")
        return Result.failure(Exception(err))
    }

    override suspend fun getTaskIds(): Set<String> {
        val args = JSONObject().apply {
            put("fields", JSONArray().apply {
                put("hashString")
                put("labels")
            })
        }
        val body = JSONObject().apply {
            put("method", "torrent-get")
            put("arguments", args)
        }

        val result = rpcCall(body) ?: return emptySet()
        val torrents = result.optJSONObject("arguments")?.optJSONArray("torrents") ?: return emptySet()
        val ids = mutableSetOf<String>()
        for (i in 0 until torrents.length()) {
            val t = torrents.getJSONObject(i)
            val labels = t.optJSONArray("labels")
            val hasFeedflow = labels != null && (0 until labels.length()).any { labels.optString(it) == "feedflow" }
            if (hasFeedflow) {
                val hash = t.optString("hashString", "")
                if (hash.isNotBlank()) ids.add(hash)
            }
        }
        return ids
    }
}
