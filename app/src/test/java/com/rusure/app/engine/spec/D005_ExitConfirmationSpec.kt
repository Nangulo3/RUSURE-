package com.rusure.app.engine.spec

import com.rusure.app.domain.engine.GateEngine
import com.rusure.app.domain.gate.GateDecision
import com.rusure.app.engine.KEY_GLOBAL
import com.rusure.app.engine.PKG_GLOBAL
import com.rusure.app.engine.PKG_IME
import com.rusure.app.engine.PKG_OTHER_APP
import com.rusure.app.engine.PKG_SYSTEM_UI
import com.rusure.app.engine.globalTarget
import com.rusure.app.engine.newHarness
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **D-005** — "Salir de la app objetivo" se confirma con retardo, no con el primer evento.
 *
 * Comportamiento esperado: *"Un evento de accesibilidad con un `packageName` distinto no debe bastar
 * para dar por hecho que el usuario salió de la app objetivo. La salida debe confirmarse con
 * retardo: transcurridos X segundos, comprobar si la app objetivo sigue (o no) siendo la ventana
 * activa, y solo entonces tratarla como salida."*
 *
 * Y **D-002**, cuyo comportamiento esperado es que entrar al buscador (que abre el teclado) *"es
 * navegación interna y, por sí solo, no debe provocar la pantalla intermedia"*.
 */
class D005_ExitConfirmationSpec {

    /**
     * D-002 + D-005: una ventana auxiliar sobre un objetivo global en uso, durante mucho más que la
     * ventana de confirmación y que la gracia de segundo plano juntas, no debe provocar fricción ni
     * cerrar la sesión: la app objetivo conserva su ventana de aplicación, así que nunca salió.
     *
     * Es el escenario del buscador de TikTok (`DEEP_INIT_REPORT.md` §26) sin nombrar la app.
     */
    @Test
    fun D005_auxWindowOverGlobalTarget_30s_noGate() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.windows.appWindows += PKG_GLOBAL

        // El usuario abre la app vigilada y acepta la fricción inicial.
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        h.engine.onDecision(GateDecision.Continue(KEY_GLOBAL))
        runCurrent()
        val sessionId = h.store.openSession(KEY_GLOBAL)!!.id
        assertEquals("la apertura muestra fricción una vez", 1, h.effects.frictionLaunches)

        // Aparece el teclado y permanece 30 s: la app sigue teniendo ventana de aplicación.
        h.engine.onWindowStateChanged(PKG_IME)
        h.advanceWithTicks(30_000)

        assertEquals("una ventana auxiliar no debe disparar fricción", 1, h.effects.frictionLaunches)
        assertEquals("no debe abrirse otra sesión", 1, h.store.sessions.size)
        assertNull("la sesión debe seguir abierta", h.store.session(sessionId)!!.endMillis)
    }

    /**
     * D-005: si la ventana auxiliar se cierra *antes* de que venza la ventana de confirmación, la
     * salida ni llega a evaluarse: se cancela por el regreso y no se suspende nada.
     */
    @Test
    fun D005_auxWindowClosedBeforeConfirmation_exitCancelled_noGate() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.windows.appWindows += PKG_GLOBAL
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        h.engine.onDecision(GateDecision.Continue(KEY_GLOBAL))
        runCurrent()
        val sessionId = h.store.openSession(KEY_GLOBAL)!!.id

        // El teclado aparece y se cierra dentro de la ventana de confirmación.
        h.engine.onWindowStateChanged(PKG_IME)
        h.advanceWithTicks(GateEngine.FOREGROUND_EXIT_CONFIRM_MILLIS - GateEngine.TICK_MILLIS)
        h.engine.onWindowStateChanged(PKG_GLOBAL)

        // Aunque después pase mucho tiempo, no quedó ninguna salida en observación.
        h.advanceWithTicks(30_000)

        assertEquals("volver antes de la confirmación no debe gatear", 1, h.effects.frictionLaunches)
        assertNull("la sesión debe seguir abierta", h.store.session(sessionId)!!.endMillis)
    }

    /**
     * D-005: si al vencer la confirmación la app objetivo **ya no tiene** ventana de aplicación, la
     * salida fue real. El objetivo queda suspendido con la gracia corta y, pasada esta, su sesión se
     * cierra; volver después cuenta como apertura nueva.
     */
    @Test
    fun D005_noApplicationWindowAfterConfirmation_targetSuspendedAndClosed() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.windows.appWindows += PKG_GLOBAL
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        h.engine.onDecision(GateDecision.Continue(KEY_GLOBAL))
        runCurrent()
        val sessionId = h.store.openSession(KEY_GLOBAL)!!.id

        // El usuario se va de verdad a otra aplicación: la objetivo pierde su ventana.
        h.windows.appWindows -= PKG_GLOBAL
        h.windows.appWindows += PKG_OTHER_APP
        h.engine.onWindowStateChanged(PKG_OTHER_APP)

        // Confirmación + gracia: la sesión debe cerrarse.
        h.advanceWithTicks(
            GateEngine.FOREGROUND_EXIT_CONFIRM_MILLIS +
                GateEngine.BACKGROUND_GRACE_MILLIS +
                2 * GateEngine.TICK_MILLIS
        )

        assertNotNull(
            "una salida real debe cerrar la sesión al vencer la gracia",
            h.store.session(sessionId)!!.endMillis
        )

        // Y el regreso cuenta como apertura nueva.
        h.windows.appWindows += PKG_GLOBAL
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()

        assertEquals("volver tras una salida real debe gatear", 2, h.effects.frictionLaunches)
        assertEquals("y abrir una sesión nueva", 2, h.store.sessions.size)
    }

    /**
     * D-005: la interfaz del sistema (persiana, notificaciones) es también una ventana auxiliar. La
     * regla no depende de qué paquete sea, solo de que el objetivo conserve su ventana de aplicación
     * (regla 4 de `CLAUDE.md`: nada de casos por app).
     */
    @Test
    fun D005_systemUiWindow_overTarget_noGate() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.windows.appWindows += PKG_GLOBAL
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        h.engine.onDecision(GateDecision.Continue(KEY_GLOBAL))
        runCurrent()

        h.engine.onWindowStateChanged(PKG_SYSTEM_UI)
        h.advanceWithTicks(15_000)

        assertEquals("la persiana no es salir de la app", 1, h.effects.frictionLaunches)
        assertEquals(1, h.store.sessions.size)
    }
}
