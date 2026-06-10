package com.rusure.app.ui.navigation

import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rusure.app.ui.statistics.BarsIcon
import com.rusure.app.ui.statistics.HomeIcon

/** Pestañas raíz de la app. Por ahora solo dos: el menú (Home) y las estadísticas. */
enum class MainTab { MENU, STATISTICS }

/**
 * Barra de navegación inferior de la app. Contiene únicamente las dos secciones raíz: "Menú"
 * (dashboard de objetivos) y "Estadísticas". Está pensada para crecer con más pestañas sin cambiar
 * su contrato: recibe la pestaña [selected] y notifica la elegida vía [onSelect].
 */
@Composable
fun AppBottomBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        val itemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        )

        NavigationBarItem(
            selected = selected == MainTab.MENU,
            onClick = { onSelect(MainTab.MENU) },
            icon = {
                HomeIcon(modifier = Modifier.size(24.dp), color = LocalContentColor.current)
            },
            label = { Text("Menú") },
            colors = itemColors
        )

        NavigationBarItem(
            selected = selected == MainTab.STATISTICS,
            onClick = { onSelect(MainTab.STATISTICS) },
            icon = {
                // Glifo de barras propio; toma el color de contenido del item (seleccionado/no).
                BarsIcon(modifier = Modifier.size(24.dp), color = LocalContentColor.current)
            },
            label = { Text("Estadísticas") },
            colors = itemColors
        )
    }
}
