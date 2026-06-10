package com.rusure.app.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rusure.app.data.local.entity.AppTargetConfig
import com.rusure.app.data.repository.RuSureRepository
import com.rusure.app.di.AppContainer
import com.rusure.app.domain.model.GateAction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Estado temporal (Draft) de edición de un objetivo. Refleja lo que el usuario está manipulando en
 * la UI SIN persistir nada: la base de datos solo se toca al pulsar "Guardar cambios".
 */
data class TargetConfigDraft(
    val initialTimerSeconds: Int,
    val continuousUsageLimitSeconds: Int,
    val reEntryTimerSeconds: Int,
    val enabled: Boolean,
    val entryAction: GateAction
)

/** Estado de pantalla de la configuración por objetivo. */
data class TargetConfigUiState(
    val loading: Boolean = true,
    val displayName: String = "",
    val draft: TargetConfigDraft? = null,
    val saving: Boolean = false
)

/**
 * ViewModel de edición de un único objetivo. Mantiene un Draft State local desacoplado de la base
 * de datos: las interacciones de la UI solo mutan el draft en memoria. La persistencia ocurre
 * EXCLUSIVAMENTE en [save], el único punto de escritura.
 *
 * [save] está deliberadamente aislado para poder interceptar el guardado en el futuro (añadir
 * demoras, pedir confirmación, o bloquear si se superó un límite diario de cambios) sin tocar la UI.
 */
class TargetConfigViewModel(
    private val repository: RuSureRepository,
    private val catalogKey: String
) : ViewModel() {

    companion object {
        fun factory(container: AppContainer, catalogKey: String): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { TargetConfigViewModel(container.repository, catalogKey) }
            }

        /** Límites del recordatorio de uso continuo, en minutos. */
        const val MIN_LIMIT_MINUTES = 5
        const val MAX_LIMIT_MINUTES = 60
    }

    /** Configuración original cargada; base sobre la que se aplica el draft al guardar. */
    private var original: AppTargetConfig? = null

    private val _uiState = MutableStateFlow(TargetConfigUiState())
    val uiState: StateFlow<TargetConfigUiState> = _uiState.asStateFlow()

    /** Señal one-shot tras un guardado exitoso, para que la pantalla vuelva al dashboard. */
    private val _savedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val savedEvents: SharedFlow<Unit> = _savedEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            val config = repository.getTarget(catalogKey)
            if (config == null) {
                _uiState.update { it.copy(loading = false) }
            } else {
                original = config
                _uiState.update {
                    it.copy(
                        loading = false,
                        displayName = config.displayName,
                        draft = TargetConfigDraft(
                            initialTimerSeconds = config.initialTimerSeconds,
                            continuousUsageLimitSeconds = config.continuousUsageLimitSeconds,
                            reEntryTimerSeconds = config.reEntryTimerSeconds,
                            enabled = config.enabled,
                            entryAction = config.entryAction
                        )
                    )
                }
            }
        }
    }

    // --- Mutaciones locales del draft (NO persisten) ---

    private fun updateDraft(transform: (TargetConfigDraft) -> TargetConfigDraft) {
        _uiState.update { state ->
            val draft = state.draft ?: return@update state
            state.copy(draft = transform(draft))
        }
    }

    fun setEnabled(enabled: Boolean) = updateDraft { it.copy(enabled = enabled) }

    fun setInitialTimerSeconds(seconds: Int) =
        updateDraft { it.copy(initialTimerSeconds = seconds) }

    /** Recibe minutos enteros y los almacena como segundos exactos (sin segundos sueltos). */
    fun setContinuousLimitMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(MIN_LIMIT_MINUTES, MAX_LIMIT_MINUTES)
        updateDraft { it.copy(continuousUsageLimitSeconds = clamped * 60) }
    }

    fun setReEntryTimerSeconds(seconds: Int) =
        updateDraft { it.copy(reEntryTimerSeconds = seconds) }

    fun setEntryAction(action: GateAction) = updateDraft { it.copy(entryAction = action) }

    // --- Persistencia explícita ---

    /**
     * ÚNICO punto de persistencia. Toma el draft actual, lo normaliza (uso continuo a minutos
     * enteros) y lo escribe en la base de datos. Cualquier interceptación futura del guardado debe
     * añadirse aquí.
     */
    fun save() {
        val draft = _uiState.value.draft ?: return
        val base = original ?: return
        if (_uiState.value.saving) return

        viewModelScope.launch {
            _uiState.update { it.copy(saving = true) }
            val snappedLimit =
                ((draft.continuousUsageLimitSeconds + 30) / 60).coerceAtLeast(1) * 60
            val updated = base.copy(
                enabled = draft.enabled,
                initialTimerSeconds = draft.initialTimerSeconds,
                continuousUsageLimitSeconds = snappedLimit,
                reEntryTimerSeconds = draft.reEntryTimerSeconds,
                entryAction = draft.entryAction
            )
            repository.upsertTarget(updated)
            original = updated
            _uiState.update { it.copy(saving = false) }
            _savedEvents.tryEmit(Unit)
        }
    }
}
