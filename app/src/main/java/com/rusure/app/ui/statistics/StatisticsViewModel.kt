package com.rusure.app.ui.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rusure.app.data.local.entity.UsageSession
import com.rusure.app.data.repository.RuSureRepository
import com.rusure.app.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

/** Estado completo de la sección de estadísticas: pantalla principal + detalle por app. */
data class StatisticsScreenData(
    val main: StatisticsUiState,
    val details: Map<String, AppStatsDetail>
)

/**
 * Agrega las sesiones reales de uso en los modelos de la sección de estadísticas. Ventana fija de 14
 * días naturales (hoy + 13 previos) capturada al crear el ViewModel: cubre "hoy", "últimos 7 días" y
 * "7 días anteriores". Las sesiones se atribuyen al día local de su inicio.
 */
class StatisticsViewModel(repository: RuSureRepository) : ViewModel() {

    companion object {
        private const val DAYS = 14
        private const val WEEK = 7

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { StatisticsViewModel(container.repository) }
        }
    }

    private val now = System.currentTimeMillis()
    private val dayStarts: LongArray = computeDayStarts(now)

    val state: StateFlow<StatisticsScreenData> =
        repository.observeSessionsSince(dayStarts.first())
            .map { aggregate(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = aggregate(emptyList())
            )

    /** Detalle seguro para una app (las 3 claves siempre existen; fallback defensivo). */
    fun detailFor(catalogKey: String, data: StatisticsScreenData): AppStatsDetail =
        data.details[catalogKey] ?: emptyDetail(ShortContentApps.byKey(catalogKey))

    // --- Agregación ---

    private fun aggregate(sessions: List<UsageSession>): StatisticsScreenData {
        val apps = ShortContentApps.all
        val keys = apps.map { it.catalogKey }.toSet()

        val mins = Array(DAYS) { HashMap<String, Long>() }
        val opens = Array(DAYS) { HashMap<String, Int>() }
        val intr = Array(DAYS) { HashMap<String, Int>() }
        val canc = Array(DAYS) { HashMap<String, Int>() }

        for (s in sessions) {
            if (s.catalogKey !in keys) continue
            val i = dayIndexOf(s.startEpochMillis)
            if (i < 0) continue
            mins[i].merge(s.catalogKey, s.activeDurationMillis, Long::plus)
            opens[i].merge(s.catalogKey, 1, Int::plus)
            intr[i].merge(s.catalogKey, s.interruptions, Int::plus)
            canc[i].merge(s.catalogKey, s.cancelledAccesses, Int::plus)
        }

        fun minutesOf(day: Int, key: String): Int =
            Math.round((mins[day][key] ?: 0L) / 60000.0).toInt()

        val today = DAYS - 1
        val weekRange = (DAYS - WEEK) until DAYS

        val todaySummary = TodaySummary(
            perApp = apps.map { AppDailyBreakdown(it, minutesOf(today, it.catalogKey)) }
        )

        val weeklyTrend = weekRange.map { day ->
            val dow = dayOfWeek(dayStarts[day])
            WeeklyDay(
                label = initialOf(dow),
                fullName = fullNameOf(dow),
                perApp = apps.map { app ->
                    DayAppUsage(app, minutesOf(day, app.catalogKey), opens[day][app.catalogKey] ?: 0)
                }
            )
        }

        val combinedTrend = weekRange.map { day ->
            DailyPoint(initialOf(dayOfWeek(dayStarts[day])), apps.sumOf { minutesOf(day, it.catalogKey) })
        }

        fun rangeMinutes(range: IntRange): Int =
            range.sumOf { day -> apps.sumOf { minutesOf(day, it.catalogKey) } }

        val dailyAverage = DailyAverage(
            thisWeekMinutesPerDay = rangeMinutes(weekRange) / WEEK,
            lastWeekMinutesPerDay = rangeMinutes(0 until DAYS - WEEK) / WEEK
        )

        val details = apps.associate { app ->
            val k = app.catalogKey
            val trend = weekRange.map { day -> DailyPoint(initialOf(dayOfWeek(dayStarts[day])), minutesOf(day, k)) }
            app.catalogKey to AppStatsDetail(
                app = app,
                todayMinutes = minutesOf(today, k),
                dailyAverageMinutes = weekRange.sumOf { minutesOf(it, k) } / WEEK,
                trend = trend,
                behaviorToday = AppBehavior(
                    openings = opens[today][k] ?: 0,
                    interruptions = intr[today][k] ?: 0,
                    cancelledAccesses = canc[today][k] ?: 0
                ),
                behaviorWeek = AppBehavior(
                    openings = weekRange.sumOf { opens[it][k] ?: 0 },
                    interruptions = weekRange.sumOf { intr[it][k] ?: 0 },
                    cancelledAccesses = weekRange.sumOf { canc[it][k] ?: 0 }
                )
            )
        }

        return StatisticsScreenData(
            main = StatisticsUiState(
                today = todaySummary,
                dailyAverage = dailyAverage,
                trend = combinedTrend,
                weeklyTrend = weeklyTrend
            ),
            details = details
        )
    }

    private fun dayIndexOf(t: Long): Int {
        for (i in DAYS - 1 downTo 0) if (t >= dayStarts[i]) return i
        return -1
    }

    private fun emptyDetail(app: ShortAppIdentity) = AppStatsDetail(
        app = app,
        todayMinutes = 0,
        dailyAverageMinutes = 0,
        trend = emptyList(),
        behaviorToday = AppBehavior(0, 0, 0),
        behaviorWeek = AppBehavior(0, 0, 0)
    )
}

/** Inicios de día local (epoch millis) para hoy y los 13 días previos, en orden ascendente. */
private fun computeDayStarts(now: Long): LongArray {
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -13)
    }
    return LongArray(14) { cal.timeInMillis.also { cal.add(Calendar.DAY_OF_YEAR, 1) } }
}

private fun dayOfWeek(millis: Long): Int =
    Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)

private fun initialOf(dow: Int): String = when (dow) {
    Calendar.MONDAY -> "L"; Calendar.TUESDAY -> "M"; Calendar.WEDNESDAY -> "X"
    Calendar.THURSDAY -> "J"; Calendar.FRIDAY -> "V"; Calendar.SATURDAY -> "S"
    else -> "D"
}

private fun fullNameOf(dow: Int): String = when (dow) {
    Calendar.MONDAY -> "Lunes"; Calendar.TUESDAY -> "Martes"; Calendar.WEDNESDAY -> "Miércoles"
    Calendar.THURSDAY -> "Jueves"; Calendar.FRIDAY -> "Viernes"; Calendar.SATURDAY -> "Sábado"
    else -> "Domingo"
}
