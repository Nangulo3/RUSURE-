package com.rusure.app.ui.statistics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusure.app.ui.theme.RuSureTheme

/** Acentos de las métricas de comportamiento, coherentes con el diseño (neutro / ámbar / rojo). */
private val OpeningsAccent = Color(0xFF9B8CFF)
private val InterruptionAccent = Color(0xFFFF9F45)
private val CancelAccent = Color(0xFFFF5C7A)

/**
 * Pantalla "Detalle individual de aplicación": para la app seleccionada muestra el tiempo de hoy,
 * el promedio diario, el gráfico de los últimos 7 días (filtrado a esa app) y las métricas de
 * comportamiento de la fricción (aperturas, interrupciones generadas y accesos cancelados).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDetailScreen(
    detail: AppStatsDetail,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    var behaviorRange by rememberSaveable { mutableStateOf(BehaviorRange.WEEK) }
    val behavior = if (behaviorRange == BehaviorRange.TODAY) detail.behaviorToday else detail.behaviorWeek

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppBrandIcon(app = detail.app, size = 28.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = detail.app.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Volver"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp)
        ) {
            item {
                HeadlineMetricCard(
                    label = "Tiempo hoy",
                    minutes = detail.todayMinutes,
                    accent = detail.app.brandColor,
                    icon = { color -> ClockIcon(modifier = Modifier.size(22.dp), color = color) }
                )
            }
            item {
                HeadlineMetricCard(
                    label = "Promedio diario",
                    minutes = detail.dailyAverageMinutes,
                    accent = detail.app.brandColor,
                    icon = { color -> TrendUpIcon(modifier = Modifier.size(22.dp), color = color) }
                )
            }
            item {
                StatCard {
                    Text(
                        text = "Últimos 7 días",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TrendChart(
                        points = detail.trend,
                        lineColor = detail.app.brandColor,
                        height = 170.dp
                    )
                }
            }
            item {
                BehaviorRangeSelector(selected = behaviorRange, onSelect = { behaviorRange = it })
            }
            item {
                BehaviorMetricRow(
                    label = "Aperturas",
                    value = behavior.openings,
                    accent = OpeningsAccent,
                    icon = { color -> OpenExternalIcon(modifier = Modifier.size(22.dp), color = color) }
                )
            }
            item {
                BehaviorMetricRow(
                    label = "Interrupciones generadas",
                    value = behavior.interruptions,
                    accent = InterruptionAccent,
                    icon = { color -> InterruptionIcon(modifier = Modifier.size(22.dp), color = color) }
                )
            }
            item {
                BehaviorMetricRow(
                    label = "Accesos cancelados",
                    value = behavior.cancelledAccesses,
                    accent = CancelAccent,
                    icon = { color -> CancelIcon(modifier = Modifier.size(22.dp), color = color) }
                )
            }
        }
    }
}

/** Tarjeta de métrica destacada (tiempo hoy / promedio): valor grande con unidad reducida + icono. */
@Composable
private fun HeadlineMetricCard(
    label: String,
    minutes: Int,
    accent: Color,
    icon: @Composable (Color) -> Unit
) {
    StatCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                DurationDisplay(
                    minutes = minutes,
                    color = accent,
                    numberSize = 34.sp,
                    unitSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            AccentIconBadge(accent = accent, icon = icon)
        }
    }
}

/** Fila de métrica de comportamiento: etiqueta + valor grande + insignia con icono de acento. */
@Composable
private fun BehaviorMetricRow(
    label: String,
    value: Int,
    accent: Color,
    icon: @Composable (Color) -> Unit
) {
    StatCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
            }
            AccentIconBadge(accent = accent, icon = icon)
        }
    }
}

@Composable
private fun AccentIconBadge(accent: Color, icon: @Composable (Color) -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        icon(accent)
    }
}

/** Ventana temporal de las métricas de comportamiento del detalle. */
enum class BehaviorRange(val label: String) { TODAY("Hoy"), WEEK("Últimos 7 días") }

@Composable
private fun BehaviorRangeSelector(selected: BehaviorRange, onSelect: (BehaviorRange) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BehaviorRange.entries.forEach { range ->
            val active = range == selected
            Text(
                text = range.label,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else Color.Transparent
                    )
                    .clickable { onSelect(range) }
                    .padding(vertical = 8.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, heightDp = 1000)
@Composable
private fun AppDetailScreenPreview() {
    RuSureTheme(dynamicColor = false) {
        AppDetailScreen(
            detail = MockStatistics.detailFor(ShortContentApps.TIKTOK.catalogKey),
            onBack = {}
        )
    }
}
