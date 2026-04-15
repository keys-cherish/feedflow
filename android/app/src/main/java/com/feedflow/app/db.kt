package com.feedflow.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ---------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------

@Entity(tableName = "feeds")
data class FeedEntity(
    @PrimaryKey val id: String,
    val title: String,
    val url: String,
    @ColumnInfo(name = "site_url") val siteUrl: String? = null,
    val description: String? = null,
    @ColumnInfo(name = "icon_url") val iconUrl: String? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    val tags: String? = null, // comma-separated tags
)

@Entity(
    tableName = "articles",
    indices = [Index("feed_id"), Index("published_at")],
    foreignKeys = [ForeignKey(
        entity = FeedEntity::class,
        parentColumns = ["id"],
        childColumns = ["feed_id"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class ArticleEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "feed_id") val feedId: String,
    val title: String,
    val url: String? = null,
    val author: String? = null,
    @ColumnInfo(name = "content_html") val contentHtml: String? = null,
    val summary: String? = null,
    @ColumnInfo(name = "thumbnail_url") val thumbnailUrl: String? = null,
    @ColumnInfo(name = "published_at") val publishedAt: Long = 0L,
    @ColumnInfo(name = "is_read") val isRead: Boolean = false,
    @ColumnInfo(name = "is_starred") val isStarred: Boolean = false,
    @ColumnInfo(name = "enclosure_url") val enclosureUrl: String? = null,
    @ColumnInfo(name = "content_length") val contentLength: Long = 0L,
)

// ---------------------------------------------------------------------------
// DAO
// ---------------------------------------------------------------------------

@Dao
interface FeedDao {
    @Query("SELECT * FROM feeds ORDER BY created_at DESC")
    fun allFeeds(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feeds ORDER BY created_at DESC")
    suspend fun allFeedsList(): List<FeedEntity>

    @Query("SELECT * FROM feeds WHERE id = :id")
    suspend fun getFeed(id: String): FeedEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFeed(feed: FeedEntity)

    // Safe update — does NOT trigger CASCADE delete on articles
    @Query("UPDATE feeds SET title = :title, last_error = :lastError, tags = :tags WHERE id = :id")
    suspend fun updateFeedMeta(id: String, title: String, lastError: String?, tags: String?)

    @Delete
    suspend fun deleteFeed(feed: FeedEntity)

    @Query("DELETE FROM feeds WHERE id = :id")
    suspend fun deleteFeedById(id: String)

    @Query("SELECT COUNT(*) FROM feeds")
    suspend fun feedCount(): Long

    @Query("UPDATE feeds SET tags = :tags WHERE id = :id")
    suspend fun updateTags(id: String, tags: String?)

    @Query("SELECT DISTINCT tags FROM feeds WHERE tags IS NOT NULL AND tags != ''")
    suspend fun allTags(): List<String>
}

@Dao
interface ArticleDao {
    @Query("SELECT * FROM articles ORDER BY published_at DESC LIMIT :limit OFFSET :offset")
    suspend fun allArticles(limit: Int = 20, offset: Int = 0): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE is_read = 0 ORDER BY published_at DESC LIMIT :limit OFFSET :offset")
    suspend fun unreadArticles(limit: Int = 20, offset: Int = 0): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE is_starred = 1 ORDER BY published_at DESC LIMIT :limit OFFSET :offset")
    suspend fun starredArticles(limit: Int = 20, offset: Int = 0): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE feed_id = :feedId ORDER BY published_at DESC LIMIT :limit OFFSET :offset")
    suspend fun articlesByFeed(feedId: String, limit: Int = 20, offset: Int = 0): List<ArticleEntity>

    @Query("SELECT * FROM articles WHERE title LIKE '%' || :q || '%' OR summary LIKE '%' || :q || '%' OR content_html LIKE '%' || :q || '%' ORDER BY published_at DESC LIMIT :limit OFFSET :offset")
    suspend fun searchArticles(q: String, limit: Int = 20, offset: Int = 0): List<ArticleEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(articles: List<ArticleEntity>)

    @Query("UPDATE articles SET is_read = 1 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE articles SET is_starred = NOT is_starred WHERE id = :id")
    suspend fun toggleStar(id: String)

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun getArticle(id: String): ArticleEntity?

    @Query("UPDATE articles SET is_read = 1 WHERE feed_id = :feedId")
    suspend fun markAllRead(feedId: String)

    @Query("SELECT COUNT(*) FROM articles")
    suspend fun articleCount(): Long

    @Query("SELECT COUNT(*) FROM articles WHERE is_read = 0")
    suspend fun unreadCount(): Long

    @Query("DELETE FROM articles WHERE feed_id = :feedId")
    suspend fun deleteByFeed(feedId: String)

    @Query("SELECT * FROM articles WHERE feed_id IN (:feedIds) ORDER BY published_at DESC LIMIT :limit OFFSET :offset")
    suspend fun articlesByFeeds(feedIds: List<String>, limit: Int = 20, offset: Int = 0): List<ArticleEntity>

    @Query("UPDATE articles SET thumbnail_url = :url WHERE id = :id AND thumbnail_url IS NULL")
    suspend fun updateThumbnail(id: String, url: String)

    @Query("UPDATE articles SET content_html = NULL WHERE is_read = 1 AND is_starred = 0 AND published_at < :cutoff AND content_html IS NOT NULL")
    suspend fun clearOldContent(cutoff: Long): Int

    @Query("DELETE FROM articles")
    suspend fun deleteAll()

    @Query("SELECT SUM(LENGTH(content_html)) FROM articles WHERE content_html IS NOT NULL")
    suspend fun totalContentSize(): Long?
}

// ---------------------------------------------------------------------------
// Bangumi Cache DAO
// ---------------------------------------------------------------------------

@Entity(tableName = "bangumi_cache")
data class BangumiCacheEntity(
    @PrimaryKey @ColumnInfo(name = "anime_name") val animeName: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    @ColumnInfo(name = "bgm_id") val bgmId: Int? = null,
    @ColumnInfo(name = "bgm_name") val bgmName: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    val summary: String? = null,
    @ColumnInfo(name = "eps_count") val epsCount: Int? = null,
    @ColumnInfo(name = "air_date") val airDate: String? = null,
    val rating: Float? = null,
)

@Dao
interface BangumiCacheDao {
    @Query("SELECT * FROM bangumi_cache WHERE anime_name = :name")
    suspend fun getCover(name: String): BangumiCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCover(entity: BangumiCacheEntity)

    @Query("DELETE FROM bangumi_cache WHERE created_at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("SELECT * FROM bangumi_cache")
    suspend fun getAll(): List<BangumiCacheEntity>
}

// ---------------------------------------------------------------------------
// Download History
// ---------------------------------------------------------------------------

@Entity(tableName = "download_history")
data class DownloadHistoryEntity(
    @PrimaryKey @ColumnInfo(name = "article_id") val articleId: String,
    @ColumnInfo(name = "torrent_url") val torrentUrl: String,
    @ColumnInfo(name = "downloaded_at") val downloadedAt: Long = System.currentTimeMillis(),
    val status: String = "sent", // sent / failed
)

@Dao
interface DownloadHistoryDao {
    @Query("SELECT EXISTS(SELECT 1 FROM download_history WHERE article_id = :articleId AND status = 'sent')")
    suspend fun isDownloaded(articleId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markDownloaded(entity: DownloadHistoryEntity)

    @Query("SELECT * FROM download_history ORDER BY downloaded_at DESC LIMIT :limit")
    suspend fun getRecentDownloads(limit: Int = 50): List<DownloadHistoryEntity>

    @Query("SELECT article_id FROM download_history WHERE status = 'sent'")
    suspend fun allDownloadedArticleIds(): List<String>
}

// ---------------------------------------------------------------------------
// Database
// ---------------------------------------------------------------------------

@Database(entities = [FeedEntity::class, ArticleEntity::class, BangumiCacheEntity::class, DownloadHistoryEntity::class], version = 5)
abstract class AppDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao
    abstract fun bangumiCacheDao(): BangumiCacheDao
    abstract fun downloadHistoryDao(): DownloadHistoryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN tags TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bangumi_cache (
                        anime_name TEXT NOT NULL PRIMARY KEY,
                        cover_url TEXT,
                        bgm_id INTEGER,
                        bgm_name TEXT,
                        created_at INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN enclosure_url TEXT DEFAULT NULL")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS download_history (
                        article_id TEXT NOT NULL PRIMARY KEY,
                        torrent_url TEXT NOT NULL,
                        downloaded_at INTEGER NOT NULL DEFAULT 0,
                        status TEXT NOT NULL DEFAULT 'sent'
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE articles ADD COLUMN content_length INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bangumi_cache ADD COLUMN summary TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE bangumi_cache ADD COLUMN eps_count INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE bangumi_cache ADD COLUMN air_date TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE bangumi_cache ADD COLUMN rating REAL DEFAULT NULL")
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "feedflow.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { INSTANCE = it }
            }
        }
    }
}
