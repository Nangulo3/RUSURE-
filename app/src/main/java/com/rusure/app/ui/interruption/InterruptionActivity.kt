package com.rusure.app.ui.interruption

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rusure.app.RuSureApp
import com.rusure.app.ui.theme.RuSureTheme
import kotlinx.coroutines.launch

/**
 * Aloja la pantalla de fricción [MindfulInterruptionScreen] por encima de la app objetivo.
 * Es translúcida, sin historial y bloquea el botón atrás para que la única salida sea una
 * decisión consciente ("Continuar" o "No quiero continuar").
 */
class InterruptionActivity : ComponentActivity() {

    private val viewModel: InterruptionViewModel by viewModels {
        InterruptionViewModel.factory((application as RuSureApp).container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // El gesto/botón atrás no permite colarse a la app objetivo.
        onBackPressedDispatcher.addCallback(this) { /* no-op */ }

        // Difumina el contenido de la app que queda detrás (API 31+). Donde el sistema no
        // soporte el blur entre ventanas, el scrim del Compose sigue ocultando el contenido.
        applyBehindBlur()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.finishEvents.collect { finish() }
            }
        }

        setContent {
            RuSureTheme {
                MindfulInterruptionScreen(viewModel = viewModel)
            }
        }
    }

    private fun applyBehindBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // Radio en píxeles equivalente a ~32dp; suficiente para hacer ilegible el contenido.
        val radiusPx = (32 * resources.displayMetrics.density).toInt()
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = radiusPx }
    }
}
