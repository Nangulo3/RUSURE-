package com.rusure.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rusure.app.data.local.dao.AppTargetConfigDao
import com.rusure.app.data.local.dao.UsageSessionDao
import com.rusure.app.data.local.entity.AppTargetConfig
import com.rusure.app.data.local.entity.UsageSession

@Database(
    entities = [AppTargetConfig::class, UsageSession::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class RuSureDatabase : RoomDatabase() {
    abstract fun appTargetConfigDao(): AppTargetConfigDao
    abstract fun usageSessionDao(): UsageSessionDao

    companion object {
        const val NAME = "rusure.db"

        /**
         * Migración de datos: normaliza la configuración previa de uso continuo redondeando
         * cualquier valor con segundos (ej. 5 m 30 s = 330 s) al minuto entero más cercano y
         * garantizando un mínimo de 1 minuto. A partir de aquí la columna solo contiene múltiplos
         * exactos de 60 segundos.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE app_target_config SET continuousUsageLimitSeconds = " +
                        "MAX(60, CAST(ROUND(continuousUsageLimitSeconds / 60.0) * 60 AS INTEGER))"
                )
            }
        }

        /**
         * Migración de esquema: añade la columna [AppTargetConfig.entryAction] (acción al intentar
         * entrar). Los objetivos preexistentes adoptan el comportamiento por defecto "WAIT"
         * (mostrar pantalla de espera), idéntico al que tenían antes de existir esta opción.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_target_config " +
                        "ADD COLUMN entryAction TEXT NOT NULL DEFAULT 'WAIT'"
                )
            }
        }
    }
}
