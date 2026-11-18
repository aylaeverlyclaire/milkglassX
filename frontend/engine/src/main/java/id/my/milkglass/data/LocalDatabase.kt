package id.my.milkglass.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val episodeId: Int,
    val animeId: Int,
    val episodeNumber: Int,
    val episodeTitle: String,
    val animeTitle: String,
    val posterUrl: String,
    val progressMs: Long,
    val durationMs: Long,
    val updatedAtEpochSec: Long
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val animeId: Int,
    val title: String,
    val posterUrl: String,
    val score: Double,
    val status: Int,
    val pinned: Boolean = false,
    val updatedAtEpochSec: Long
)

@Dao
interface MilkglassLocalDao {

    @Query("SELECT * FROM watch_history ORDER BY updatedAtEpochSec DESC LIMIT :limit")
    fun observeRecentHistory(limit: Int = 50): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE episodeId = :episodeId LIMIT 1")
    suspend fun getEpisodeHistory(episodeId: Int): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(entity: WatchHistoryEntity)

    @Query("DELETE FROM watch_history WHERE episodeId = :episodeId")
    suspend fun deleteHistory(episodeId: Int)

    @Query("SELECT * FROM bookmarks ORDER BY pinned DESC, updatedAtEpochSec DESC")
    fun observeBookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bookmarks WHERE animeId = :animeId)")
    fun observeIsBookmarked(animeId: Int): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmark(entity: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE animeId = :animeId")
    suspend fun deleteBookmark(animeId: Int)
}

@Database(
    entities = [WatchHistoryEntity::class, BookmarkEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MilkglassLocalDatabase : RoomDatabase() {
    abstract fun localDao(): MilkglassLocalDao
}
