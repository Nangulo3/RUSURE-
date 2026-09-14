package com.rusure.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.rusure.app.data.local.entity.UsageSession
import com.rusure.app.domain.model.UsageStats
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageSessionDao {

    @Insert
    suspend fun insert(session: UsageSession): Long

    @Update
    suspend fun update(session: UsageSession)

    @Query("SELECT * FROM usage_session WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): UsageSession?

    /** Sesión abierta (sin endEpochMillis) más reciente para un objetivo, si existe. */
    @Query(
        """
        SELECT * FROM usage_session
        WHERE catalogKey = :catalogKey AND endEpochMillis IS NULL
        ORDER BY startEpochMillis DESC
        LIMIT 1
        """
    )
    suspend fun getOpenSession(catalogKey: String): UsageSession?

    /** Estadísticas agregadas para un objetivo desde [sinceEpochMillis] (suspend, uso puntual). */
    @Query(
        """
        SELECT
            COUNT(*) AS openings,
            COALESCE(SUM(activeDurationMillis), 0) AS totalActiveMillis,
            MAX(startEpochMillis) AS lastUsedEpochMillis,
            COALESCE(SUM(interruptions), 0) AS totalInterruptions
        FROM usage_session
        WHERE catalogKey = :catalogKey AND startEpochMillis >= :sinceEpochMillis
        """
    )
    suspend fun getStatsSince(catalogKey: String, sinceEpochMillis: Long): UsageStats

    /** Versión reactiva de las estadísticas agregadas. */
    @Query(
        """
        SELECT
            COUNT(*) AS openings,
            COALESCE(SUM(activeDurationMillis), 0) AS totalActiveMillis,
            MAX(startEpochMillis) AS lastUsedEpochMillis,
            COALESCE(SUM(interruptions), 0) AS totalInterruptions
        FROM usage_session
        WHERE catalogKey = :catalogKey AND startEpochMillis >= :sinceEpochMillis
        """
    )
    fun observeStatsSince(catalogKey: String, sinceEpochMillis: Long): Flow<UsageStats>

    /** Todas las sesiones desde [sinceEpochMillis] (orden ascendente) para agregación en memoria. */
    @Query(
        "SELECT * FROM usage_session WHERE startEpochMillis >= :sinceEpochMillis " +
            "ORDER BY startEpochMillis ASC"
    )
    fun observeSessionsSince(sinceEpochMillis: Long): Flow<List<UsageSession>>

    @Query("UPDATE usage_session SET activeDurationMillis = activeDurationMillis + :deltaMillis WHERE id = :id")
    suspend fun addActiveTime(id: Long, deltaMillis: Long)

    @Query("UPDATE usage_session SET interruptions = interruptions + 1 WHERE id = :id")
    suspend fun incrementInterruptions(id: Long)

    @Query("UPDATE usage_session SET cancelledAccesses = cancelledAccesses + 1 WHERE id = :id")
    suspend fun incrementCancelled(id: Long)

    @Query("UPDATE usage_session SET endEpochMillis = :nowMillis WHERE id = :id AND endEpochMillis IS NULL")
    suspend fun endSession(id: Long, nowMillis: Long)
}
