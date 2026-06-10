package com.rusure.app.ui.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rusure.app.ui.util.formatDurationHoursMinutes
import kotlin.math.ceil

/**
 * Formatea minutos enteros a un texto compacto en horas/minutos ("57m", "1h 11m"). Reutiliza el
 * formateador del proyecto trabajando en milisegundos para mantener una única convención de salida.
 */
fun formatMinutesLabel(totalMinutes: Int): String =
    formatDurationHoursMinutes(totalMinutes.coerceAtLeast(0) * 60_000L)

/**
 * Gráfico de tendencia de minutos por día dibujado íntegramente con [Canvas]: rejilla horizontal
 * con etiquetas del eje Y (en h/m), línea suave con puntos por día y etiquetas del eje X (L..D).
 * Todo el texto se pinta con el `nativeCanvas` para que las etiquetas queden alineadas exactamente
 * bajo cada punto. No depende de ninguna librería de gráficos.
 */
@Composable
fun TrendChart(
    points: List<DailyPoint>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    axisColor: Color = Color.White.copy(alpha = 0.45f),
    gridColor: Color = Color.White.copy(alpha = 0.08f)
) {
    if (points.isEmpty()) return

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        drawTrend(points, lineColor, axisColor, gridColor)
    }
}

private fun DrawScope.drawTrend(
    points: List<DailyPoint>,
    lineColor: Color,
    axisColor: Color,
    gridColor: Color
) {
    val leftInset = 38.dp.toPx()   // espacio para etiquetas del eje Y
    val bottomInset = 22.dp.toPx() // espacio para etiquetas del eje X
    val topInset = 8.dp.toPx()

    val plotLeft = leftInset
    val plotRight = size.width
    val plotTop = topInset
    val plotBottom = size.height - bottomInset
    val plotHeight = plotBottom - plotTop

    // Escala "agradable" del eje Y: múltiplo del paso inmediatamente superior al máximo.
    val maxMinutes = points.maxOf { it.minutes }.coerceAtLeast(1)
    val step = when {
        maxMinutes <= 60 -> 15
        maxMinutes <= 120 -> 30
        else -> 60
    }
    val niceMax = (ceil(maxMinutes / step.toFloat()).toInt() * step).coerceAtLeast(step)

    val labelPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = axisColor.toArgb()
        textSize = 10.sp.toPx()
        textAlign = android.graphics.Paint.Align.RIGHT
    }
    val xLabelPaint = android.graphics.Paint(labelPaint).apply {
        textAlign = android.graphics.Paint.Align.CENTER
    }

    // Rejilla horizontal + etiquetas del eje Y (de "step" hasta "niceMax").
    var value = step
    while (value <= niceMax) {
        val ratio = value.toFloat() / niceMax
        val y = plotBottom - ratio * plotHeight
        drawLine(
            color = gridColor,
            start = Offset(plotLeft, y),
            end = Offset(plotRight, y),
            strokeWidth = 1.dp.toPx()
        )
        drawContext.canvas.nativeCanvas.drawText(
            formatDurationHoursMinutes(value * 60_000L),
            plotLeft - 8.dp.toPx(),
            y + (labelPaint.textSize / 3f),
            labelPaint
        )
        value += step
    }

    // Coordenadas de cada punto.
    val n = points.size
    val coords = points.mapIndexed { index, point ->
        val x = if (n == 1) (plotLeft + plotRight) / 2f
        else plotLeft + (plotRight - plotLeft) * index / (n - 1)
        val y = plotBottom - (point.minutes.toFloat() / niceMax) * plotHeight
        Offset(x, y)
    }

    // Relleno degradado bajo la línea (sutil, para dar volumen).
    val fillPath = Path().apply {
        moveTo(coords.first().x, plotBottom)
        coords.forEach { lineTo(it.x, it.y) }
        lineTo(coords.last().x, plotBottom)
        close()
    }
    drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0f)),
            startY = plotTop,
            endY = plotBottom
        )
    )

    // Línea de la serie.
    val linePath = Path().apply {
        moveTo(coords.first().x, coords.first().y)
        for (i in 1 until coords.size) lineTo(coords[i].x, coords[i].y)
    }
    drawPath(
        path = linePath,
        color = lineColor,
        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // Puntos: relleno de color de marca + núcleo oscuro para que "respiren" sobre el fondo.
    coords.forEach { point ->
        drawCircle(color = lineColor, radius = 4.dp.toPx(), center = point)
        drawCircle(color = Color(0xFF0E0E12), radius = 1.8.dp.toPx(), center = point)
    }

    // Etiquetas del eje X bajo cada punto.
    coords.forEachIndexed { index, point ->
        drawContext.canvas.nativeCanvas.drawText(
            points[index].label,
            point.x,
            size.height - 4.dp.toPx(),
            xLabelPaint
        )
    }
}

/** Pequeña referencia tipográfica usada por las leyendas (peso semibold con color de marca). */
internal val LegendWeight = FontWeight.SemiBold
