package com.rusure.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.rusure.app.domain.model.GateAction
import com.rusure.app.domain.model.TargetType

/**
 * Configuración persistida y editable por el usuario para cada objetivo (app global o sección).
 *
 * Los criterios de detección (viewIds / content-descriptions) NO viven aquí: residen en
 * [com.rusure.app.domain.catalog.TargetCatalog], indexados por [catalogKey]. Esta tabla solo
 * guarda los parámetros que el usuario puede ajustar, de modo que actualizar los matchers de
 * una app no requiera migraciones de base de datos.
 */
@Entity(
    tableName = "app_target_config",
    indices = [Index(value = ["catalogKey"], unique = true)]
)
data class AppTargetConfig(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    /** Clave estable que enlaza con la entrada del catálogo (ej. "instagram_reels"). */
    val catalogKey: String,

    val packageName: String,

    val targetType: TargetType,

    /** Identificador lógico de la sección (ej. "reels", "shorts"); null para APP_GLOBAL. */
    val sectionKey: String? = null,

    val displayName: String,

    /** Si el objetivo está activo. Por defecto deshabilitado hasta que el usuario lo active. */
    val enabled: Boolean = false,

    /** Segundos de la cuenta regresiva al abrir el objetivo. */
    val initialTimerSeconds: Int,

    /** Segundos de uso continuo permitido antes de forzar un nuevo gate de reingreso. */
    val continuousUsageLimitSeconds: Int,

    /** Segundos de la cuenta regresiva del gate de reingreso. */
    val reEntryTimerSeconds: Int,

    /** Acción al intentar entrar al objetivo: mostrar pantalla de espera o bloquear por completo. */
    val entryAction: GateAction = GateAction.WAIT
)
