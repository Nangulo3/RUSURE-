package com.rusure.app.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

/**
 * Iconografía de la sección de estadísticas. El icono de marca resuelve el logo real instalado; el
 * resto son glifos minimalistas dibujados con [Canvas] para no añadir dependencias de iconos
 * extendidos de Material (misma estrategia que el resto de la app).
 */

/**
 * Logo de la app: resuelve el icono instalado vía PackageManager; si no está disponible (no
 * instalada o en previsualización) cae a un avatar con el color de marca y la inicial.
 */
@Composable
fun AppBrandIcon(
    app: ShortAppIdentity,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap = remember(app.packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap().asImageBitmap()
        }.getOrNull()
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(app.brandColor.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = app.displayName.firstOrNull()?.uppercase() ?: "?",
                color = app.brandColor,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp
            )
        }
    }
}

/** Gráfico de barras (acceso al detalle por aplicación). */
@Composable
fun BarsIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val barWidth = w * 0.16f
        val gap = w * 0.13f
        val fractions = listOf(0.55f, 1f, 0.78f)
        // Centra el grupo de barras horizontal y verticalmente dentro del lienzo, para que el
        // dibujo coincida con la píldora de selección de la barra de navegación.
        val groupWidth = barWidth * fractions.size + gap * (fractions.size - 1)
        val startX = (w - groupWidth) / 2f
        val plotHeight = h * 0.62f
        val plotBottom = (h + plotHeight) / 2f
        fractions.forEachIndexed { index, fraction ->
            val left = startX + index * (barWidth + gap)
            val barHeight = plotHeight * fraction
            drawRoundRect(
                color = color,
                topLeft = Offset(left, plotBottom - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth * 0.45f, barWidth * 0.45f)
            )
        }
    }
}

/** Casa minimalista (pestaña "Menú"): tejado triangular + cuerpo, centrada en el lienzo. */
@Composable
fun HomeIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val roofPath = Path().apply {
            moveTo(cx, h * 0.16f)
            lineTo(w * 0.88f, h * 0.5f)
            lineTo(w * 0.12f, h * 0.5f)
            close()
        }
        drawPath(roofPath, color)
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.24f, h * 0.46f),
            size = Size(w * 0.52f, h * 0.38f),
            cornerRadius = CornerRadius(w * 0.06f, w * 0.06f)
        )
    }
}

/** Reloj minimalista (tiempo de hoy). */
@Composable
fun ClockIcon(modifier: Modifier = Modifier, color: Color) {
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

/** Línea de tendencia ascendente con punta de flecha (promedio diario). */
@Composable
fun TrendUpIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.1f
        val path = Path().apply {
            moveTo(w * 0.1f, h * 0.72f)
            lineTo(w * 0.4f, h * 0.45f)
            lineTo(w * 0.58f, h * 0.6f)
            lineTo(w * 0.88f, h * 0.25f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        // Punta de flecha en el extremo superior derecho.
        val tip = Offset(w * 0.88f, h * 0.25f)
        drawLine(color, tip, Offset(w * 0.66f, h * 0.27f), stroke, StrokeCap.Round)
        drawLine(color, tip, Offset(w * 0.86f, h * 0.48f), stroke, StrokeCap.Round)
    }
}

/** Caja con flecha hacia afuera (aperturas). */
@Composable
fun OpenExternalIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        // Marco abierto por la esquina superior derecha.
        val frame = Path().apply {
            moveTo(w * 0.55f, h * 0.2f)
            lineTo(w * 0.2f, h * 0.2f)
            lineTo(w * 0.2f, h * 0.8f)
            lineTo(w * 0.8f, h * 0.8f)
            lineTo(w * 0.8f, h * 0.45f)
        }
        drawPath(frame, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Flecha diagonal saliente.
        drawLine(color, Offset(w * 0.5f, h * 0.5f), Offset(w * 0.85f, h * 0.15f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.85f, h * 0.15f), Offset(w * 0.62f, h * 0.15f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.85f, h * 0.15f), Offset(w * 0.85f, h * 0.38f), stroke, StrokeCap.Round)
    }
}

/** Símbolo de "pausa/interrupción": círculo con barra vertical (interrupciones generadas). */
@Composable
fun InterruptionIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.09f
        val radius = (size.minDimension - stroke) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = color, radius = radius, center = center, style = Stroke(width = stroke))
        drawLine(
            color = color,
            start = Offset(center.x, center.y - radius * 0.45f),
            end = Offset(center.x, center.y + radius * 0.45f),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

/** Círculo con aspa (accesos cancelados). */
@Composable
fun CancelIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.09f
        val radius = (size.minDimension - stroke) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = color, radius = radius, center = center, style = Stroke(width = stroke))
        val d = radius * 0.42f
        drawLine(
            color = color,
            start = Offset(center.x - d, center.y - d),
            end = Offset(center.x + d, center.y + d),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(center.x + d, center.y - d),
            end = Offset(center.x - d, center.y + d),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

/** Chevron hacia la derecha (ítems navegables del detalle por aplicación). */
@Composable
fun ChevronRightIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.12f
        drawLine(color, Offset(w * 0.4f, h * 0.28f), Offset(w * 0.64f, h * 0.5f), stroke, StrokeCap.Round)
        drawLine(color, Offset(w * 0.64f, h * 0.5f), Offset(w * 0.4f, h * 0.72f), stroke, StrokeCap.Round)
    }
}
