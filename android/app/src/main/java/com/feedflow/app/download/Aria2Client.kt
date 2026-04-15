package com.feedflow.app.download

import com.feedflow.app.AppLogger
import com.feedflow.app.RssParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Aria2 JSON-RPC client.
 * - addUri: push torrent/magnet URL
 * - tellActive/tellWaiting/tellStopped: list tasks for dedup
 */
class Aria2Client(private val config: DownloadConfig) : DownloadClient {

    private val client = RssParser.getClient()
    private val rpcUrl get() = config.host.trimEnd('/') + "/jsonrpc"
    private val secret get() = if (config.password.isNotBlank()) "token:${config.password}" else null

    private fun rpcCall(method: String, params: JSONArray): JSONObject? {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", UUID.randomUUID().toString())
            put("method", method)
            put("params", params)
        }
        val request = Request.Builder()
            .url(rpcUrl)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val resp = client.newCall(request).execute()
            if (resp.isSuccessful) {
                JSONObject(resp.body?.string() ?: "{}")
            } else null
        } catch (e: Exception) {
            AppLogger.e("Aria2 RPC error ($method)", e)
            null
        }
    }

    override suspend fun testConnection(): Boolean {
        val params = JSONArray()
        secret?.let { params.put(it) }
        val result = rpcCall("aria2.getVersion", params)
        return result?.has("result") == true
    }

    override suspend fun addTorrent(torrentUrl: String, savePath: String?): Result<String> {
        val params = JSONArray()
        secret?.let { params.put(it) }
        params.put(JSONArray().put(torrentUrl)) // uris array
        val options = JSONObject()
        val path = savePath?.ifBlank { null } ?: config.savePath.ifBlank { null }
        if (path != null) options.put("dir", path)
        params.put(options)

        val result = rpcCall("aria2.addUri", params)
        val gid = result?.optString("result")
        return if (!gid.isNullOrBlank()) {
            AppLogger.i("Aria2 task added: gid=$gid url=$torrentUrl")
            Result.success(gid)
        } else {
            val err = result?.optJSONObject("error")?.optString("message") ?: "Unknown error"
            AppLogger.e("Aria2 addUri failed: $err")
            Result.failure(Exception(err))
        }
    }

    override suspend fun getTaskIds(): Set<String> {
        val ids = mutableSetOf<String>()
        for (method in listOf("aria2.tellActive", "aria2.tellWaiting", "aria2.tellStopped")) {
            val params = JSONArray()
            secret?.let { params.put(it) }
            if (method != "aria2.tellActive") {
                params.put(0)  // offset
                params.put(100) // num
            }
            val result = rpcCall(method, params)
            val arr = result?.optJSONArray("result") ?: continue
            for (i in 0 until arr.length()) {
                val hash = arr.getJSONObject(i).optString("infoHash", "")
                if (hash.isNotBlank()) ids.add(hash)
            }
        }
        return ids
    }
}
