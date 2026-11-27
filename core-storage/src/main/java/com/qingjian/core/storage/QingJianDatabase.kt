package com.qingjian.core.storage

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface BookIndexDao {
  @Query("SELECT * FROM book_index ORDER BY updatedAt DESC")
  fun observeBooks(): Flow<List<BookIndexEntity>>

  @Query("SELECT * FROM book_index WHERE bookId = :bookId LIMIT 1")
  suspend fun getBook(bookId: String): BookIndexEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(book: BookIndexEntity)

  @Query("DELETE FROM book_index WHERE bookId = :bookId")
  suspend fun deleteBook(bookId: String)
}

@Dao
interface ChapterIndexDao {
  @Query("SELECT * FROM chapter_index WHERE bookId = :bookId ORDER BY chapterOrder ASC")
  fun observeChapters(bookId: String): Flow<List<ChapterIndexEntity>>

  @Query("SELECT * FROM chapter_index WHERE bookId = :bookId AND (title LIKE '%' || :query || '%' OR searchExcerpt LIKE '%' || :query || '%') ORDER BY chapterOrder ASC")
  suspend fun searchChapters(bookId: String, query: String): List<ChapterIndexEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(chapter: ChapterIndexEntity)

  @Query("DELETE FROM chapter_index WHERE bookId = :bookId")
  suspend fun clearForBook(bookId: String)
}

@Dao
interface ForeshadowIndexDao {
  @Query("SELECT * FROM foreshadow_anchor_index WHERE bookId = :bookId ORDER BY createdAt DESC")
  fun observeAnchors(bookId: String): Flow<List<ForeshadowAnchorEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(items: List<ForeshadowAnchorEntity>)

  @Query("DELETE FROM foreshadow_anchor_index WHERE bookId = :bookId")
  suspend fun clearForBook(bookId: String)
}

@Dao
interface CausalEdgeDao {
  @Query("SELECT * FROM causal_edge_index WHERE bookId = :bookId")
  fun observeEdges(bookId: String): Flow<List<CausalEdgeEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsertAll(items: List<CausalEdgeEntity>)

  @Query("DELETE FROM causal_edge_index WHERE bookId = :bookId")
  suspend fun clearForBook(bookId: String)
}

@Dao
interface PipelineRunDao {
  @Query("SELECT * FROM pipeline_runs WHERE bookId = :bookId ORDER BY updatedAt DESC")
  fun observeRuns(bookId: String): Flow<List<PipelineRunEntity>>

  @Query("SELECT * FROM pipeline_runs WHERE runId = :runId LIMIT 1")
  suspend fun getRun(runId: String): PipelineRunEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun upsert(run: PipelineRunEntity)
}

@Dao
interface AiUsageDao {
  @Query("SELECT * FROM ai_usage_logs ORDER BY timestamp DESC LIMIT 100")
  fun observeUsage(): Flow<List<AiUsageEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(log: AiUsageEntity)
}

@Dao
interface RestorePointDao {
  @Query("SELECT * FROM restore_points WHERE bookId = :bookId ORDER BY createdAt DESC")
  fun observeRestorePoints(bookId: String): Flow<List<RestorePointEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(point: RestorePointEntity)
}

@Database(
  entities = [
    BookIndexEntity::class,
    ChapterIndexEntity::class,
    ForeshadowAnchorEntity::class,
    CausalEdgeEntity::class,
    PipelineRunEntity::class,
    AiUsageEntity::class,
    RestorePointEntity::class,
  ],
  version = 1,
  exportSchema = false,
)
abstract class QingJianDatabase : RoomDatabase() {
  abstract fun bookIndexDao(): BookIndexDao
  abstract fun chapterIndexDao(): ChapterIndexDao
  abstract fun foreshadowIndexDao(): ForeshadowIndexDao
  abstract fun causalEdgeDao(): CausalEdgeDao
  abstract fun pipelineRunDao(): PipelineRunDao
  abstract fun aiUsageDao(): AiUsageDao
  abstract fun restorePointDao(): RestorePointDao

  companion object {
    @Volatile
    private var INSTANCE: QingJianDatabase? = null

    fun getInstance(context: Context): QingJianDatabase =
      INSTANCE ?: synchronized(this) {
        INSTANCE ?: Room.databaseBuilder(
          context.applicationContext,
          QingJianDatabase::class.java,
          "qingjian_index.db",
        ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
      }
  }
}
