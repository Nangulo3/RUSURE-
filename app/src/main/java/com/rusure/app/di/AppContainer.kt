package com.rusure.app.di

import android.content.Context
import androidx.room.Room
import com.rusure.app.data.local.RuSureDatabase
import com.rusure.app.data.repository.RuSureRepository
import com.rusure.app.domain.detection.TargetDetector
import com.rusure.app.domain.gate.GateCoordinator

/**
 * Contenedor de dependencias singletons del proceso (DI manual).
 *
 * Se usa DI manual en lugar de Hilt porque el plugin Gradle de Hilt aún depende de la API
 * `BaseExtension` de AGP, eliminada en AGP 9. Este contenedor cumple el mismo rol: una única
 * instancia compartida de base de datos, repositorio y colaboradores de dominio.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: RuSureDatabase by lazy {
        Room.databaseBuilder(appContext, RuSureDatabase::class.java, RuSureDatabase.NAME)
            // Migración real que redondea el límite de uso continuo a minutos enteros; el fallback
            // destructivo queda solo como red de seguridad para rutas de versión no contempladas.
            .addMigrations(RuSureDatabase.MIGRATION_1_2, RuSureDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    val appTargetConfigDao by lazy { database.appTargetConfigDao() }
    val usageSessionDao by lazy { database.usageSessionDao() }

    val repository: RuSureRepository by lazy {
        RuSureRepository(appTargetConfigDao, usageSessionDao)
    }

    val detector: TargetDetector by lazy { TargetDetector() }

    /** Singleton de proceso: el servicio y la Activity de fricción comparten esta instancia. */
    val gateCoordinator: GateCoordinator by lazy { GateCoordinator() }
}
