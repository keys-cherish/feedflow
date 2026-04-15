package com.feedflow.app.download

/**
 * Abstraction for torrent download clients (qBittorrent, Aria2, Transmission).
 */
interface DownloadClient {
    /** Test whether the client is reachable and credentials are valid. */
    suspend fun testConnection(): Boolean

    /** Push a torrent URL to the client. Returns info hash or task id on success. */
    suspend fun addTorrent(torrentUrl: String, savePath: String?): Result<String>

    /** Get hashes/ids of all tasks tagged with "feedflow", used for dedup. */
    suspend fun getTaskIds(): Set<String>
}

data class DownloadConfig(
    val type: String = "qbittorrent",  // "qbittorrent" / "aria2" / "transmission"
    val host: String = "",              // "http://192.168.1.100:8080"
    val username: String = "",
    val password: String = "",
    val savePath: String = "",
    val autoDownload: Boolean = false,
)
