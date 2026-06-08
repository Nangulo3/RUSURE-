package com.rusure.app.domain.model

/**
 * Métricas agregadas de uso de un target en una ventana temporal (típicamente 24h).
 * Proyección directa de la consulta agregada de [com.rusure.app.data.local.dao.UsageSessionDao].
 */
data class UsageStats(
    val openings: Int,
    val totalActiveMillis: Long,
    val lastUsedEpochMillis: Long?,
    val totalInterruptions: Int
) {
    companion object {
        val EMPTY = UsageStats(
            openings = 0,
            totalActiveMillis = 0L,
            lastUsedEpochMillis = null,
            totalInterruptions = 0
        )
    }
}
