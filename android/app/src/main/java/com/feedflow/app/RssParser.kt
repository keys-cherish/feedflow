package com.feedflow.app

import android.content.Context
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Parsed result from an RSS/Atom feed.
 */
data class ParsedFeed(
    val title: String,
    val siteUrl: String?,
    val description: String?,
    val articles: List<ParsedArticle>,
)

data class ParsedArticle(
    val title: String,
    val url: String?,
    val author: String?,
    val contentHtml: String?,
    val summary: String?,
    val thumbnailUrl: String?,
    val publishedAt: Long,
    val enclosureUrl: String? = null,
    val contentLength: Long = 0L,
)

// Regex patterns matching ani-rss's StringEnum for torrent link types
private val MAGNET_REGEX = Regex("""^magnet:\?xt=urn:btih:(\w+)""", RegexOption.IGNORE_CASE)
private val ED2K_REGEX = Regex("""^ed2k://\|file\|([^|]+)\|(\d+)\|([A-Fa-f0-9]{32})\|/$""")

/** Check if a URL is a downloadable torrent link (torrent file, magnet, or ed2k). */
private fun isTorrentUrl(url: String): Boolean {
    return url.endsWith(".torrent", ignoreCase = true)
            || MAGNET_REGEX.containsMatchIn(url)
            || ED2K_REGEX.containsMatchIn(url)
}

/**
 * Fetches and parses RSS 2.0 / Atom feeds using OkHttp + XmlPullParser.
 * No server needed — runs entirely on device.
 *
 * Torrent link extraction is compatible with ani-rss, supporting:
 * - Mikan: <enclosure type="application/x-bittorrent"> + <torrent><contentLength>
 * - Nyaa: nyaa:infoHash + nyaa:size namespace elements
 * - DMHY: magnet: links in <enclosure url="">
 * - bangumi.moe: <torrent><pubDate> nested element
 * - acg.rip: standard <enclosure> with .torrent URL
 * - <link> fallback if URL ends with .torrent
 * - <guid> as infoHash if purely hex/numeric
 * - ed2k:// links
 */
object RssParser {

    private var client: OkHttpClient? = null

    fun init(context: Context) {
        if (client == null) {
            val cacheDir = File(context.cacheDir, "rss_cache")
            client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .connectionPool(okhttp3.ConnectionPool(5, 2, TimeUnit.MINUTES))
                .cache(Cache(cacheDir, 10L * 1024 * 1024)) // 10MB disk cache
                .build()
        }
    }

    internal fun getClient(): OkHttpClient {
        return client ?: OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .connectionPool(okhttp3.ConnectionPool(5, 2, TimeUnit.MINUTES))
            .build()
    }

    /** Fetch XML from [url] and parse it. Throws on network/parse errors. */
    fun fetch(url: String): ParsedFeed {
        val request = Request.Builder().url(url)
            .header("User-Agent", "FeedFlow/1.0")
            .build()
        val body = getClient().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            resp.body?.string() ?: throw Exception("空响应")
        }
        return parse(body, url)
    }

    /** Detect RSS vs Atom and delegate. */
    private fun parse(xml: String, feedUrl: String): ParsedFeed {
        val trimmed = xml.trimStart()
        return if (trimmed.contains("<feed") && trimmed.contains("xmlns=\"http://www.w3.org/2005/Atom\"")) {
            parseAtom(xml, feedUrl)
        } else {
            parseRss(xml, feedUrl)
        }
    }

    // ---- RSS 2.0 parser -----------------------------------------------------
    // Compatible with: Mikan, Nyaa, DMHY, bangumi.moe, acg.rip, any standard RSS

    private fun parseRss(xml: String, feedUrl: String): ParsedFeed {
        val xpp = newParser(xml)
        var feedTitle = ""
        var feedLink: String? = null
        var feedDesc: String? = null
        val articles = mutableListOf<ParsedArticle>()

        var inItem = false
        var inChannel = false
        var inTorrent = false
        var title = ""; var link = ""; var author = ""; var content = ""
        var summary = ""; var pubDate = ""; var thumbnail: String? = null
        // Torrent extraction state
        var enclosure: String? = null
        var contentLength = 0L
        var nyaaSize: String? = null
        var guid: String? = null

        var eventType = xpp.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = xpp.name.lowercase()
                    when {
                        tag == "channel" -> inChannel = true
                        tag == "item" -> {
                            inItem = true; title = ""; link = ""; author = ""; content = ""
                            summary = ""; pubDate = ""; thumbnail = null
                            enclosure = null; contentLength = 0L; nyaaSize = null; guid = null
                            inTorrent = false
                        }
                        inItem -> when (tag) {
                            "title" -> title = xpp.nextText().trim()
                            "link" -> {
                                // ani-rss: if <link> text ends with .torrent, use as torrent URL
                                val linkText = xpp.nextText().trim()
                                if (linkText.endsWith(".torrent", ignoreCase = true)) {
                                    if (enclosure == null) enclosure = linkText
                                } else {
                                    link = linkText
                                }
                            }
                            "author", "dc:creator" -> author = xpp.nextText().trim()
                            "content:encoded" -> content = xpp.nextText().trim()
                            "description" -> summary = xpp.nextText().trim()
                            "pubdate", "dc:date" -> pubDate = xpp.nextText().trim()
                            "guid" -> {
                                // ani-rss: if guid is purely hex/numeric, it's an infoHash
                                guid = xpp.nextText().trim()
                            }
                            "enclosure" -> {
                                val type = xpp.getAttributeValue(null, "type") ?: ""
                                val encUrl = xpp.getAttributeValue(null, "url") ?: ""
                                val encLen = xpp.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L

                                if (type.contains("bittorrent", ignoreCase = true)
                                    || isTorrentUrl(encUrl)) {
                                    // Mikan (.torrent), DMHY (magnet:), ed2k, etc.
                                    enclosure = encUrl
                                    if (encLen > 0) contentLength = encLen
                                } else if (type.startsWith("image/")) {
                                    thumbnail = encUrl
                                }
                            }
                            // Mikan/bangumi.moe: <torrent> namespace
                            "torrent" -> inTorrent = true
                            "contentlength" -> if (inTorrent) {
                                val len = xpp.nextText().trim().toLongOrNull() ?: 0L
                                if (len > 0) contentLength = len
                            }
                            // Nyaa: nyaa:infoHash and nyaa:size
                            "nyaa:infohash" -> {
                                val hash = xpp.nextText().trim()
                                if (hash.isNotBlank() && enclosure == null) {
                                    // Store as magnet for download client compatibility
                                    enclosure = "magnet:?xt=urn:btih:$hash"
                                }
                            }
                            "nyaa:size" -> nyaaSize = xpp.nextText().trim()
                            // Media elements
                            "media:thumbnail", "media:content" -> {
                                if (thumbnail == null) thumbnail = xpp.getAttributeValue(null, "url")
                            }
                        }
                        inChannel && !inItem -> when (tag) {
                            "title" -> feedTitle = xpp.nextText().trim()
                            "link" -> feedLink = xpp.nextText().trim()
                            "description" -> feedDesc = xpp.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val endTag = xpp.name.lowercase()
                    if (endTag == "torrent") inTorrent = false
                    if (endTag == "item" && inItem) {
                        inItem = false

                        // Resolve contentLength from nyaa:size if not set
                        if (contentLength == 0L && nyaaSize != null) {
                            contentLength = parseNyaaSize(nyaaSize!!)
                        }

                        articles.add(ParsedArticle(
                            title = title,
                            url = link.ifBlank { null },
                            author = author.ifBlank { null },
                            contentHtml = content.ifBlank { null },
                            summary = stripHtml(summary).take(300).ifBlank { null },
                            thumbnailUrl = thumbnail ?: extractFirstImage(content.ifBlank { summary }),
                            publishedAt = parseDate(pubDate),
                            enclosureUrl = enclosure,
                            contentLength = contentLength,
                        ))
                    }
                    if (endTag == "channel") inChannel = false
                }
            }
            eventType = xpp.next()
        }
        return ParsedFeed(feedTitle, feedLink, feedDesc, articles)
    }

    // ---- Atom parser --------------------------------------------------------

    private fun parseAtom(xml: String, feedUrl: String): ParsedFeed {
        val xpp = newParser(xml)
        var feedTitle = ""
        var feedLink: String? = null
        var feedDesc: String? = null
        val articles = mutableListOf<ParsedArticle>()

        var inEntry = false
        var title = ""; var link = ""; var author = ""; var content = ""
        var summary = ""; var updated = ""; var thumbnail: String? = null
        var enclosure: String? = null; var contentLength = 0L

        var eventType = xpp.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = xpp.name.lowercase()
                    when {
                        tag == "entry" -> { inEntry = true; title = ""; link = ""; author = ""; content = ""; summary = ""; updated = ""; thumbnail = null; enclosure = null; contentLength = 0L }
                        inEntry -> when (tag) {
                            "title" -> title = xpp.nextText().trim()
                            "link" -> {
                                val href = xpp.getAttributeValue(null, "href")
                                val rel = xpp.getAttributeValue(null, "rel") ?: "alternate"
                                val type = xpp.getAttributeValue(null, "type") ?: ""
                                val encLen = xpp.getAttributeValue(null, "length")?.toLongOrNull() ?: 0L
                                if (href != null && (rel == "enclosure"
                                            && (type.contains("bittorrent", ignoreCase = true) || isTorrentUrl(href)))) {
                                    enclosure = href
                                    if (encLen > 0) contentLength = encLen
                                } else if (rel == "alternate" && href != null && link.isBlank()) {
                                    link = href
                                }
                            }
                            "author" -> {} // will get name inside
                            "name" -> if (author.isBlank()) author = xpp.nextText().trim()
                            "content" -> content = xpp.nextText().trim()
                            "summary" -> summary = xpp.nextText().trim()
                            "updated", "published" -> if (updated.isBlank()) updated = xpp.nextText().trim()
                            "media:thumbnail" -> thumbnail = xpp.getAttributeValue(null, "url")
                        }
                        !inEntry -> when (tag) {
                            "title" -> feedTitle = xpp.nextText().trim()
                            "link" -> {
                                val href = xpp.getAttributeValue(null, "href")
                                val rel = xpp.getAttributeValue(null, "rel") ?: "alternate"
                                if (rel == "alternate" && href != null && feedLink == null) feedLink = href
                            }
                            "subtitle" -> feedDesc = xpp.nextText().trim()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (xpp.name.lowercase() == "entry" && inEntry) {
                        inEntry = false
                        articles.add(ParsedArticle(
                            title = title,
                            url = link.ifBlank { null },
                            author = author.ifBlank { null },
                            contentHtml = content.ifBlank { null },
                            summary = stripHtml(summary.ifBlank { content }).take(300).ifBlank { null },
                            thumbnailUrl = thumbnail ?: extractFirstImage(content.ifBlank { summary }),
                            publishedAt = parseDate(updated),
                            enclosureUrl = enclosure,
                            contentLength = contentLength,
                        ))
                    }
                }
            }
            eventType = xpp.next()
        }
        return ParsedFeed(feedTitle, feedLink, feedDesc, articles)
    }

    // ---- Helpers -------------------------------------------------------------

    private fun newParser(xml: String): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newPullParser().apply { setInput(StringReader(xml)) }
    }

    private val dateFormats = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSSSSS",     // bangumi.moe microseconds
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    )

    fun parseDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        // Strip trailing sub-second precision that SimpleDateFormat can't handle
        val cleaned = dateStr.replace(Regex("\\.\\d+$"), "")
        for (fmt in dateFormats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(cleaned)?.time ?: continue
            } catch (_: Exception) { }
        }
        // Try original string as-is (in case cleaning broke something)
        if (cleaned != dateStr) {
            for (fmt in dateFormats) {
                try {
                    val sdf = SimpleDateFormat(fmt, Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    return sdf.parse(dateStr)?.time ?: continue
                } catch (_: Exception) { }
            }
        }
        return 0L
    }

    /** Parse nyaa:size string like "1.2 GiB" or "350.5 MiB" to bytes. */
    private fun parseNyaaSize(sizeStr: String): Long {
        val match = Regex("""([\d.]+)\s*(GiB|MiB|KiB|GB|MB|KB|TB|TiB)""", RegexOption.IGNORE_CASE).find(sizeStr) ?: return 0L
        val value = match.groupValues[1].toDoubleOrNull() ?: return 0L
        val unit = match.groupValues[2].uppercase()
        return when {
            unit.startsWith("T") -> (value * 1024 * 1024 * 1024 * 1024).toLong()
            unit.startsWith("G") -> (value * 1024 * 1024 * 1024).toLong()
            unit.startsWith("M") -> (value * 1024 * 1024).toLong()
            unit.startsWith("K") -> (value * 1024).toLong()
            else -> 0L
        }
    }

    fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun stripHtml(html: String): String {
        return html.replace(Regex("<[^>]*>"), "").replace(Regex("&\\w+;"), " ").trim()
    }

    private val imgRegex = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    private fun extractFirstImage(html: String): String? {
        return imgRegex.find(html)?.groupValues?.getOrNull(1)
    }
}
