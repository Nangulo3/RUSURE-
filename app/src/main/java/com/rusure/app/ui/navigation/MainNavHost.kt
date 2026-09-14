package com.rusure.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rusure.app.di.AppContainer
import com.rusure.app.ui.config.TargetConfigScreen
import com.rusure.app.ui.config.TargetConfigViewModel
import com.rusure.app.ui.dashboard.DashboardScreen
import com.rusure.app.ui.dashboard.DashboardViewModel
import com.rusure.app.ui.statistics.AppBreakdownScreen
import com.rusure.app.ui.statistics.AppDetailScreen
import com.rusure.app.ui.statistics.StatisticsScreen
import com.rusure.app.ui.statistics.StatisticsViewModel

/**
 * Destinos de la app. Las dos pestañas raíz ([Home] y [Stats]) muestran la [AppBottomBar]; el resto
 * son sub-pantallas a pantalla completa que se apilan sobre la pestaña de origen y se cierran con
 * "atrás". Navegación por estado en memoria (sin Navigation-Compose), coherente con la toolchain.
 */
private sealed interface Destination {
    data object Home : Destination
    data object Stats : Destination
    data class Config(val catalogKey: String) : Destination
    data object StatsBreakdown : Destination
    data class StatsDetail(val catalogKey: String) : Destination
}

/**
 * Punto de entrada de la interfaz: aloja la barra de navegación inferior (Menú / Estadísticas) y la
 * navegación entre las pestañas y sus sub-pantallas.
 */
@Composable
fun MainNavHost(container: AppContainer) {
    var destination: Destination by rememberSaveable(stateSaver = DestinationSaver) {
        mutableStateOf(Destination.Home)
    }

    val statsViewModel: StatisticsViewModel = viewModel(factory = StatisticsViewModel.factory(container))
    val statsData by statsViewModel.state.collectAsState()

    val onSelectTab: (MainTab) -> Unit = { tab ->
        destination = when (tab) {
            MainTab.MENU -> Destination.Home
            MainTab.STATISTICS -> Destination.Stats
        }
    }

    when (val current = destination) {
        Destination.Home -> {
            val dashboardViewModel: DashboardViewModel =
                viewModel(factory = DashboardViewModel.factory(container))
            DashboardScreen(
                viewModel = dashboardViewModel,
                onConfigure = { catalogKey -> destination = Destination.Config(catalogKey) },
                bottomBar = { AppBottomBar(selected = MainTab.MENU, onSelect = onSelectTab) }
            )
        }

        Destination.Stats -> {
            // "Atrás" desde la pestaña de estadísticas vuelve al menú (no cierra la app).
            BackHandler { destination = Destination.Home }
            StatisticsScreen(
                uiState = statsData.main,
                onOpenAppBreakdown = { destination = Destination.StatsBreakdown },
                bottomBar = { AppBottomBar(selected = MainTab.STATISTICS, onSelect = onSelectTab) }
            )
        }

        is Destination.Config -> {
            val configViewModel: TargetConfigViewModel = viewModel(
                key = "config_${current.catalogKey}",
                factory = TargetConfigViewModel.factory(container, current.catalogKey)
            )
            TargetConfigScreen(
                viewModel = configViewModel,
                onBack = { destination = Destination.Home }
            )
        }

        Destination.StatsBreakdown -> AppBreakdownScreen(
            items = statsData.main.perAppToday,
            onBack = { destination = Destination.Stats },
            onOpenApp = { catalogKey -> destination = Destination.StatsDetail(catalogKey) }
        )

        is Destination.StatsDetail -> AppDetailScreen(
            detail = statsViewModel.detailFor(current.catalogKey, statsData),
            onBack = { destination = Destination.StatsBreakdown }
        )
    }
}

/** Serializa el destino actual para sobrevivir cambios de configuración (rotación, etc.). */
private val DestinationSaver = Saver<Destination, String>(
    save = { destination ->
        when (destination) {
            Destination.Home -> "home"
            Destination.Stats -> "stats"
            Destination.StatsBreakdown -> "stats_breakdown"
            is Destination.Config -> "config:${destination.catalogKey}"
            is Destination.StatsDetail -> "stats_detail:${destination.catalogKey}"
        }
    },
    restore = { value ->
        when {
            value == "home" -> Destination.Home
            value == "stats" -> Destination.Stats
            value == "stats_breakdown" -> Destination.StatsBreakdown
            value.startsWith("config:") -> Destination.Config(value.removePrefix("config:"))
            value.startsWith("stats_detail:") ->
                Destination.StatsDetail(value.removePrefix("stats_detail:"))
            else -> Destination.Home
        }
    }
)
