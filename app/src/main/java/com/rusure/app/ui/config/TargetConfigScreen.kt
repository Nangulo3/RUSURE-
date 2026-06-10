package com.rusure.app.ui.config

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rusure.app.domain.model.GateAction
import com.rusure.app.ui.config.TargetConfigViewModel.Companion.MAX_LIMIT_MINUTES
import com.rusure.app.ui.config.TargetConfigViewModel.Companion.MIN_LIMIT_MINUTES

/**
 * Pantalla de configuración dedicada a un objetivo. Toda interacción muta el Draft State del
 * ViewModel en memoria; nada se persiste hasta pulsar "Guardar cambios" (bottomBar). Al guardar
 * con éxito se navega de vuelta al dashboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetConfigScreen(
    viewModel: TargetConfigViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Volver atrás descarta el draft (no se persistió nada): salida sin efectos secundarios.
    BackHandler(onBack = onBack)

    // Un guardado exitoso cierra la pantalla y regresa al dashboard.
    LaunchedEffect(viewModel) {
        viewModel.savedEvents.collect { onBack() }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.displayName.ifEmpty { "Configuración" },
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Volver"
                        )
                    }
                }
            )
        },
        bottomBar = {
            val canSave = uiState.draft != null && !uiState.saving
            Box(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = viewModel::save,
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (uiState.saving) "Guardando…" else "Guardar cambios")
                }
            }
        }
    ) { innerPadding ->
        val draft = uiState.draft
        when {
            uiState.loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            draft == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No se encontró la configuración de este objetivo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    EnabledToggleCard(
                        enabled = draft.enabled,
                        onToggle = viewModel::setEnabled
                    )

                    EntryActionCard(
                        action = draft.entryAction,
                        onChange = viewModel::setEntryAction
                    )

                    TimesConfigCard(
                        initialSeconds = draft.initialTimerSeconds,
                        reminderSeconds = draft.continuousUsageLimitSeconds,
                        reEntrySeconds = draft.reEntryTimerSeconds,
                        onSelectInitialSeconds = viewModel::setInitialTimerSeconds,
                        onSelectReminderMinutes = viewModel::setContinuousLimitMinutes,
                        onSelectReEntrySeconds = viewModel::setReEntryTimerSeconds
                    )
                }
            }
        }
    }
}

@Composable
private fun EnabledToggleCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Objetivo activo",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Aplica la fricción cuando se abre este objetivo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun EntryActionCard(
    action: GateAction,
    onChange: (GateAction) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Acción al intentar entrar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            EntryActionSelector(action = action, onChange = onChange)
            Text(
                text = when (action) {
                    GateAction.WAIT ->
                        "Se muestra una cuenta regresiva antes de permitir el acceso."
                    GateAction.BLOCK ->
                        "El acceso se bloquea por completo y se vuelve al inicio."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Control segmentado para la acción al entrar: "Espera" (reloj) y "Bloqueo" (candado). La opción
 * seleccionada se resalta con el color primario; ambos iconos se dibujan con [Canvas] para no
 * depender de los iconos extendidos de Material.
 */
@Composable
private fun EntryActionSelector(
    action: GateAction,
    onChange: (GateAction) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            EntrySegment(
                selected = action == GateAction.WAIT,
                label = "Espera",
                onClick = { onChange(GateAction.WAIT) },
                modifier = Modifier.weight(1f),
                icon = { color -> ClockIcon(modifier = Modifier.size(18.dp), color = color) }
            )
            EntrySegment(
                selected = action == GateAction.BLOCK,
                label = "Bloqueo",
                onClick = { onChange(GateAction.BLOCK) },
                modifier = Modifier.weight(1f),
                icon = { color -> LockIcon(modifier = Modifier.size(18.dp), color = color) }
            )
        }
    }
}

@Composable
private fun EntrySegment(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    } else {
        Color.Transparent
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon(content)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = content,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/** Reloj minimalista (círculo + dos manecillas) dibujado a mano. */
@Composable
private fun ClockIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.09f
        val radius = (size.minDimension - strokeWidth) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = color, radius = radius, center = center, style = Stroke(width = strokeWidth))
        drawLine(
            color = color,
            start = center,
            end = Offset(center.x, center.y - radius * 0.5f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = center,
            end = Offset(center.x + radius * 0.62f, center.y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

/** Candado minimalista (arco superior + cuerpo redondeado) dibujado a mano. */
@Composable
private fun LockIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeWidth = w * 0.11f
        // Arco (asa) del candado.
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.28f, h * 0.10f),
            size = Size(w * 0.44f, w * 0.44f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        // Cuerpo del candado.
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.18f, h * 0.42f),
            size = Size(w * 0.64f, h * 0.46f),
            cornerRadius = CornerRadius(w * 0.12f, w * 0.12f)
        )
    }
}

/** Identifica cuál de las tres filas de tiempo está expandida (acordeón mutuamente excluyente). */
private enum class TimeRow { INITIAL, REMINDER, REENTRY }

/**
 * Opciones predefinidas (en segundos) para los tiempos de espera cortos. Exactamente 6 valores para
 * rellenar una cuadrícula simétrica de 2 filas × 3 columnas.
 */
private val WAIT_SECONDS_OPTIONS = listOf(5, 10, 15, 20, 30, 60)

/**
 * Opciones predefinidas (en minutos) para el recordatorio de uso continuo. Exactamente 6 valores
 * (sin "20 min") para rellenar la cuadrícula simétrica de 2 filas × 3 columnas.
 */
private val REMINDER_MINUTES_OPTIONS = listOf(5, 10, 15, 30, 45, 60)

/**
 * Tarjeta "Configuración de tiempos": reemplaza los antiguos sliders por tres listas desplegables
 * tipo acordeón, mutuamente excluyentes. El estado de expansión es 100% local a la tarjeta (no toca
 * el ViewModel): abrir una fila colapsa cualquier otra, y elegir una opción colapsa al instante. La
 * selección solo muta el draft local del ViewModel; la persistencia ocurre aparte en "Guardar cambios".
 */
@Composable
private fun TimesConfigCard(
    initialSeconds: Int,
    reminderSeconds: Int,
    reEntrySeconds: Int,
    onSelectInitialSeconds: (Int) -> Unit,
    onSelectReminderMinutes: (Int) -> Unit,
    onSelectReEntrySeconds: (Int) -> Unit
) {
    // Una sola variable de estado garantiza la exclusividad: como mucho una fila puede estar abierta.
    var expanded by remember { mutableStateOf<TimeRow?>(null) }
    val reminderMinutes = ((reminderSeconds + 30) / 60).coerceIn(MIN_LIMIT_MINUTES, MAX_LIMIT_MINUTES)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Configuración de tiempos",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Define los tiempos de tu flujo de acceso.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.size(8.dp))

            TimeAccordionRow(
                expanded = expanded == TimeRow.INITIAL,
                onToggle = { expanded = if (expanded == TimeRow.INITIAL) null else TimeRow.INITIAL },
                icon = { color -> ClockIcon(modifier = Modifier.size(24.dp), color = color) },
                title = "Tiempo de espera inicial",
                description = "Se muestra antes de permitir el acceso.",
                valueLabel = "$initialSeconds segundos",
                options = WAIT_SECONDS_OPTIONS,
                selectedOption = initialSeconds,
                optionLabel = { "$it s" },
                onSelect = {
                    onSelectInitialSeconds(it)
                    expanded = null
                }
            )

            HorizontalDivider()

            TimeAccordionRow(
                expanded = expanded == TimeRow.REMINDER,
                onToggle = { expanded = if (expanded == TimeRow.REMINDER) null else TimeRow.REMINDER },
                icon = { color -> BellIcon(modifier = Modifier.size(24.dp), color = color) },
                title = "Recordatorio cada",
                description = "Después de este tiempo aparecerá una nueva pausa.",
                valueLabel = "$reminderMinutes minutos",
                options = REMINDER_MINUTES_OPTIONS,
                selectedOption = reminderMinutes,
                optionLabel = { "$it min" },
                onSelect = {
                    onSelectReminderMinutes(it)
                    expanded = null
                }
            )

            HorizontalDivider()

            TimeAccordionRow(
                expanded = expanded == TimeRow.REENTRY,
                onToggle = { expanded = if (expanded == TimeRow.REENTRY) null else TimeRow.REENTRY },
                icon = { color -> HourglassIcon(modifier = Modifier.size(24.dp), color = color) },
                title = "Tiempo de espera tras recordatorio",
                description = "Tiempo que debe esperar para continuar usando la aplicación.",
                valueLabel = "$reEntrySeconds segundos",
                options = WAIT_SECONDS_OPTIONS,
                selectedOption = reEntrySeconds,
                optionLabel = { "$it s" },
                onSelect = {
                    onSelectReEntrySeconds(it)
                    expanded = null
                }
            )
        }
    }
}

/**
 * Una fila del acordeón de tiempos. Colapsada muestra icono + título/descripción + valor actual y un
 * chevron; expandida revela los chips de opciones, resaltando el actualmente seleccionado. Toda la
 * cabecera es pulsable para alternar la expansión (vía [onToggle]); elegir un chip dispara [onSelect].
 */
@Composable
private fun TimeAccordionRow(
    expanded: Boolean,
    onToggle: () -> Unit,
    icon: @Composable (Color) -> Unit,
    title: String,
    description: String,
    valueLabel: String,
    options: List<Int>,
    selectedOption: Int,
    optionLabel: (Int) -> String,
    onSelect: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon(MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Estado cerrado: la cabecera muestra el valor actual. Estado expandido: se oculta para
            // evitar la duplicación con el chip activo; la selección la representa solo el chip.
            if (!expanded) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // El chevron base apunta a la derecha: rotar 90° → ▼ (cerrado), −90° → ▲ (expandido).
            ChevronIcon(
                modifier = Modifier
                    .size(16.dp)
                    .rotate(if (expanded) -90f else 90f),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = expanded) {
            TimeOptionsGrid(
                options = options,
                selectedOption = selectedOption,
                optionLabel = optionLabel,
                onSelect = onSelect
            )
        }
    }
}

/**
 * Cuadrícula estricta de 2 filas × 3 columnas de chips seleccionables, revelada al expandir una fila
 * de tiempo. Cada [options] contiene exactamente 6 elementos: `chunked(3)` produce dos filas llenas,
 * sin huecos ni botones aislados. Las columnas son equitativas (cada chip toma `weight(1f)`), por lo
 * que la simetría se mantiene idéntica en cualquier ancho de pantalla.
 */
@Composable
private fun TimeOptionsGrid(
    options: List<Int>,
    selectedOption: Int,
    optionLabel: (Int) -> String,
    onSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 38.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.chunked(3).forEach { rowOptions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowOptions.forEach { option ->
                    TimeOptionChip(
                        label = optionLabel(option),
                        selected = option == selectedOption,
                        onClick = { onSelect(option) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/**
 * Chip de una opción de tiempo. El seleccionado se resalta con el color primario. Ocupa el ancho que
 * le asigne su columna (vía [modifier] con `weight`) y centra la etiqueta en una sola línea para que
 * las tres columnas queden perfectamente alineadas.
 */
@Composable
private fun TimeOptionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
            maxLines = 1
        )
    }
}

/** Campana minimalista (cuerpo en arco + base + badajo) dibujada a mano. */
@Composable
private fun BellIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = size.minDimension * 0.09f
        val body = Path().apply {
            moveTo(w * 0.28f, h * 0.66f)
            cubicTo(w * 0.28f, h * 0.34f, w * 0.40f, h * 0.22f, w * 0.50f, h * 0.22f)
            cubicTo(w * 0.60f, h * 0.22f, w * 0.72f, h * 0.34f, w * 0.72f, h * 0.66f)
        }
        drawPath(body, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color, Offset(w * 0.2f, h * 0.66f), Offset(w * 0.8f, h * 0.66f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.44f, h * 0.74f), Offset(w * 0.56f, h * 0.74f), stroke, StrokeCap.Round)
    }
}

/** Reloj de arena minimalista (dos barras + lazo central) dibujado a mano. */
@Composable
private fun HourglassIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = size.minDimension * 0.09f
        // Barras superior e inferior.
        drawLine(color, Offset(w * 0.26f, h * 0.18f), Offset(w * 0.74f, h * 0.18f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.26f, h * 0.82f), Offset(w * 0.74f, h * 0.82f), stroke, StrokeCap.Round)
        // Cuerpo de reloj de arena (dos triángulos que se tocan en el centro).
        val glass = Path().apply {
            moveTo(w * 0.3f, h * 0.2f)
            lineTo(w * 0.7f, h * 0.2f)
            lineTo(w * 0.5f, h * 0.5f)
            lineTo(w * 0.7f, h * 0.8f)
            lineTo(w * 0.3f, h * 0.8f)
            lineTo(w * 0.5f, h * 0.5f)
            close()
        }
        drawPath(glass, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Chevron hacia la derecha dibujado a mano (rota a 90° para indicar fila expandida). */
@Composable
private fun ChevronIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.14f
        drawLine(color, Offset(w * 0.38f, h * 0.26f), Offset(w * 0.64f, h * 0.5f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.64f, h * 0.5f), Offset(w * 0.38f, h * 0.74f), stroke, StrokeCap.Round)
    }
}
