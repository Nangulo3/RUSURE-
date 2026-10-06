package com.rusure.app.engine.spec

import com.rusure.app.domain.engine.GateEngine
import com.rusure.app.domain.engine.SectionScan
import com.rusure.app.domain.gate.GateMode
import com.rusure.app.domain.model.GateAction
import com.rusure.app.engine.CLOCK_START
import com.rusure.app.engine.KEY_GLOBAL
import com.rusure.app.engine.KEY_SECTION
import com.rusure.app.engine.PKG_GLOBAL
import com.rusure.app.engine.PKG_SECTION
import com.rusure.app.engine.globalTarget
import com.rusure.app.engine.newHarness
import com.rusure.app.engine.sectionTarget
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **D-009** — Pausa global de la protección (5 minutos).
 *
 * Comportamiento esperado: *"El usuario puede pausar, con un solo toque y sin diálogo de
 * confirmación, toda la fricción configurada durante 5 minutos fijos. La pausa es global: un único
 * control afecta a todos los objetivos. Al terminar, cada objetivo vuelve exactamente a su
 * configuración anterior porque la pausa nunca modifica ningún `AppTargetConfig`."*
 *
 * De las cuatro decisiones confirmadas, aquí se cubren las que son del motor. La **#4**
 * (persistencia en `SharedPreferences`) es de `PauseController`, no del motor: este solo consulta
 * [com.rusure.app.domain.pause.PauseSource]. La duración fija de 5 min y el toque sin diálogo son de
 * la UI y de `PauseController`.
 */
class D009_GlobalPauseSpec {

    private val pauseWindow = 5 * 60_000L

    /**
     * D-009, decisión #3: *"Durante la pausa, tiempo y aperturas se siguen contando con normalidad en
     * estadísticas."* Entrar a un objetivo con la pausa activa no muestra fricción, pero abre la
     * sesión y acumula tiempo.
     */
    @Test
    fun D009_duringPause_noFriction_butOpeningAndTimeStillCount() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.windows.appWindows += PKG_GLOBAL
        h.pause.pausedUntilMillis = CLOCK_START + pauseWindow
        h.engine.onStarted()

        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()

        assertEquals("con la pausa activa no debe mostrarse fricción", 0, h.effects.frictionLaunches)
        val session = h.store.openSession(KEY_GLOBAL)
        assertNotNull("la apertura debe contar igualmente", session)
        assertEquals("y sin registrar interrupción, porque no la hubo", 0, session!!.interruptions)

        h.advanceWithTicks(10_000)
        assertTrue(
            "el tiempo de uso debe seguir acumulándose durante la pausa",
            h.store.session(session.id)!!.activeMillis > 0
        )
    }

    /**
     * D-009, decisión #1: *"Si la pausa termina estando dentro de una app objetivo, la fricción se
     * aplica al instante."* Y el supuesto aprobado de que ese gate INITIAL *"reutiliza la sesión
     * abierta durante la pausa, así que no cuenta como una apertura extra"*.
     */
    @Test
    fun D009_pauseEndsInsideTarget_frictionAppliesImmediately_reusingSession() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.windows.appWindows += PKG_GLOBAL
        h.pause.pausedUntilMillis = CLOCK_START + pauseWindow
        h.engine.onStarted()

        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        val sessionId = h.store.openSession(KEY_GLOBAL)!!.id
        assertEquals(0, h.effects.frictionLaunches)

        // Vence la pausa estando dentro de la app.
        h.advanceWithTicks(pauseWindow + 2 * GateEngine.TICK_MILLIS)
        runCurrent()

        assertEquals("al terminar la pausa debe aplicarse la fricción", 1, h.effects.frictionLaunches)
        assertEquals(GateMode.INITIAL, h.pendingRequest()!!.mode)
        assertEquals("sin contar una apertura extra", 1, h.store.sessions.size)
        assertEquals("la sesión es la misma", sessionId, h.store.openSession(KEY_GLOBAL)!!.id)
    }

    /**
     * D-009, decisión #2: *"La pausa se puede cancelar antes de tiempo con un botón 'Reanudar
     * ahora'."* Para el motor es el mismo flanco que el vencimiento natural.
     */
    @Test
    fun D009_pauseCancelledEarly_behavesLikeExpiry() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.windows.appWindows += PKG_GLOBAL
        h.pause.pausedUntilMillis = CLOCK_START + pauseWindow
        h.engine.onStarted()
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()

        // "Reanudar ahora" a los 30 s.
        h.advanceWithTicks(30_000)
        h.pause.pausedUntilMillis = 0L
        h.advanceWithTicks(2 * GateEngine.TICK_MILLIS)
        runCurrent()

        assertEquals("cancelar la pausa debe aplicar la fricción igual", 1, h.effects.frictionLaunches)
    }

    /**
     * D-009, supuesto aprobado: *"Si el teléfono está bloqueado/con la pantalla apagada al vencer la
     * pausa, la reactivación se aplica en cuanto el dispositivo vuelva a estar activo."*
     */
    @Test
    fun D009_pauseExpiresWhileDeviceInactive_reactivationDeferred() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.windows.appWindows += PKG_GLOBAL
        h.pause.pausedUntilMillis = CLOCK_START + pauseWindow
        h.engine.onStarted()
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()

        // La pausa vence con el teléfono bloqueado.
        h.device.active = false
        h.advanceWithTicks(pauseWindow + 2 * GateEngine.TICK_MILLIS)
        runCurrent()

        assertEquals(
            "con el dispositivo inactivo la reactivación debe quedar diferida",
            0,
            h.effects.frictionLaunches
        )

        // El usuario vuelve a usar el teléfono.
        h.device.active = true
        h.advanceWithTicks(2 * GateEngine.TICK_MILLIS)
        runCurrent()

        assertEquals(
            "y aplicarse en cuanto el dispositivo está activo",
            1,
            h.effects.frictionLaunches
        )
    }

    /**
     * D-009, supuesto aprobado: *"En secciones (Reels/Shorts) en navegación interna al terminar la
     * pausa: no se interrumpe al momento; la próxima vez que la sección sea visible, se gatea."*
     */
    @Test
    fun D009_pauseEndsDuringInternalNavigation_gatesWhenSectionVisibleAgain() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(sectionTarget(continuousUsageLimitSeconds = 60 * 60 * 24)))
        h.windows.appWindows += PKG_SECTION
        h.pause.pausedUntilMillis = CLOCK_START + pauseWindow
        h.engine.onStarted()

        // La apertura de la app llega como cambio de ventana: es lo que fija el primer plano. Sin
        // ella, onContentChanged no actúa (solo evalúa el paquete que ya está en primer plano).
        h.sections.result = SectionScan.Detected(sectionTarget())
        h.engine.onWindowStateChanged(PKG_SECTION)
        runCurrent()
        assertEquals(0, h.effects.frictionLaunches)
        assertNotNull("la apertura cuenta aunque la pausa esté activa", h.store.openSession(KEY_SECTION))

        // Navegación interna: la sección deja de verse.
        h.sections.result = SectionScan.NotDetected
        h.advanceWithTicks(GateEngine.CONTENT_EVAL_THROTTLE_MILLIS * 2)
        h.engine.onContentChanged(PKG_SECTION)

        // Vence la pausa mientras la sección no está visible.
        h.advanceWithTicks(pauseWindow + 2 * GateEngine.TICK_MILLIS)
        runCurrent()

        assertEquals(
            "no debe interrumpirse mientras la sección no está visible",
            0,
            h.effects.frictionLaunches
        )

        // La sección vuelve a verse: ahora sí.
        h.sections.result = SectionScan.Detected(sectionTarget())
        h.advanceWithTicks(GateEngine.CONTENT_EVAL_THROTTLE_MILLIS * 2)
        h.engine.onContentChanged(PKG_SECTION)
        runCurrent()

        assertEquals(
            "al volver a ser visible la sección, se gatea",
            1,
            h.effects.frictionLaunches
        )
    }

    /**
     * D-009: *"la fricción configurada (espera, recordatorio de uso continuo y modo Bloqueado)"* se
     * pausa por completo. Un objetivo en modo Bloqueado entra sin ser expulsado al inicio.
     */
    @Test
    fun D009_duringPause_blockedTargetIsNotSentHome() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget(entryAction = GateAction.BLOCK)))
        h.windows.appWindows += PKG_GLOBAL
        h.pause.pausedUntilMillis = CLOCK_START + pauseWindow
        h.engine.onStarted()

        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()

        assertEquals("la pausa también suspende el modo Bloqueado", 0, h.effects.homePresses)
        assertEquals(0, h.effects.frictionLaunches)
        assertNull(
            "y la sesión queda abierta, como un acceso normal",
            h.store.openSession(KEY_GLOBAL)!!.endMillis
        )
    }
}
