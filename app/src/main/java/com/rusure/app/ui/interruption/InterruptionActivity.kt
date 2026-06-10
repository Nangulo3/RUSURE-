package com.rusure.app.ui.interruption

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
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

        // Edge-to-edge: la ventana dibuja por DETRÁS de las barras de estado y navegación, de modo
        // que el scrim oscuro de Compose (fillMaxSize) cubra la pantalla completa. Sin esto, el
        // contenido solo ocupaba el área entre barras y dejaba ver la app objetivo en los bordes,
        // produciendo el "recuadro" translúcido en el centro.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // El gesto/botón atrás no permite colarse a la app objetivo.
        onBackPressedDispatcher.addCallback(this) { /* no-op */ }

        // Difumina el contenido de la app que queda detrás (efecto frosted glass nativo, API 31+).
        // Donde el sistema no soporte el blur entre ventanas, el scrim reforzado del Compose oculta
        // igualmente el contenido (fallback funcional).
        val blurActive = applyBehindBlur()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.finishEvents.collect { finish() }
            }
        }

        setContent {
            RuSureTheme {
                MindfulInterruptionScreen(
                    viewModel = viewModel,
                    glassBlurActive = blurActive
                )
            }
        }
    }

    /**
     * Aplica el desenfoque de la ventana subyacente (frosted glass).
     *
     * @return `true` si el blur entre ventanas quedó realmente activo; `false` si la versión de
     *   Android es anterior a 12 o el sistema tiene el blur deshabilitado, para que la UI use el
     *   scrim oscuro reforzado como fallback.
     */
    private fun applyBehindBlur(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        // Radio dentro del rango exigido [16dp, 24dp]: 22dp difumina lo suficiente para hacer
        // ilegible el contenido sin un coste de render excesivo.
        val radiusPx = (BLUR_RADIUS_DP * resources.displayMetrics.density).toInt()
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = radiusPx }
        return windowManager.isCrossWindowBlurEnabled
    }

    private companion object {
        /** Radio del frosted glass en dp (debe permanecer entre 16dp y 24dp). */
        const val BLUR_RADIUS_DP = 22f
    }
}
