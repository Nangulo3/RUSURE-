package com.rusure.app.ui.statistics

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusure.app.ui.theme.RuSureTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Verde de "mejora" (consumo menor que la semana pasada): contenedor + texto sobre fondo oscuro. */
private val ImprovementContainer = Color(0xFF14331F)
private val ImprovementContent = Color(0xFF6FE08A)
private val RegressionContainer = Color(0xFF3A2421)
private val RegressionContent = Color(0xFFFF8A7A)

/**
 * Pantalla principal "Estadísticas": enfocada en hábitos y progreso diario del consumo de contenido
 * corto (TikTok, Instagram Reels, YouTube Shorts). Cuatro secciones secuenciales: Hoy, Promedio
 * diario, Tendencia y la navegación al detalle por aplicación. Es de SOLO LECTURA y no muestra
 * configuración, límites ni tiempo de pantalla general del teléfono.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    uiState: StatisticsUiState,
    onOpenAppBreakdown: () -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {}
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Estadísticas",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = bottomBar,
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp)
        ) {
            item { TodaySection(today = uiState.today) }
            item { DailyAverageSection(average = uiState.dailyAverage) }
            item { TrendSection(days = uiState.weeklyTrend) }
            item { AppBreakdownNavCard(onClick = onOpenAppBreakdown) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sección "Hoy"
// ---------------------------------------------------------------------------------------------

@Composable
private fun TodaySection(today: TodaySummary) {
    StatCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Hoy",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            DateChip()
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "Tiempo en contenido corto",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        DurationDisplay(
            minutes = today.totalMinutes,
            color = MaterialTheme.colorScheme.primary,
            numberSize = 46.sp,
            unitSize = 22.sp
        )

        Spacer(modifier = Modifier.height(18.dp))

        today.perApp.sortedByDescending { it.todayMinutes }.forEach { breakdown ->
            AppBreakdownRow(breakdown = breakdown)
        }
    }
}

@Composable
private fun DateChip() {
    val today = remember { SimpleDateFormat("d MMM", Locale.forLanguageTag("es-ES")).format(Date()) }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Text(
            text = today,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** Fila del desglose por app: icono de marca, nombre y minutos de hoy en el color de la app. */
@Composable
private fun AppBreakdownRow(breakdown: AppDailyBreakdown) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppBrandIcon(app = breakdown.app, size = 36.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = breakdown.app.displayName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        DurationDisplay(
            minutes = breakdown.todayMinutes,
            color = breakdown.app.brandColor,
            numberSize = 18.sp,
            unitSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Sección "Promedio diario"
// ---------------------------------------------------------------------------------------------

@Composable
private fun DailyAverageSection(average: DailyAverage) {
    StatCard {
        Text(
            text = "Promedio diario (últimos 7 días)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = "Esta semana",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                DurationDisplay(
                    minutes = average.thisWeekMinutesPerDay,
                    color = MaterialTheme.colorScheme.onSurface,
                    numberSize = 30.sp,
                    unitSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Semana pasada",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                DurationDisplay(
                    minutes = average.lastWeekMinutesPerDay,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    numberSize = 22.sp,
                    unitSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        DailyDeltaChip(average = average)
    }
}

/**
 * Resalta la diferencia DIARIA (no la semanal): "↓ 31 min por día". El verde indica mejora
 * (consumo menor que la semana pasada); el rojo, lo contrario.
 */
@Composable
private fun DailyDeltaChip(average: DailyAverage) {
    if (average.unchanged) {
        Text(
            text = "Igual que la semana pasada",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    val improved = average.improved
    val container = if (improved) ImprovementContainer else RegressionContainer
    val content = if (improved) ImprovementContent else RegressionContent
    val arrow = if (improved) "↓" else "↑"
    val deltaMinutes = abs(average.deltaMinutesPerDay)

    Column {
        Surface(shape = RoundedCornerShape(50), color = container) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = arrow,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = content
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$deltaMinutes min por día",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = content
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (improved) "menos que la semana pasada" else "más que la semana pasada",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Sección "Tendencia"
// ---------------------------------------------------------------------------------------------

/**
 * Tarjeta "Tendencia (últimos 7 días)": gráfica de barras apiladas interactiva.
 *
 * Estado local:
 * - [metric]: métrica activa del selector (tiempo total / aperturas).
 * - [selectedIndex]: día seleccionado (null = sin selección → gráfica limpia, sin panel).
 *
 * Ambos son INDEPENDIENTES, de modo que cambiar la métrica reconstruye los datos mostrados pero
 * preserva el día seleccionado (regla de persistencia de estado).
 */
@Composable
private fun TrendSection(days: List<WeeklyDay>) {
    var metric by remember { mutableStateOf(TrendMetric.TIME) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    StatCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Tendencia (últimos 7 días)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            MetricDropdown(metric = metric, onMetricChange = { metric = it })
        }

        Spacer(modifier = Modifier.height(20.dp))

        StackedBarChart(
            days = days,
            metric = metric,
            selectedIndex = selectedIndex,
            onSelect = { selectedIndex = it }
        )

        // Panel de detalle: aparece SOLO con un día seleccionado; crossfade suave entre días/métrica.
        AnimatedContent(
            targetState = selectedIndex,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "trend-detail"
        ) { index ->
            if (index != null && index in days.indices) {
                Column {
                    Spacer(modifier = Modifier.height(18.dp))
                    DayDetailPanel(day = days[index], metric = metric)
                }
            } else {
                Spacer(modifier = Modifier.height(0.dp))
            }
        }
    }
}

/** Selector desplegable de métrica en la esquina superior derecha ("Tiempo total ▼" / "Aperturas ▼"). */
@Composable
private fun MetricDropdown(
    metric: TrendMetric,
    onMetricChange: (TrendMetric) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = metric.selectorLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            TrendMetric.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.selectorLabel) },
                    onClick = {
                        onMetricChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * Gráfica de barras apiladas, limpia y minimalista. Cada columna es un día (L..D); cada barra apila
 * el uso de las tres apps en su color exacto. La SELECCIÓN no dibuja ninguna línea ni resalta la
 * barra: el único indicador es un círculo relleno tras la inicial del día en el eje X.
 *
 * Interacción:
 * - Tocar una columna selecciona ese día.
 * - Si hay un día seleccionado, un swipe horizontal mueve la selección al día adyacente.
 */
@Composable
private fun StackedBarChart(
    days: List<WeeklyDay>,
    metric: TrendMetric,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit
) {
    if (days.isEmpty()) return
    val n = days.size
    val maxTotal = days.maxOf { it.total(metric) }.coerceAtLeast(1)
    val barsHeight = 150.dp
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val mutedLabel = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(barsHeight)
                // Tap: selecciona la columna bajo el dedo.
                .pointerInput(n) {
                    detectTapGestures { offset ->
                        val column = (offset.x / (size.width / n)).toInt().coerceIn(0, n - 1)
                        onSelect(column)
                    }
                }
                // Swipe: si hay día seleccionado, mueve la selección al adyacente.
                .pointerInput(n, selectedIndex) {
                    var accumulated = 0f
                    val threshold = 40.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { accumulated = 0f },
                        onHorizontalDrag = { _, delta -> accumulated += delta },
                        onDragEnd = {
                            val current = selectedIndex ?: return@detectHorizontalDragGestures
                            when {
                                accumulated <= -threshold ->
                                    onSelect((current + 1).coerceAtMost(n - 1))
                                accumulated >= threshold ->
                                    onSelect((current - 1).coerceAtLeast(0))
                            }
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val columnWidth = size.width / n
                val barWidth = columnWidth * 0.46f
                val cornerRadius = barWidth * 0.35f
                val plotBottom = size.height
                val plotHeight = size.height

                days.forEachIndexed { index, day ->
                    val total = day.total(metric)
                    if (total <= 0) return@forEachIndexed
                    val barHeight = (total.toFloat() / maxTotal) * plotHeight
                    val left = columnWidth * index + (columnWidth - barWidth) / 2f
                    val barTop = plotBottom - barHeight

                    // Esquinas redondeadas SOLO arriba: recortamos la barra y pintamos los segmentos.
                    val barPath = Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                left = left,
                                top = barTop,
                                right = left + barWidth,
                                bottom = plotBottom + cornerRadius, // empuja el redondeo inferior fuera de vista
                                topLeftCornerRadius = CornerRadius(cornerRadius, cornerRadius),
                                topRightCornerRadius = CornerRadius(cornerRadius, cornerRadius),
                                bottomLeftCornerRadius = CornerRadius.Zero,
                                bottomRightCornerRadius = CornerRadius.Zero
                            )
                        )
                    }
                    clipPath(barPath) {
                        var segmentBottom = plotBottom
                        // Orden de apilado de abajo hacia arriba = orden estricto de la lista.
                        day.perApp.forEach { usage ->
                            val value = usage.valueFor(metric)
                            if (value <= 0) return@forEach
                            val segmentHeight = (value.toFloat() / maxTotal) * plotHeight
                            val segmentTop = segmentBottom - segmentHeight
                            drawRect(
                                color = TrendColors.forKey(usage.app.catalogKey),
                                topLeft = Offset(left, segmentTop),
                                size = Size(barWidth, segmentHeight)
                            )
                            segmentBottom = segmentTop
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Eje X: iniciales de los días; la seleccionada lleva un círculo relleno detrás (animado).
        Row(modifier = Modifier.fillMaxWidth()) {
            days.forEachIndexed { index, day ->
                DayAxisLabel(
                    label = day.label,
                    selected = index == selectedIndex,
                    selectedColor = primary,
                    selectedTextColor = onPrimary,
                    unselectedTextColor = mutedLabel,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** Una inicial del eje X con su círculo de selección animado (sin tocar las barras). */
@Composable
private fun DayAxisLabel(
    label: String,
    selected: Boolean,
    selectedColor: Color,
    selectedTextColor: Color,
    unselectedTextColor: Color,
    modifier: Modifier = Modifier
) {
    val circleAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        label = "axis-circle"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) selectedTextColor else unselectedTextColor,
        label = "axis-text"
    )
    Box(
        modifier = modifier.height(28.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(selectedColor.copy(alpha = circleAlpha))
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}

/**
 * Panel de detalle de un día seleccionado, en dos columnas: a la izquierda el resumen del día
 * (nombre, valor principal y subtítulo según la métrica); a la derecha el desglose por app en orden
 * estricto (TikTok, Instagram Reels, YouTube Shorts) con punto de color, nombre, valor y porcentaje.
 */
@Composable
private fun DayDetailPanel(day: WeeklyDay, metric: TrendMetric) {
    val total = day.total(metric)
    Row(modifier = Modifier.fillMaxWidth()) {
        // Columna izquierda: resumen del día.
        Column(modifier = Modifier.weight(0.42f)) {
            Text(
                text = day.fullName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatMetricValue(total, metric),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = metric.detailSubtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Columna derecha: desglose por app (orden de la lista = orden estricto requerido).
        Column(
            modifier = Modifier.weight(0.58f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            day.perApp.forEach { usage ->
                AppBreakdownDetailRow(usage = usage, dayTotal = total, metric = metric)
            }
        }
    }
}

@Composable
private fun AppBreakdownDetailRow(
    usage: DayAppUsage,
    dayTotal: Int,
    metric: TrendMetric
) {
    val value = usage.valueFor(metric)
    val percentage = if (dayTotal > 0) (value * 100f / dayTotal).roundToInt() else 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(TrendColors.forKey(usage.app.catalogKey))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = usage.app.displayName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatMetricValueCompact(value, metric),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$percentage%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Valor con unidad textual para el resumen del día: "2h 14m" o "24 aperturas". */
private fun formatMetricValue(value: Int, metric: TrendMetric): String = when (metric) {
    TrendMetric.TIME -> formatMinutesLabel(value)
    TrendMetric.OPENINGS -> if (value == 1) "1 apertura" else "$value aperturas"
}

/** Valor compacto para el desglose por app: "1h 24m" o solo el conteo "24". */
private fun formatMetricValueCompact(value: Int, metric: TrendMetric): String = when (metric) {
    TrendMetric.TIME -> formatMinutesLabel(value)
    TrendMetric.OPENINGS -> value.toString()
}

// ---------------------------------------------------------------------------------------------
// Sección "Navegación"
// ---------------------------------------------------------------------------------------------

@Composable
private fun AppBreakdownNavCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BarsIcon(
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = "Ver detalle por aplicación",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Previsualización
// ---------------------------------------------------------------------------------------------

@Preview(showBackground = true, backgroundColor = 0xFF000000, heightDp = 1200)
@Composable
private fun StatisticsScreenPreview() {
    RuSureTheme(dynamicColor = false) {
        StatisticsScreen(
            uiState = MockStatistics.statisticsUiState,
            onOpenAppBreakdown = {}
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Helpers compartidos de presentación (texto de duración con unidad reducida + tarjeta base)
// ---------------------------------------------------------------------------------------------

/**
 * Muestra una duración ("57m", "1h 11m") con los dígitos en [numberSize] y las unidades (h/m) en
 * [unitSize] reducido, replicando el énfasis tipográfico del diseño.
 */
@Composable
fun DurationDisplay(
    minutes: Int,
    color: Color,
    numberSize: androidx.compose.ui.unit.TextUnit,
    unitSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight = FontWeight.Bold,
    modifier: Modifier = Modifier
) {
    val text = formatMinutesLabel(minutes)
    val annotated = buildAnnotatedString {
        text.forEach { char ->
            if (char.isDigit()) {
                withStyle(SpanStyle(fontSize = numberSize, fontWeight = fontWeight)) {
                    append(char)
                }
            } else {
                withStyle(SpanStyle(fontSize = unitSize, fontWeight = fontWeight)) {
                    append(char)
                }
            }
        }
    }
    Text(text = annotated, color = color, modifier = modifier)
}

/** Tarjeta base de las secciones de estadísticas: contenedor translúcido con borde sutil. */
@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
    ) {
        Column(modifier = Modifier.padding(18.dp), content = content)
    }
}
