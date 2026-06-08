package com.rusure.app.data.repository

import com.rusure.app.data.local.dao.AppTargetConfigDao
import com.rusure.app.data.local.dao.UsageSessionDao
import com.rusure.app.data.local.entity.AppTargetConfig
import com.rusure.app.data.local.entity.UsageSession
import com.rusure.app.domain.model.UsageStats
import kotlinx.coroutines.flow.Flow

/** Fachada única sobre la persistencia de RuSure. */
class RuSureRepository(
    private val targetDao: AppTargetConfigDao,
    private val sessionDao: UsageSessionDao
) {

    companion object {
        const val WINDOW_24H_MILLIS = 24L * 60L * 60L * 1000L
    }

    // --- Configuración de objetivos ---

    fun observeTargets(): Flow<List<AppTargetConfig>> = targetDao.observeAll()

    fun observeEnabledTargets(): Flow<List<AppTargetConfig>> = targetDao.observeEnabled()

    suspend fun getEnabledTargets(): List<AppTargetConfig> = targetDao.getEnabled()

    suspend fun getTarget(catalogKey: String): AppTargetConfig? =
        targetDao.getByCatalogKey(catalogKey)

    suspend fun upsertTarget(config: AppTargetConfig) = targetDao.upsert(config)

    // --- Sesiones de uso ---

    suspend fun startSession(catalogKey: String, packageName: String, nowMillis: Long): Long =
        sessionDao.insert(
            UsageSession(
                catalogKey = catalogKey,
                packageName = packageName,
                startEpochMillis = nowMillis
            )
        )

    suspend fun getOpenSession(catalogKey: String): UsageSession? =
        sessionDao.getOpenSession(catalogKey)

    suspend fun getSession(id: Long): UsageSession? = sessionDao.getById(id)

    suspend fun addActiveTime(sessionId: Long, deltaMillis: Long) {
        val session = sessionDao.getById(sessionId) ?: return
        sessionDao.update(
            session.copy(activeDurationMillis = session.activeDurationMillis + deltaMillis)
        )
    }

    suspend fun incrementInterruptions(sessionId: Long) {
        val session = sessionDao.getById(sessionId) ?: return
        sessionDao.update(session.copy(interruptions = session.interruptions + 1))
    }

    suspend fun endSession(sessionId: Long, nowMillis: Long) {
        val session = sessionDao.getById(sessionId) ?: return
        if (session.endEpochMillis != null) return
        sessionDao.update(session.copy(endEpochMillis = nowMillis))
    }

    // --- Estadísticas ---

    suspend fun getStats24h(catalogKey: String, nowMillis: Long): UsageStats =
        sessionDao.getStatsSince(catalogKey, nowMillis - WINDOW_24H_MILLIS)

    fun observeStats24h(catalogKey: String, nowMillis: Long): Flow<UsageStats> =
        sessionDao.observeStatsSince(catalogKey, nowMillis - WINDOW_24H_MILLIS)
}
