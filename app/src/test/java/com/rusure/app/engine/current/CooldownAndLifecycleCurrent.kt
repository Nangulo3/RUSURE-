package com.rusure.app.engine.current

import com.rusure.app.domain.engine.GateEngine
import com.rusure.app.domain.engine.SectionScan
import com.rusure.app.domain.gate.GateDecision
import com.rusure.app.engine.KEY_GLOBAL
import com.rusure.app.engine.KEY_SECTION
import com.rusure.app.engine.PKG_GLOBAL
import com.rusure.app.engine.PKG_OTHER_APP
import com.rusure.app.engine.PKG_SECTION
import com.rusure.app.engine.globalTarget
import com.rusure.app.engine.newHarness
import com.rusure.app.engine.sectionTarget
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * # COMPORTAMIENTO ACTUAL, NO ESPECIFICACIÓN
 *
 * Fija el cooldown global de gates y los estados degenerados del ciclo de vida de un objetivo.
 * Referencias: `docs/DEEP_INIT_REPORT.md` §16 **T2** (cooldown de 2,5 s, global y no por objetivo),
 * §13 y **B7** (`LIMIT_REACHED` atascado cuando el cooldown se come su gate), **B2** con §33 **#7**
 * (desactivar un objetivo en uso) y **E8** con §33 **#9** (`GATING` sin pantalla).
 */
class CooldownAndLifecycleCurrent {

    /**
     * **T2** — el cooldown de 2,5 s es **global**, no por objetivo: un segundo objetivo que debería
     * gatear dentro de la ventana pierde su gate en silencio y se queda como estaba.
     */
    @Test
    fun current_gateCooldownIsGlobal_secondTargetLosesItsGate() = runTest {
        val h = newHarness()
        val section = sectionTarget()
        h.engine.onTargetsChanged(listOf(globalTarget(), section))
        h.windows.appWindows += PKG_GLOBAL
        h.windows.appWindows += PKG_SECTION

        // Primer objetivo: gatea.
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        assertEquals(1, h.effects.frictionLaunches)

        // Dentro de los 2,5 s, el segundo objetivo también debería gatear.
        h.sections.result = SectionScan.Detected(section)
        h.engine.onWindowStateChanged(PKG_SECTION)
        runCurrent()

        assertEquals(
            "el segundo gate se descarta por un cooldown que no es suyo",
            1,
            h.effects.frictionLaunches
        )
        assertNull("y el segundo objetivo no llega a abrir sesión", h.store.openSession(KEY_SECTION))
    }

    /**
     * **B7 / §13** — al alcanzar el límite de uso continuo el estado pasa a `LIMIT_REACHED` y se
     * dispara un gate `RE_ENTRY`. Si ese gate cae dentro del cooldown global, se descarta y el
     * objetivo **se queda atascado** en `LIMIT_REACHED`: ni gatea (los handlers lo ignoran) ni cuenta
     * tiempo. Solo sale de ahí abandonando el primer plano.
     */
    @Test
    fun current_limitReachedSwallowedByCooldown_targetStuck() = runTest {
        val h = newHarness()
        // Límite de 1 s: se alcanza dentro del cooldown de 2,5 s del gate de apertura.
        h.engine.onTargetsChanged(listOf(globalTarget(continuousUsageLimitSeconds = 1)))
        h.windows.appWindows += PKG_GLOBAL

        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        h.engine.onDecision(GateDecision.Continue(KEY_GLOBAL))
        runCurrent()
        assertEquals(1, h.effects.frictionLaunches)

        // El límite se cumple al primer tick, muy dentro del cooldown.
        h.advanceWithTicks(2 * GateEngine.TICK_MILLIS)

        assertEquals(
            "el recordatorio se pierde: su gate cae dentro del cooldown global",
            1,
            h.effects.frictionLaunches
        )

        // Y desde aquí el objetivo ya no reacciona a nada mientras siga en primer plano.
        h.advanceWithTicks(60_000)
        h.engine.onContentChanged(PKG_GLOBAL)
        runCurrent()
        assertEquals("sigue atascado en LIMIT_REACHED", 1, h.effects.frictionLaunches)
    }

    /**
     * **B2 / PREGUNTA ABIERTA #7** — desactivar un objetivo mientras se está usando deja su sesión
     * abierta para siempre y su runtime congelado en `ALLOWED`. Al reactivarlo, **no vuelve a
     * gatear**: sigue en `ALLOWED` y el reingreso lo reanuda.
     */
    @Test
    fun current_disablingTargetInUse_leavesSessionOpenAndStateFrozen() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.windows.appWindows += PKG_GLOBAL
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        h.engine.onDecision(GateDecision.Continue(KEY_GLOBAL))
        runCurrent()
        val sessionId = h.store.openSession(KEY_GLOBAL)!!.id

        // El usuario desactiva el objetivo desde la configuración.
        h.engine.onTargetsChanged(emptyList())
        h.advanceWithTicks(60_000)

        assertNull(
            "la sesión queda abierta indefinidamente (B2)",
            h.store.session(sessionId)!!.endMillis
        )

        // Lo vuelve a activar y entra otra vez: no hay fricción, porque el runtime sigue ALLOWED.
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()

        assertEquals(
            "reactivar el objetivo no restaura la fricción hasta que muera el proceso",
            1,
            h.effects.frictionLaunches
        )
    }

    /**
     * **E8 / PREGUNTA ABIERTA #9** — si la pantalla de fricción desaparece sin decisión del usuario, el
     * objetivo se queda en `GATING`, estado en el que ni `handleAppGlobal` ni `evaluateSections` hacen
     * nada: la app queda **usable sin fricción y sin medición** hasta que abandone el primer plano.
     */
    @Test
    fun current_gatingWithoutDecision_appUsableAndUnmeasured() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.windows.appWindows += PKG_GLOBAL
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        val sessionId = h.store.openSession(KEY_GLOBAL)!!.id

        // La decisión nunca llega (la Activity murió por noHistory). La app se sigue usando.
        h.advanceWithTicks(5 * 60_000)
        h.engine.onContentChanged(PKG_GLOBAL)
        runCurrent()

        assertEquals("no se vuelve a gatear mientras siga en GATING", 1, h.effects.frictionLaunches)
        assertEquals(
            "y no se mide tiempo de uso: el ticker solo cuenta en ALLOWED",
            0L,
            h.store.session(sessionId)!!.activeMillis
        )

        // Solo salir del primer plano lo libera.
        h.windows.appWindows -= PKG_GLOBAL
        h.engine.onWindowStateChanged(PKG_OTHER_APP)
        h.advanceWithTicks(GateEngine.FOREGROUND_EXIT_CONFIRM_MILLIS + 2 * GateEngine.TICK_MILLIS)

        assertNotNull(
            "al abandonar el primer plano el gate se cierra",
            h.store.session(sessionId)!!.endMillis
        )
    }
}
