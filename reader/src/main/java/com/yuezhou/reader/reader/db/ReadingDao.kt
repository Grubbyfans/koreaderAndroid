package com.yuezhou.reader.reader.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "reading_profile")
data class ReadingProfile(
    @PrimaryKey val bookId: String,
    val rating: Int = 0,
    val status: String = "在读",
    val firstRead: Long?,
    val lastRead: Long,
)

@Entity(tableName = "reading_session", indices = [Index("bookId")])
data class ReadingSession(
    @PrimaryKey val id: String,
    val bookId: String,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val startProgress: Double?,
    val endProgress: Double?,
)

@Dao
interface ReadingDao {
    @Query("SELECT * FROM reading_profile ORDER BY lastRead DESC")
    fun profiles(): Flow<List<ReadingProfile>>
    @Query("SELECT * FROM reading_session WHERE bookId = :bookId ORDER BY startTime")
    fun sessions(bookId: String): Flow<List<ReadingSession>>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProfile(profile: ReadingProfile)
    @Query("UPDATE reading_profile SET firstRead = COALESCE(firstRead, :at), lastRead = MAX(lastRead, :at) WHERE bookId = :bookId")
    suspend fun begin(bookId: String, at: Long)
    @Query("UPDATE reading_profile SET lastRead = MAX(lastRead, :at) WHERE bookId = :bookId")
    suspend fun touch(bookId: String, at: Long)
    @Query("UPDATE reading_profile SET rating = :rating WHERE bookId = :bookId")
    suspend fun rate(bookId: String, rating: Int)
    @Query("UPDATE reading_profile SET status = :status WHERE bookId = :bookId")
    suspend fun status(bookId: String, status: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSession(session: ReadingSession)
}
