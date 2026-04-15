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
)

/**
 * Fetches and parses RSS 2.0 / Atom feeds using OkHttp + XmlPullParser.
 * No server needed — runs entirely on device.
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

    private fun parseRss(xml: String, feedUrl: String): ParsedFeed {
        val xpp = newParser(xml)
        var feedTitle = ""
        var feedLink: String? = null
        var feedDesc: String? = null
        val articles = mutableListOf<ParsedArticle>()

        var inItem = false
        var inChannel = false
        var title = ""; var link = ""; var author = ""; var content = ""
        var summary = ""; var pubDate = ""; var thumbnail: String? = null

        var eventType = xpp.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = xpp.name.lowercase()
                    when {
                        tag == "channel" -> inChannel = true
                        tag == "item" -> { inItem = true; title = ""; link = ""; author = ""; content = ""; summary = ""; pubDate = ""; thumbnail = null }
                        inItem -> when (tag) {
                            "title" -> title = xpp.nextText().trim()
                            "link" -> link = xpp.nextText().trim()
                            "author", "dc:creator" -> author = xpp.nextText().trim()
                            "content:encoded" -> content = xpp.nextText().trim()
                            "description" -> summary = xpp.nextText().trim()
                            "pubdate", "dc:date" -> pubDate = xpp.nextText().trim()
                            "enclosure" -> {
                                val type = xpp.getAttributeValue(null, "type") ?: ""
                                if (type.startsWith("image/")) thumbnail = xpp.getAttributeValue(null, "url")
                            }
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
                    if (xpp.name.lowercase() == "item" && inItem) {
                        inItem = false
                        articles.add(ParsedArticle(
                            title = title,
                            url = link.ifBlank { null },
                            author = author.ifBlank { null },
                            contentHtml = content.ifBlank { null },
                            summary = stripHtml(summary).take(300).ifBlank { null },
                            thumbnailUrl = thumbnail ?: extractFirstImage(content.ifBlank { summary }),
                            publishedAt = parseDate(pubDate),
                        ))
                    }
                    if (xpp.name.lowercase() == "channel") inChannel = false
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

        var eventType = xpp.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = xpp.name.lowercase()
                    when {
                        tag == "entry" -> { inEntry = true; title = ""; link = ""; author = ""; content = ""; summary = ""; updated = ""; thumbnail = null }
                        inEntry -> when (tag) {
                            "title" -> title = xpp.nextText().trim()
                            "link" -> {
                                val href = xpp.getAttributeValue(null, "href")
                                val rel = xpp.getAttributeValue(null, "rel") ?: "alternate"
                                if (rel == "alternate" && href != null && link.isBlank()) link = href
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
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd",
    )

    fun parseDate(dateStr: String): Long {
        if (dateStr.isBlank()) return 0L
        for (fmt in dateFormats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(dateStr)?.time ?: continue
            } catch (_: Exception) { }
        }
        return 0L
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
