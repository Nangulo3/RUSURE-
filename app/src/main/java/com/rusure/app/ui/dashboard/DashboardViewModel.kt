package com.rusure.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rusure.app.data.repository.RuSureRepository
import com.rusure.app.di.AppContainer
import com.rusure.app.domain.model.GateAction
import com.rusure.app.domain.pause.PauseController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Modo de protección de un objetivo, que decide cómo se renderiza el cuerpo de su tarjeta:
 *
 * - [TIMER]: fricción con temporizador; la tarjeta muestra "Espera antes de entrar" e
 *   "Interrupción cada".
 * - [BLOCKED]: restricción total; la tarjeta muestra una variante compacta con "🔒 Bloqueado".
 */
enum class ProtectionMode {
    TIMER,
    BLOCKED
}

/**
 * Proyección de un objetivo para el Home: identidad, estado y un resumen de su configuración de
 * bloqueo (espera inicial e intervalo de interrupción). Deliberadamente NO contiene métricas de uso:
 * las estadísticas viven en la pantalla dedicada de estadísticas. El detalle editable vive en la
 * pantalla de configuración por objetivo, a la que la tarjeta navega como un todo.
 */
data class TargetDashboardItem(
    val catalogKey: String,
    val packageName: String,
    val displayName: String,
    val enabled: Boolean,
    /** Modo de protección: decide la variante visual de la tarjeta (temporizador vs. bloqueado). */
    val protectionMode: ProtectionMode,
    /** Segundos de la cuenta regresiva al abrir el objetivo ("Espera antes de entrar"). */
    val initialTimerSeconds: Int,
    /** Segundos de uso continuo antes de forzar una interrupción de reingreso ("Interrupción cada"). */
    val continuousUsageLimitSeconds: Int
)

/**
 * Resumen global del día (últimas 24h) mostrado en la cabecera "Hoy": tiempo total en apps
 * objetivo y número de aperturas evitadas (interrupciones acumuladas de la fricción).
 */
data class DailySummary(
    val totalActiveMillis: Long,
    val openingsAvoided: Int
)

/**
 * Estado del dashboard: resumen del día + lista de objetivos (solo lectura) y el tiempo restante
 * de la pausa global de protección ([pauseRemainingMillis], 0 = sin pausa activa), la única
 * acción disponible en esta pantalla además de navegar (ver [PauseController], D-009).
 */
data class DashboardUiState(
    val summary: DailySummary,
    val targets: List<TargetDashboardItem>,
    val pauseRemainingMillis: Long = 0L
) {
    companion object {
        val EMPTY = DashboardUiState(
            summary = DailySummary(totalActiveMillis = 0L, openingsAvoided = 0),
            targets = emptyList(),
            pauseRemainingMillis = 0L
        )
    }
}

/**
 * ViewModel del dashboard. Expone el estado de lectura combinando la configuración de cada objetivo
 * con su agregado de uso de 24h. Es deliberadamente independiente del estado de edición
 * ([com.rusure.app.ui.config.TargetConfigViewModel]) para mantener separados consulta y modificación.
 */
class DashboardViewModel(
    private val repository: RuSureRepository,
    private val pauseController: PauseController
) : ViewModel() {

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { DashboardViewModel(container.repository, container.pauseController) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val targetsAndSummary =
        repository.observeTargets().flatMapLatest { targets ->
            if (targets.isEmpty()) {
                flowOf(emptyList())
            } else {
                val now = System.currentTimeMillis()
                combine(
                    targets.map { config ->
                        // Las estadísticas se observan solo para alimentar el resumen "Hoy"; la
                        // tarjeta del objetivo en sí ya no muestra métricas.
                        repository.observeStats24h(config.catalogKey, now).map { stats ->
                            config to stats
                        }
                    }
                ) { combined -> combined.toList() }
            }
        }.map { pairs ->
            DailySummary(
                totalActiveMillis = pairs.sumOf { it.second.totalActiveMillis },
                openingsAvoided = pairs.sumOf { it.second.totalInterruptions }
            ) to pairs.map { (config, _) ->
                TargetDashboardItem(
                    catalogKey = config.catalogKey,
                    packageName = config.packageName,
                    displayName = config.displayName,
                    enabled = config.enabled,
                    protectionMode = when (config.entryAction) {
                        GateAction.BLOCK -> ProtectionMode.BLOCKED
                        GateAction.WAIT -> ProtectionMode.TIMER
                    },
                    initialTimerSeconds = config.initialTimerSeconds,
                    continuousUsageLimitSeconds = config.continuousUsageLimitSeconds
                )
            }
        }

    /** Cuenta atrás del tiempo restante de pausa, actualizada cada segundo; 0 = sin pausa activa. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pauseRemaining = pauseController.pausedUntilMillis.flatMapLatest { until ->
        flow {
            while (true) {
                val remaining = (until - System.currentTimeMillis()).coerceAtLeast(0L)
                emit(remaining)
                if (remaining <= 0L) break
                delay(1_000L)
            }
        }
    }

    val uiState: StateFlow<DashboardUiState> =
        combine(targetsAndSummary, pauseRemaining) { (summary, targets), pauseRemainingMillis ->
            DashboardUiState(
                summary = summary,
                targets = targets,
                pauseRemainingMillis = pauseRemainingMillis
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DashboardUiState.EMPTY
        )

    /** Inicia la pausa global de 5 minutos ("Pausar 5 min"). */
    fun onStartPause() = pauseController.pause()

    /** Cancela la pausa activa antes de tiempo ("Reanudar ahora"). */
    fun onEndPause() = pauseController.resume()
}
