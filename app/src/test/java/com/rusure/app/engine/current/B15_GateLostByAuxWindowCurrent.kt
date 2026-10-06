package com.rusure.app.engine.current

import com.rusure.app.domain.engine.GateEngine
import com.rusure.app.domain.gate.GateDecision
import com.rusure.app.engine.KEY_GLOBAL
import com.rusure.app.engine.PKG_GLOBAL
import com.rusure.app.engine.PKG_IME
import com.rusure.app.engine.globalTarget
import com.rusure.app.engine.newHarness
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * # COMPORTAMIENTO ACTUAL, NO ESPECIFICACIÓN
 *
 * Fija la cadena del bug **B15** (`docs/DEEP_INIT_REPORT.md` §28, hallazgo H1 de
 * `docs/baseline/F1_summary.md`): con el objetivo en `GATING` y la pantalla de fricción encima, un
 * evento de una ventana auxiliar hace que la salida se confirme —porque la propia pantalla de
 * fricción oculta la ventana de la app— y el gate se cierra con su sesión.
 *
 * **D-012 decide lo contrario** y está CONFIRMADA, pero **no implementada**: mientras la pantalla de
 * fricción esté visible, una ventana auxiliar no debe cerrar el gate ni abandonar la sesión. Cuando
 * se implemente, estos tests se reescriben en `spec/`.
 *
 * Que estos tests **pasen** significa que el bug sigue ahí, no que el comportamiento sea correcto.
 */
class B15_GateLostByAuxWindowCurrent {

    /**
     * B15: durante `GATING`, una ventana auxiliar cuya salida se confirma cierra el gate y la sesión.
     * La decisión posterior del usuario llega sobre un runtime ya cerrado.
     */
    @Test
    fun current_auxWindowDuringGating_closesGateAndSession() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        // La app objetivo NO figura con ventana de aplicación: es lo que ocurre en el dispositivo
        // mientras la Activity translúcida de fricción la ocluye.
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        val sessionId = h.store.openSession(KEY_GLOBAL)!!.id
        assertEquals("la apertura abre sesión y muestra fricción", 1, h.effects.frictionLaunches)

        // Llega el evento del teclado ~1 s después de aparecer la pantalla, como en el dispositivo.
        h.advanceWithTicks(GateEngine.TICK_MILLIS)
        h.engine.onWindowStateChanged(PKG_IME)
        h.advanceWithTicks(GateEngine.FOREGROUND_EXIT_CONFIRM_MILLIS + GateEngine.TICK_MILLIS)

        assertNotNull(
            "hoy el gate se cierra solo: la sesión termina sin que el usuario decidiera",
            h.store.session(sessionId)!!.endMillis
        )
    }

    /**
     * B15, consecuencia: tras el cierre, el "Continuar" del usuario deja el objetivo en `ALLOWED`
     * **sin sesión**, así que todo el tiempo de uso posterior se descarta en silencio y no se abre
     * ninguna sesión nueva.
     *
     * Verificado en dispositivo: 6 veces en 20 minutos (`F1_summary.md` H1) y ~7 minutos de uso real
     * sin contabilizar.
     */
    @Test
    fun current_continueAfterLostGate_allowedWithoutSession_timeDiscarded() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        val sessionId = h.store.openSession(KEY_GLOBAL)!!.id

        h.advanceWithTicks(GateEngine.TICK_MILLIS)
        h.engine.onWindowStateChanged(PKG_IME)
        h.advanceWithTicks(GateEngine.FOREGROUND_EXIT_CONFIRM_MILLIS + GateEngine.TICK_MILLIS)

        // El usuario pulsa Continuar sobre una pantalla cuyo gate ya no existe.
        h.engine.onDecision(GateDecision.Continue(KEY_GLOBAL))
        runCurrent()

        // La app vuelve a primer plano y se usa un rato.
        h.windows.appWindows += PKG_GLOBAL
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        h.advanceWithTicks(60_000)

        assertEquals("no se abre ninguna sesión nueva", 1, h.store.sessions.size)
        assertEquals(
            "y el tiempo de uso se descarta porque el runtime no tiene sesión",
            0L,
            h.store.session(sessionId)!!.activeMillis
        )
    }

    /**
     * B15, consecuencia sobre "accesos cancelados" (verificada en dispositivo en la fase F3, paso 6):
     * si el usuario pulsa "No quiero continuar" después de que el gate se haya cerrado,
     * `incrementCancelled` no encuentra sesión y la cancelación **no se registra**.
     */
    @Test
    fun current_leaveAfterLostGate_cancellationNotRecorded() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        val sessionId = h.store.openSession(KEY_GLOBAL)!!.id

        h.advanceWithTicks(GateEngine.TICK_MILLIS)
        h.engine.onWindowStateChanged(PKG_IME)
        h.advanceWithTicks(GateEngine.FOREGROUND_EXIT_CONFIRM_MILLIS + GateEngine.TICK_MILLIS)

        h.engine.onDecision(GateDecision.Leave(KEY_GLOBAL))
        runCurrent()

        assertEquals(
            "la cancelación se pierde: el dashboard la cuenta como aperturas evitadas",
            0,
            h.store.session(sessionId)!!.cancelled
        )
    }
}
