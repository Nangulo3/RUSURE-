package com.rusure.app.ui.statistics

import androidx.compose.ui.graphics.Color
import com.rusure.app.domain.catalog.TargetCatalog

/**
 * Modelos de presentación de la sección "Estadísticas".
 *
 * IMPORTANTE — alcance del dominio: estas pantallas miden ÚNICA Y EXCLUSIVAMENTE el consumo de
 * contenido corto en las tres apps objetivo (TikTok, Instagram Reels, YouTube Shorts). No existe
 * ningún concepto de "tiempo de pantalla" general del teléfono, ni se exponen los parámetros de
 * configuración (temporizadores, límites, tiempo evitado): todas las métricas son datos reales
 * medibles del consumo. Los modelos están diseñados para mapearse 1:1 con agregados de
 * [com.rusure.app.data.local.dao.UsageSessionDao] cuando se conecten a Room; aquí se alimentan de
 * [MockStatistics] para desarrollar y previsualizar la interfaz.
 */

/** Identidad visual de una de las tres apps de contenido corto monitoreadas. */
data class ShortAppIdentity(
    val catalogKey: String,
    val displayName: String,
    val packageName: String,
    /** Color de acento de marca, usado para valores numéricos, leyendas y series del gráfico. */
    val brandColor: Color
)

/** Catálogo cerrado de las tres apps de contenido corto. El orden define el de la interfaz. */
object ShortContentApps {

    val TIKTOK = ShortAppIdentity(
        catalogKey = TargetCatalog.KEY_TIKTOK_GLOBAL,
        displayName = "TikTok",
        packageName = "com.zhiliaoapp.musically",
        brandColor = Color(0xFFFF2D55)
    )

    val INSTAGRAM_REELS = ShortAppIdentity(
        catalogKey = TargetCatalog.KEY_INSTAGRAM_REELS,
        displayName = "Instagram Reels",
        packageName = "com.instagram.android",
        brandColor = Color(0xFFF77737)
    )

    val YOUTUBE_SHORTS = ShortAppIdentity(
        catalogKey = TargetCatalog.KEY_YOUTUBE_SHORTS,
        displayName = "YouTube Shorts",
        packageName = "com.google.android.youtube",
        brandColor = Color(0xFFFF3B30)
    )

    val all: List<ShortAppIdentity> = listOf(TIKTOK, INSTAGRAM_REELS, YOUTUBE_SHORTS)

    fun byKey(catalogKey: String): ShortAppIdentity =
        all.firstOrNull { it.catalogKey == catalogKey } ?: TIKTOK
}

/** Un punto del gráfico de tendencia: minutos consumidos en un día concreto de la semana. */
data class DailyPoint(
    /** Etiqueta del eje X (L, M, X, J, V, S, D). */
    val label: String,
    val minutes: Int
)

/**
 * Métrica seleccionable en la tarjeta de tendencia. Alterna qué dimensión se apila en las barras y
 * se muestra en el panel de detalle, SIN perder el día seleccionado.
 */
enum class TrendMetric(val selectorLabel: String, val detailSubtitle: String) {
    TIME("Tiempo total", "Tiempo total"),
    OPENINGS("Aperturas", "Aperturas totales")
}

/**
 * Colores EXACTOS de cada app en la gráfica de barras apiladas y su desglose. Son específicos del
 * gráfico (definidos por requisito de producto) y no coinciden con [ShortAppIdentity.brandColor].
 */
object TrendColors {
    val TIKTOK = Color(0xFFFFFFFF)         // blanco
    val INSTAGRAM_REELS = Color(0xFF9739B1) // morado
    val YOUTUBE_SHORTS = Color(0xFFFF0000)  // rojo

    fun forKey(catalogKey: String): Color = when (catalogKey) {
        ShortContentApps.INSTAGRAM_REELS.catalogKey -> INSTAGRAM_REELS
        ShortContentApps.YOUTUBE_SHORTS.catalogKey -> YOUTUBE_SHORTS
        else -> TIKTOK
    }
}

/** Uso de UNA app en UN día: minutos consumidos y número de aperturas. */
data class DayAppUsage(
    val app: ShortAppIdentity,
    val minutes: Int,
    val openings: Int
) {
    fun valueFor(metric: TrendMetric): Int = when (metric) {
        TrendMetric.TIME -> minutes
        TrendMetric.OPENINGS -> openings
    }
}

/**
 * Un día de la semana en la tendencia apilada: inicial del eje X, nombre completo para el panel y
 * el uso por app en ORDEN ESTRICTO (TikTok, Instagram Reels, YouTube Shorts).
 */
data class WeeklyDay(
    val label: String,    // L, M, X, J, V, S, D
    val fullName: String, // Lunes, Martes, …
    val perApp: List<DayAppUsage>
) {
    fun total(metric: TrendMetric): Int = perApp.sumOf { it.valueFor(metric) }
}

/** Minutos consumidos hoy en una app concreta (fila del desglose "Hoy"). */
data class AppDailyBreakdown(
    val app: ShortAppIdentity,
    val todayMinutes: Int
)

/** Sección "Hoy": total agregado de las tres apps + desglose individual. */
data class TodaySummary(
    val perApp: List<AppDailyBreakdown>
) {
    val totalMinutes: Int get() = perApp.sumOf { it.todayMinutes }
}

/**
 * Sección "Promedio diario": media diaria de los últimos 7 días frente a la de la semana anterior.
 * La diferencia relevante para la interfaz es la DIARIA, no la semanal total.
 */
data class DailyAverage(
    val thisWeekMinutesPerDay: Int,
    val lastWeekMinutesPerDay: Int
) {
    /** Negativo = se consume menos que la semana pasada (mejora); positivo = más. */
    val deltaMinutesPerDay: Int get() = thisWeekMinutesPerDay - lastWeekMinutesPerDay
    val improved: Boolean get() = deltaMinutesPerDay < 0
    val unchanged: Boolean get() = deltaMinutesPerDay == 0
}

/** Métricas de comportamiento de la fricción para una app (sección inferior del detalle). */
data class AppBehavior(
    /** Veces que se abrió el contenido corto (número de sesiones). */
    val openings: Int,
    /** Interrupciones que generó la fricción durante el consumo. */
    val interruptions: Int,
    /** Accesos cancelados: veces que el usuario eligió salir en la pantalla de fricción. */
    val cancelledAccesses: Int
)

/** Estado completo del detalle individual de una app. */
data class AppStatsDetail(
    val app: ShortAppIdentity,
    val todayMinutes: Int,
    val dailyAverageMinutes: Int,
    /** Tendencia de los últimos 7 días FILTRADA solo para esta app. */
    val trend: List<DailyPoint>,
    /** Comportamiento del día actual. */
    val behaviorToday: AppBehavior,
    /** Comportamiento de los últimos 7 días. */
    val behaviorWeek: AppBehavior
)

/** Estado de la pantalla principal "Estadísticas". */
data class StatisticsUiState(
    val today: TodaySummary,
    val dailyAverage: DailyAverage,
    /** Tendencia de los últimos 7 días: suma de las tres apps por día. */
    val trend: List<DailyPoint>,
    /** Tendencia apilada de los últimos 7 días: uso por app y día, para la gráfica interactiva. */
    val weeklyTrend: List<WeeklyDay>
) {
    /** Desglose "Hoy" por app, ordenado de mayor a menor consumo. */
    val perAppToday: List<AppDailyBreakdown>
        get() = today.perApp.sortedByDescending { it.todayMinutes }
}

/**
 * Fuente de datos mockeada de la sección de estadísticas. Reemplazable por un ViewModel respaldado
 * por Room sin tocar las pantallas: estas solo dependen de los modelos de arriba.
 */
object MockStatistics {

    private val weekLabels = listOf("L", "M", "X", "J", "V", "S", "D")
    private val weekFullNames =
        listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")

    /** Tendencia diaria (minutos/día) por app durante los últimos 7 días. */
    private val tiktokTrend = listOf(52, 41, 70, 38, 84, 61, 38)
    private val reelsTrend = listOf(18, 12, 25, 9, 31, 22, 12)
    private val shortsTrend = listOf(11, 6, 14, 4, 18, 9, 7)

    /** Aperturas/día por app durante los últimos 7 días (segunda métrica de la tendencia). */
    private val tiktokOpenings = listOf(14, 11, 19, 10, 24, 17, 11)
    private val reelsOpenings = listOf(6, 4, 8, 3, 10, 7, 4)
    private val shortsOpenings = listOf(3, 2, 4, 1, 5, 3, 2)

    private fun trendOf(minutesPerDay: List<Int>): List<DailyPoint> =
        weekLabels.mapIndexed { index, label -> DailyPoint(label, minutesPerDay[index]) }

    /** Tendencia agregada (suma de las tres apps) por día. */
    private val combinedTrend: List<DailyPoint> =
        weekLabels.mapIndexed { index, label ->
            DailyPoint(label, tiktokTrend[index] + reelsTrend[index] + shortsTrend[index])
        }

    /** Tendencia apilada: uso por app y día, en orden estricto TikTok → Reels → Shorts. */
    private val weeklyTrend: List<WeeklyDay> = weekLabels.indices.map { i ->
        WeeklyDay(
            label = weekLabels[i],
            fullName = weekFullNames[i],
            perApp = listOf(
                DayAppUsage(ShortContentApps.TIKTOK, tiktokTrend[i], tiktokOpenings[i]),
                DayAppUsage(ShortContentApps.INSTAGRAM_REELS, reelsTrend[i], reelsOpenings[i]),
                DayAppUsage(ShortContentApps.YOUTUBE_SHORTS, shortsTrend[i], shortsOpenings[i])
            )
        )
    }

    private fun averageOf(minutesPerDay: List<Int>): Int = minutesPerDay.average().toInt()

    val statisticsUiState: StatisticsUiState = StatisticsUiState(
        today = TodaySummary(
            perApp = listOf(
                AppDailyBreakdown(ShortContentApps.TIKTOK, todayMinutes = 38),
                AppDailyBreakdown(ShortContentApps.INSTAGRAM_REELS, todayMinutes = 12),
                AppDailyBreakdown(ShortContentApps.YOUTUBE_SHORTS, todayMinutes = 7)
            )
        ),
        dailyAverage = DailyAverage(
            thisWeekMinutesPerDay = 71,   // 1h 11m
            lastWeekMinutesPerDay = 102   // 1h 42m  → ↓ 31 min por día
        ),
        trend = combinedTrend,
        weeklyTrend = weeklyTrend
    )

    private val behaviorByKey: Map<String, AppBehavior> = mapOf(
        ShortContentApps.TIKTOK.catalogKey to AppBehavior(openings = 24, interruptions = 9, cancelledAccesses = 5),
        ShortContentApps.INSTAGRAM_REELS.catalogKey to AppBehavior(openings = 11, interruptions = 4, cancelledAccesses = 3),
        ShortContentApps.YOUTUBE_SHORTS.catalogKey to AppBehavior(openings = 6, interruptions = 2, cancelledAccesses = 1)
    )

    private val todayByKey: Map<String, Int> =
        statisticsUiState.today.perApp.associate { it.app.catalogKey to it.todayMinutes }

    private val trendByKey: Map<String, List<DailyPoint>> = mapOf(
        ShortContentApps.TIKTOK.catalogKey to trendOf(tiktokTrend),
        ShortContentApps.INSTAGRAM_REELS.catalogKey to trendOf(reelsTrend),
        ShortContentApps.YOUTUBE_SHORTS.catalogKey to trendOf(shortsTrend)
    )

    private val averageByKey: Map<String, Int> = mapOf(
        ShortContentApps.TIKTOK.catalogKey to averageOf(tiktokTrend),
        ShortContentApps.INSTAGRAM_REELS.catalogKey to averageOf(reelsTrend),
        ShortContentApps.YOUTUBE_SHORTS.catalogKey to averageOf(shortsTrend)
    )

    /** Detalle individual de una app por su catalogKey. */
    fun detailFor(catalogKey: String): AppStatsDetail {
        val app = ShortContentApps.byKey(catalogKey)
        val week = behaviorByKey[catalogKey] ?: AppBehavior(0, 0, 0)
        return AppStatsDetail(
            app = app,
            todayMinutes = todayByKey[catalogKey] ?: 0,
            dailyAverageMinutes = averageByKey[catalogKey] ?: 0,
            trend = trendByKey[catalogKey] ?: emptyList(),
            behaviorToday = AppBehavior(week.openings / 7, week.interruptions / 7, week.cancelledAccesses / 7),
            behaviorWeek = week
        )
    }
}
