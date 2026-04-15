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

    @Query("UPDATE articles SET thumbnail_url = :url WHERE id = :id AND thumbnail_url IS NULL")
    suspend fun updateThumbnail(id: String, url: String)
}

// ---------------------------------------------------------------------------
// Database
// ---------------------------------------------------------------------------

@Database(entities = [FeedEntity::class, ArticleEntity::class], version = 2)
abstract class AppDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE feeds ADD COLUMN tags TEXT DEFAULT NULL")
            }
        }

        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "feedflow.db",
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }
    }
}
