package com.rusure.app.ui.dashboard

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rusure.app.ui.settings.AccessibilityStatus
import com.rusure.app.ui.theme.RuSureTheme
import com.rusure.app.ui.util.formatCountdown
import com.rusure.app.ui.util.formatDurationHoursMinutes

/** Verde de estado "Activo": contenedor translúcido + texto, legibles sobre fondo oscuro. */
private val ActiveChipContainer = Color(0xFF1E4A2B)
private val ActiveChipContent = Color(0xFF6FE08A)

/** Rojo de énfasis del modo "Bloqueado": candado y badge, legibles sobre fondo oscuro. */
private val BlockedAccent = Color(0xFFFF5A5A)

/**
 * Dashboard "Mi tiempo": panel inspirado en Tiempo en Pantalla / Bienestar Digital, mayormente de
 * SOLO LECTURA. Presenta el resumen del día y la lista de objetivos; la única interacción posible
 * además de navegar a la configuración de un objetivo (callback [onConfigure]) es la pausa global
 * de protección de 5 minutos (tarjeta de pausa, ver [PauseCard] y `docs/DECISIONS.md` D-009).
 */
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onConfigure: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomBar: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var accessibilityEnabled by remember { mutableStateOf(AccessibilityStatus.isServiceEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        accessibilityEnabled = AccessibilityStatus.isServiceEnabled(context)
    }

    val openAccessibilitySettings: () -> Unit = {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    DashboardContent(
        uiState = uiState,
        accessibilityEnabled = accessibilityEnabled,
        onConfigure = onConfigure,
        onOpenSettings = openAccessibilitySettings,
        onStartPause = viewModel::onStartPause,
        onEndPause = viewModel::onEndPause,
        bottomBar = bottomBar,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardContent(
    uiState: DashboardUiState,
    accessibilityEnabled: Boolean,
    onConfigure: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onStartPause: () -> Unit,
    onEndPause: () -> Unit,
    bottomBar: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Mi tiempo",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Ajustes",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            if (!accessibilityEnabled) {
                item {
                    AccessibilityWarning(onOpenSettings = onOpenSettings)
                }
            }

            item {
                DailySummaryCard(summary = uiState.summary)
            }

            item {
                PauseCard(
                    remainingMillis = uiState.pauseRemainingMillis,
                    onStartPause = onStartPause,
                    onEndPause = onEndPause
                )
            }

            item {
                Text(
                    text = "Objetivos",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
            }

            items(uiState.targets, key = { it.catalogKey }) { item ->
                TargetCard(
                    item = item,
                    onConfigure = { onConfigure(item.catalogKey) }
                )
            }
        }
    }
}

/** Aviso visible solo cuando el servicio de accesibilidad está apagado (la fricción no opera). */
@Composable
private fun AccessibilityWarning(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSettings),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Servicio desactivado",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.size(4.dp))
            Text(
                text = "Toca para activar el servicio de accesibilidad y que la fricción funcione.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

/** Tarjeta "Hoy": tiempo total en apps objetivo + aperturas evitadas. */
@Composable
private fun DailySummaryCard(summary: DailySummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                ClockBadge(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hoy",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDurationHoursMinutes(summary.totalActiveMillis),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "en apps objetivo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = summary.openingsAvoided.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "aperturas\nevitadas",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Tarjeta de pausa global de protección (ver `docs/DECISIONS.md` D-009): pausa durante 5 minutos
 * toda la fricción configurada (espera, recordatorio continuo y modo Bloqueado) sin tocar la
 * configuración de ningún objetivo. Sin pausa activa invita a activarla con un solo toque; en
 * pausa muestra la cuenta atrás y permite cancelarla antes de tiempo.
 */
@Composable
private fun PauseCard(
    remainingMillis: Long,
    onStartPause: () -> Unit,
    onEndPause: () -> Unit
) {
    val paused = remainingMillis > 0L
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                ClockBadge(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (paused) {
                    Text(
                        text = "Protección en pausa",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatCountdown(remainingMillis),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Todo vuelve a tu configuración al terminar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Pausar protección",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Desactiva la fricción de todas las apps durante 5 minutos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (paused) {
                Button(onClick = onEndPause) {
                    Text("Reanudar ahora")
                }
            } else {
                FilledTonalButton(onClick = onStartPause) {
                    Text("Pausar 5 min")
                }
            }
        }
    }
}

/** Reloj minimalista dibujado a mano (sin dependencia de iconos extendidos). */
@Composable
private fun ClockBadge(
    modifier: Modifier = Modifier,
    color: Color
) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.09f
        val radius = (size.minDimension - strokeWidth) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = color,
            radius = radius,
            center = center,
            style = Stroke(width = strokeWidth)
        )
        // Manecilla horaria (hacia arriba).
        drawLine(
            color = color,
            start = center,
            end = Offset(center.x, center.y - radius * 0.5f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        // Manecilla minutera (hacia la derecha).
        drawLine(
            color = color,
            start = center,
            end = Offset(center.x + radius * 0.62f, center.y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Tarjeta de un objetivo. La cabecera (identidad + estado + pista de ajustes) es idéntica para todos
 * los modos; el cuerpo se renderiza condicionalmente según [TargetDashboardItem.protectionMode]:
 *
 * - [ProtectionMode.TIMER]: resumen de configuración en dos líneas (espera e interrupción).
 * - [ProtectionMode.BLOCKED]: variante compacta que comunica la restricción total ("🔒 Bloqueado").
 *
 * NO muestra métricas de uso. La tarjeta completa es pulsable y navega a la configuración del
 * objetivo; el icono de ajustes (sliders) es solo una pista visual, sin área táctil propia.
 */
@Composable
private fun TargetCard(
    item: TargetDashboardItem,
    onConfigure: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onConfigure),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Cabecera: logo, nombre, estado y pista de configuración. Idéntica en ambos modos.
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(
                    packageName = item.packageName,
                    fallbackLabel = item.displayName,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                StatusChip(enabled = item.enabled)
                Spacer(modifier = Modifier.width(10.dp))
                SlidersIcon(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Cuerpo: condicional al modo de protección.
            when (item.protectionMode) {
                ProtectionMode.TIMER -> TimerBody(item)
                ProtectionMode.BLOCKED -> BlockedBody()
            }
        }
    }
}

/** Cuerpo de la variante TIMER: resumen de la configuración del bloqueo en dos líneas. */
@Composable
private fun TimerBody(item: TargetDashboardItem) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ConfigRow(
            label = "Espera antes de entrar",
            value = formatSeconds(item.initialTimerSeconds)
        ) { iconModifier, iconColor ->
            ClockBadge(modifier = iconModifier, color = iconColor)
        }
        ConfigRow(
            label = "Interrupción cada",
            value = formatIntervalMinutes(item.continuousUsageLimitSeconds)
        ) { iconModifier, iconColor ->
            BellIcon(modifier = iconModifier, color = iconColor)
        }
    }
}

/**
 * Cuerpo de la variante BLOCKED: compacto, sin tiempos ni métricas. Un badge de candado rojo y la
 * etiqueta "Modo" sobre el valor destacado "Bloqueado" comunican la restricción total de un vistazo.
 */
@Composable
private fun BlockedBody() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(BlockedAccent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            LockIcon(
                modifier = Modifier.size(22.dp),
                color = BlockedAccent
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(
                text = "Modo",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Bloqueado",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StatusChip(enabled: Boolean) {
    val container = if (enabled) ActiveChipContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (enabled) ActiveChipContent else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(
            text = if (enabled) "Activo" else "Desactivado",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = content,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Una línea del resumen de configuración: glifo + etiqueta descriptiva a la izquierda y el valor
 * alineado a la derecha (ej. "Espera antes de entrar … 5s").
 */
@Composable
private fun ConfigRow(
    label: String,
    value: String,
    leadingIcon: @Composable (iconModifier: Modifier, iconColor: Color) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        leadingIcon(Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Espera inicial mostrada en segundos compactos (ej. "5s"). */
private fun formatSeconds(seconds: Int): String = "${seconds.coerceAtLeast(0)}s"

/** Intervalo de interrupción mostrado en minutos compactos, redondeado (ej. "15m"), mínimo 1m. */
private fun formatIntervalMinutes(seconds: Int): String {
    val minutes = ((seconds + 30) / 60).coerceAtLeast(1)
    return "${minutes}m"
}

/** Campana minimalista dibujada a mano (pista visual de la línea "Interrupción cada"). */
@Composable
private fun BellIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = size.minDimension * 0.09f
        // Cuerpo de la campana (arco superior + faldón).
        val body = Path().apply {
            moveTo(w * 0.28f, h * 0.66f)
            cubicTo(w * 0.28f, h * 0.34f, w * 0.40f, h * 0.22f, w * 0.50f, h * 0.22f)
            cubicTo(w * 0.60f, h * 0.22f, w * 0.72f, h * 0.34f, w * 0.72f, h * 0.66f)
        }
        drawPath(body, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Base horizontal.
        drawLine(color, Offset(w * 0.2f, h * 0.66f), Offset(w * 0.8f, h * 0.66f), stroke, StrokeCap.Round)
        // Badajo.
        drawLine(color, Offset(w * 0.44f, h * 0.74f), Offset(w * 0.56f, h * 0.74f), stroke, StrokeCap.Round)
    }
}

/** Candado cerrado dibujado a mano: cuerpo redondeado + arco superior (modo "Bloqueado"). */
@Composable
private fun LockIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = size.minDimension * 0.1f
        // Cuerpo del candado (relleno redondeado).
        val bodyLeft = w * 0.24f
        val bodyTop = h * 0.46f
        val bodyHeight = h * 0.34f
        drawRoundRect(
            color = color,
            topLeft = Offset(bodyLeft, bodyTop),
            size = Size(w * 0.52f, bodyHeight),
            cornerRadius = CornerRadius(w * 0.1f, w * 0.1f)
        )
        // Arco (shackle) sobre el cuerpo.
        val arcInset = w * 0.34f
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(arcInset, h * 0.22f),
            size = Size(w - arcInset * 2f, h * 0.36f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        // Ojo de la cerradura (barra vertical contrastada sobre el cuerpo).
        drawLine(
            color = color.copy(alpha = 0.35f),
            start = Offset(w * 0.5f, bodyTop + bodyHeight * 0.28f),
            end = Offset(w * 0.5f, bodyTop + bodyHeight * 0.72f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Icono de "sliders" (ajustes) dibujado a mano: dos carriles horizontales con su perilla. Sustituye
 * la antigua flecha de navegación como pista de que la tarjeta lleva a la configuración del objetivo.
 */
@Composable
private fun SlidersIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.1f
        val knobRadius = w * 0.14f
        // Carril superior con perilla a la derecha.
        drawLine(color, Offset(w * 0.15f, h * 0.34f), Offset(w * 0.85f, h * 0.34f), stroke, StrokeCap.Round)
        drawCircle(color, knobRadius, Offset(w * 0.66f, h * 0.34f))
        // Carril inferior con perilla a la izquierda.
        drawLine(color, Offset(w * 0.15f, h * 0.66f), Offset(w * 0.85f, h * 0.66f), stroke, StrokeCap.Round)
        drawCircle(color, knobRadius, Offset(w * 0.34f, h * 0.66f))
    }
}

/**
 * Logo de la app: resuelve el icono real instalado vía PackageManager; si no está instalada o
 * falla la resolución (p. ej. en previsualización), muestra un avatar con la inicial del nombre.
 */
@Composable
private fun AppIcon(
    packageName: String,
    fallbackLabel: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap().asImageBitmap()
        }.getOrNull()
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(12.dp))
        )
    } else {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = fallbackLabel.firstOrNull()?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}

// --- Previsualización con datos mockeados ---

private fun mockDashboardUiState(): DashboardUiState {
    val hour = 60L * 60L * 1000L
    val minute = 60L * 1000L
    return DashboardUiState(
        summary = DailySummary(
            totalActiveMillis = 1 * hour + 47 * minute,
            openingsAvoided = 12
        ),
        targets = listOf(
            TargetDashboardItem(
                catalogKey = "instagram_reels",
                packageName = "com.instagram.android",
                displayName = "Instagram · Reels",
                enabled = true,
                protectionMode = ProtectionMode.BLOCKED,
                initialTimerSeconds = 5,
                continuousUsageLimitSeconds = 15 * 60
            ),
            TargetDashboardItem(
                catalogKey = "tiktok_global",
                packageName = "com.zhiliaoapp.musically",
                displayName = "TikTok",
                enabled = true,
                protectionMode = ProtectionMode.TIMER,
                initialTimerSeconds = 10,
                continuousUsageLimitSeconds = 15 * 60
            ),
            TargetDashboardItem(
                catalogKey = "youtube_shorts",
                packageName = "com.google.android.youtube",
                displayName = "YouTube · Shorts",
                enabled = true,
                protectionMode = ProtectionMode.BLOCKED,
                initialTimerSeconds = 5,
                continuousUsageLimitSeconds = 20 * 60
            )
        )
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun DashboardContentPreview() {
    RuSureTheme {
        DashboardContent(
            uiState = mockDashboardUiState(),
            accessibilityEnabled = true,
            onConfigure = {},
            onOpenSettings = {},
            onStartPause = {},
            onEndPause = {},
            bottomBar = {}
        )
    }
}
