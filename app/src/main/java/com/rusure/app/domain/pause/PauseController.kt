package com.rusure.app.domain.pause

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Pausa global de la protección (ver `docs/DECISIONS.md` D-009): un único instante "pausado hasta
 * X" (epoch millis) que el servicio consulta antes de aplicar cualquier fricción. Deliberadamente
 * NO modifica ningún [com.rusure.app.data.local.entity.AppTargetConfig]: como la configuración de
 * cada objetivo nunca se toca, "volver a la configuración anterior" al terminar la pausa es
 * automático y no hay nada que restaurar.
 *
 * Persistido en [SharedPreferences] (sin migración de Room) para sobrevivir a la muerte del
 * proceso y a un reinicio del dispositivo. Singleton de proceso: el servicio y la UI del
 * dashboard comparten esta misma instancia (ver [com.rusure.app.di.AppContainer]).
 */
class PauseController(private val prefs: SharedPreferences) {

    private val _pausedUntilMillis = MutableStateFlow(prefs.getLong(KEY_PAUSED_UNTIL, 0L))

    /** Epoch millis hasta el que la protección está pausada; 0 = sin pausa activa. */
    val pausedUntilMillis: StateFlow<Long> = _pausedUntilMillis

    /** true si, en [now], la pausa global sigue vigente. */
    fun isPaused(now: Long = System.currentTimeMillis()): Boolean = now < pausedUntilMillis.value

    /** Inicia (o reinicia) la pausa global por [PAUSE_DURATION_MILLIS] a partir de ahora. */
    fun pause() {
        val until = System.currentTimeMillis() + PAUSE_DURATION_MILLIS
        prefs.edit { putLong(KEY_PAUSED_UNTIL, until) }
        _pausedUntilMillis.value = until
    }

    /** Cancela la pausa activa ("Reanudar ahora"): la protección vuelve a aplicarse de inmediato. */
    fun resume() {
        prefs.edit { putLong(KEY_PAUSED_UNTIL, 0L) }
        _pausedUntilMillis.value = 0L
    }

    companion object {
        private const val KEY_PAUSED_UNTIL = "paused_until_millis"

        /** Duración fija de la pausa global: 5 minutos. */
        const val PAUSE_DURATION_MILLIS = 5 * 60_000L
    }
}
