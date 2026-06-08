package com.rusure.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rusure.app.data.local.entity.AppTargetConfig
import com.rusure.app.data.repository.RuSureRepository
import com.rusure.app.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: RuSureRepository
) : ViewModel() {

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(container.repository) }
        }
    }

    val targets: StateFlow<List<AppTargetConfig>> =
        repository.observeTargets().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    fun setEnabled(config: AppTargetConfig, enabled: Boolean) =
        save(config.copy(enabled = enabled))

    fun setInitialTimer(config: AppTargetConfig, seconds: Int) =
        save(config.copy(initialTimerSeconds = seconds))

    fun setContinuousLimit(config: AppTargetConfig, seconds: Int) =
        save(config.copy(continuousUsageLimitSeconds = seconds))

    fun setReEntryTimer(config: AppTargetConfig, seconds: Int) =
        save(config.copy(reEntryTimerSeconds = seconds))

    private fun save(config: AppTargetConfig) {
        viewModelScope.launch { repository.upsertTarget(config) }
    }
}
