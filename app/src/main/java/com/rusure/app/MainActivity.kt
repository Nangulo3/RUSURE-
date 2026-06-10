package com.rusure.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rusure.app.ui.navigation.MainNavHost
import com.rusure.app.ui.theme.RuSureTheme

/**
 * Punto de entrada de la UI. Toda la navegación (barra inferior Menú / Estadísticas y sus
 * sub-pantallas) vive en [MainNavHost]; aquí solo se inyecta el [AppContainer] y el tema. La
 * navegación es por estado en memoria (sin Navigation-Compose), coherente con esta toolchain.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as RuSureApp).container

        setContent {
            RuSureTheme {
                MainNavHost(container = container)
            }
        }
    }
}
