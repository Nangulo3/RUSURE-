package com.rusure.app.ui.interruption

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rusure.app.di.AppContainer
import com.rusure.app.data.repository.RuSureRepository
import com.rusure.app.domain.gate.GateCoordinator
import com.rusure.app.domain.gate.GateDecision
import com.rusure.app.domain.gate.GateMode
import com.rusure.app.domain.model.UsageStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InterruptionUiState(
    val valid: Boolean = true,
    val displayName: String = "",
    val mode: GateMode = GateMode.INITIAL,
    val totalSeconds: Int = 0,
    val remainingSeconds: Int = 0,
    val stats: UsageStats = UsageStats.EMPTY
) {
    /** El botón "Continuar" permanece deshabilitado hasta que la cuenta llega a 0. */
    val canContinue: Boolean get() = remainingSeconds <= 0

    val progress: Float
        get() = if (totalSeconds <= 0) 1f else 1f - (remainingSeconds.toFloat() / totalSeconds.toFloat())
}

class InterruptionViewModel(
    private val repository: RuSureRepository,
    private val gateCoordinator: GateCoordinator
) : ViewModel() {

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                InterruptionViewModel(container.repository, container.gateCoordinator)
            }
        }
    }

    private val request = gateCoordinator.consumeRequest()

    private val _uiState = MutableStateFlow(InterruptionUiState())
    val uiState: StateFlow<InterruptionUiState> = _uiState.asStateFlow()

    /** Señal one-shot para que la Activity se cierre tras una decisión (o solicitud inválida). */
    private val _finishEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val finishEvents: SharedFlow<Unit> = _finishEvents.asSharedFlow()

    init {
        val req = request
        if (req == null) {
            _uiState.update { it.copy(valid = false) }
            _finishEvents.tryEmit(Unit)
        } else {
            _uiState.update {
                it.copy(
                    valid = true,
                    displayName = req.displayName,
                    mode = req.mode,
                    totalSeconds = req.seconds,
                    remainingSeconds = req.seconds
                )
            }
            observeStats(req.catalogKey)
            startCountdown(req.seconds)
        }
    }

    private fun observeStats(catalogKey: String) {
        viewModelScope.launch {
            repository.observeStats24h(catalogKey, System.currentTimeMillis()).collect { stats ->
                _uiState.update { it.copy(stats = stats) }
            }
        }
    }

    private fun startCountdown(seconds: Int) {
        viewModelScope.launch {
            var remaining = seconds
            while (remaining > 0) {
                delay(1000L)
                remaining--
                _uiState.update { it.copy(remainingSeconds = remaining) }
            }
        }
    }

    fun onContinue() {
        if (!_uiState.value.canContinue) return
        request?.let { gateCoordinator.submitDecision(GateDecision.Continue(it.catalogKey)) }
        _finishEvents.tryEmit(Unit)
    }

    fun onLeave() {
        request?.let { gateCoordinator.submitDecision(GateDecision.Leave(it.catalogKey)) }
        _finishEvents.tryEmit(Unit)
    }
}
