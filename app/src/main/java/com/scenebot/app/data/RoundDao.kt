package com.scenebot.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RoundDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRound(round: RoundEntity): Long

    @Update
    suspend fun updateRound(round: RoundEntity)

    @Query("SELECT * FROM rounds ORDER BY roundId DESC LIMIT 1")
    suspend fun getLatestRound(): RoundEntity?

    @Query("SELECT * FROM rounds ORDER BY roundId DESC LIMIT :limit")
    suspend fun getRecentRounds(limit: Int): List<RoundEntity>

    @Query("SELECT * FROM rounds ORDER BY roundId DESC")
    fun getAllRoundsFlow(): Flow<List<RoundEntity>>

    @Query("SELECT COUNT(*) FROM rounds")
    suspend fun getTotalRoundsCount(): Int

    @Query("SELECT * FROM rounds WHERE fingerprint = :fingerprint ORDER BY roundId DESC LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): RoundEntity?

    @Query("SELECT * FROM rounds WHERE sequence LIKE '%' || :query || '%' ORDER BY roundId DESC")
    suspend fun searchRounds(query: String): List<RoundEntity>

    @Query("DELETE FROM rounds")
    suspend fun clearAllRounds()
}
