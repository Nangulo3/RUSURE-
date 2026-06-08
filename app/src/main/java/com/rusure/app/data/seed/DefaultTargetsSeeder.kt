package com.rusure.app.data.seed

import com.rusure.app.data.local.dao.AppTargetConfigDao
import com.rusure.app.data.local.entity.AppTargetConfig
import com.rusure.app.domain.catalog.TargetCatalog

/**
 * Siembra la configuración inicial a partir del catálogo curado la primera vez que se usa la app.
 * Todos los objetivos quedan deshabilitados hasta que el usuario los active en Ajustes.
 */
object DefaultTargetsSeeder {

    suspend fun seedIfEmpty(dao: AppTargetConfigDao) {
        if (dao.count() > 0) return

        val defaults = TargetCatalog.entries.map { entry ->
            AppTargetConfig(
                catalogKey = entry.catalogKey,
                packageName = entry.packageName,
                targetType = entry.targetType,
                sectionKey = entry.sectionKey,
                displayName = entry.displayName,
                enabled = false,
                initialTimerSeconds = entry.defaultInitialTimerSeconds,
                continuousUsageLimitSeconds = entry.defaultContinuousUsageLimitSeconds,
                reEntryTimerSeconds = entry.defaultReEntryTimerSeconds
            )
        }
        dao.insertAll(defaults)
    }
}
