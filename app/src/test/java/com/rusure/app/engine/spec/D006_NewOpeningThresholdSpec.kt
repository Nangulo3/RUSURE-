package com.rusure.app.engine.spec

import com.rusure.app.domain.engine.GateEngine
import com.rusure.app.domain.gate.GateDecision
import com.rusure.app.engine.EngineHarness
import com.rusure.app.engine.KEY_GLOBAL
import com.rusure.app.engine.PKG_GLOBAL
import com.rusure.app.engine.PKG_OTHER_APP
import com.rusure.app.engine.globalTarget
import com.rusure.app.engine.newHarness
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **D-006** — El umbral de "apertura nueva" tras salir debe ser configurable por el usuario.
 *
 * Comportamiento esperado: *"El tiempo que el usuario debe pasar fuera de la app objetivo para que
 * el regreso cuente como apertura nueva (y vuelva a mostrarse la fricción) deja de ser una constante
 * interna: se expone en la configuración de cada objetivo, con un valor por defecto."*
 *
 * El **valor** sigue siendo pregunta abierta (#2b), así que estos tests **no fijan ningún número**:
 * derivan el umbral de las constantes del motor y comprueban la *forma* de la regla —por debajo
 * reanuda, por encima es apertura nueva—. Cuando el umbral pase a ser un ajuste del objetivo, estos
 * tests deberán leerlo de la configuración y no de las constantes.
 */
class D006_NewOpeningThresholdSpec {

    /**
     * El umbral efectivo hoy no es la gracia sola: la gracia empieza a contar **después** de la
     * confirmación de la salida (D-005), así que son las dos sumadas.
     */
    private val threshold =
        GateEngine.FOREGROUND_EXIT_CONFIRM_MILLIS + GateEngine.BACKGROUND_GRACE_MILLIS

    /** Un periodo de ticker de margen: el barrido solo puede actuar en un tick. */
    private val margin = GateEngine.TICK_MILLIS

    private fun TestScope.targetInUse(): EngineHarness {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.windows.appWindows += PKG_GLOBAL
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        h.engine.onDecision(GateDecision.Continue(KEY_GLOBAL))
        runCurrent()
        return h
    }

    /** Sale de verdad de la app objetivo: pierde su ventana de aplicación. */
    private fun EngineHarness.leaveForRealApp() {
        windows.appWindows -= PKG_GLOBAL
        windows.appWindows += PKG_OTHER_APP
        engine.onWindowStateChanged(PKG_OTHER_APP)
    }

    /** Vuelve a la app objetivo. */
    private fun EngineHarness.returnToTarget() {
        windows.appWindows += PKG_GLOBAL
        engine.onWindowStateChanged(PKG_GLOBAL)
    }

    /**
     * D-006: volviendo **justo por debajo** del umbral, el regreso no es una apertura nueva: se
     * reanuda sin fricción y sobre la misma sesión.
     */
    @Test
    fun D006_returnJustBelowThreshold_resumesWithoutFriction() = runTest {
        val h = targetInUse()
        val sessionId = h.store.openSession(KEY_GLOBAL)!!.id

        h.leaveForRealApp()
        h.advanceWithTicks(threshold - margin)
        h.returnToTarget()
        runCurrent()

        assertEquals(
            "volver antes del umbral (${threshold - margin} ms) debe reanudar sin fricción",
            1,
            h.effects.frictionLaunches
        )
        assertEquals("y sobre la misma sesión", 1, h.store.sessions.size)
        assertNull(h.store.session(sessionId)!!.endMillis)
    }

    /**
     * D-006: volviendo **justo por encima** del umbral, el regreso es una apertura nueva: fricción y
     * sesión nueva.
     */
    @Test
    fun D006_returnJustAboveThreshold_countsAsNewOpening() = runTest {
        val h = targetInUse()
        val sessionId = h.store.openSession(KEY_GLOBAL)!!.id

        h.leaveForRealApp()
        h.advanceWithTicks(threshold + margin)
        h.returnToTarget()
        runCurrent()

        assertEquals(
            "volver pasado el umbral (${threshold + margin} ms) debe gatear de nuevo",
            2,
            h.effects.frictionLaunches
        )
        assertEquals("con una sesión nueva", 2, h.store.sessions.size)
        assertEquals(
            "y la anterior cerrada",
            true,
            h.store.session(sessionId)!!.endMillis != null
        )
    }
}
