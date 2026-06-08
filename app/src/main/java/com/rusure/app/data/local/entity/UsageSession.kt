package com.rusure.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Una fila por apertura/sesión de un objetivo. Es la base de las estadísticas:
 * - aperturas = número de filas en la ventana de tiempo
 * - tiempo de uso = suma de [activeDurationMillis]
 * - último uso = máximo [startEpochMillis]
 * - interrupciones = suma de [interruptions]
 */
@Entity(
    tableName = "usage_session",
    indices = [Index(value = ["catalogKey"]), Index(value = ["startEpochMillis"])]
)
data class UsageSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val catalogKey: String,

    val packageName: String,

    val startEpochMillis: Long,

    /** null mientras la sesión sigue abierta (objetivo en primer plano). */
    val endEpochMillis: Long? = null,

    /** Tiempo activo real acumulado en la sesión. */
    val activeDurationMillis: Long = 0L,

    /** Cuántas veces se forzó la pantalla intermedia durante esta sesión. */
    val interruptions: Int = 0
)
