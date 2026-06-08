package com.rusure.app.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rusure.app.data.local.entity.AppTargetConfig
import com.rusure.app.ui.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val targets by viewModel.targets.collectAsStateWithLifecycle()

    var accessibilityEnabled by remember { mutableStateOf(AccessibilityStatus.isServiceEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessibilityEnabled = AccessibilityStatus.isServiceEnabled(context)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("RuSure") }) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
        ) {
            item {
                AccessibilityCard(
                    enabled = accessibilityEnabled,
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                )
            }

            item {
                Text(
                    text = "Objetivos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            items(targets, key = { it.catalogKey }) { config ->
                TargetCard(
                    config = config,
                    onEnabledChange = { viewModel.setEnabled(config, it) },
                    onInitialChange = { viewModel.setInitialTimer(config, it) },
                    onLimitChange = { viewModel.setContinuousLimit(config, it) },
                    onReEntryChange = { viewModel.setReEntryTimer(config, it) }
                )
            }
        }
    }
}

@Composable
private fun AccessibilityCard(
    enabled: Boolean,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (enabled) "Servicio activo" else "Servicio desactivado",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (enabled) {
                    "RuSure está vigilando tus apps y secciones objetivo."
                } else {
                    "Activa el servicio de accesibilidad de RuSure para que la fricción funcione."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (!enabled) {
                Button(onClick = onOpenSettings) {
                    Text("Abrir ajustes de accesibilidad")
                }
            }
        }
    }
}

@Composable
private fun TargetCard(
    config: AppTargetConfig,
    onEnabledChange: (Boolean) -> Unit,
    onInitialChange: (Int) -> Unit,
    onLimitChange: (Int) -> Unit,
    onReEntryChange: (Int) -> Unit
) {
    // La configuración queda colapsada por defecto; se despliega al tocar la tarjeta.
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevronRotation"
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = collapsedSummary(config),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = config.enabled, onCheckedChange = onEnabledChange)
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Ocultar configuración" else "Mostrar configuración",
                    modifier = Modifier.rotate(chevronRotation)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    ParamSlider(
                        label = "Cuenta regresiva inicial",
                        valueSeconds = config.initialTimerSeconds,
                        range = 3f..60f,
                        onChange = onInitialChange
                    )
                    ParamSlider(
                        label = "Límite de uso continuo",
                        valueSeconds = config.continuousUsageLimitSeconds,
                        range = 30f..1800f,
                        onChange = onLimitChange
                    )
                    ParamSlider(
                        label = "Cuenta regresiva de reingreso",
                        valueSeconds = config.reEntryTimerSeconds,
                        range = 3f..120f,
                        onChange = onReEntryChange
                    )
                }
            }
        }
    }
}

/** Resumen de una línea visible cuando la tarjeta está colapsada. */
private fun collapsedSummary(config: AppTargetConfig): String {
    if (!config.enabled) return "Desactivado"
    val initial = formatDuration(config.initialTimerSeconds * 1000L)
    val limit = formatDuration(config.continuousUsageLimitSeconds * 1000L)
    val reEntry = formatDuration(config.reEntryTimerSeconds * 1000L)
    return "Activo · $initial · límite $limit · reingreso $reEntry"
}

@Composable
private fun ParamSlider(
    label: String,
    valueSeconds: Int,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatDuration(valueSeconds * 1000L),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Slider(
            value = valueSeconds.toFloat().coerceIn(range),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range
        )
    }
}
