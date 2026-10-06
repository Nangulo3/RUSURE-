package com.rusure.app.engine.spec

import com.rusure.app.domain.engine.GateEngine
import com.rusure.app.domain.engine.SectionScan
import com.rusure.app.domain.gate.GateDecision
import com.rusure.app.domain.gate.GateMode
import com.rusure.app.engine.KEY_SECTION
import com.rusure.app.engine.PKG_IME
import com.rusure.app.engine.PKG_SECTION
import com.rusure.app.engine.newHarness
import com.rusure.app.engine.sectionTarget
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Ignore
import org.junit.Test

/**
 * **D-008** — El recordatorio de uso continuo mide el tiempo en la app, no solo en la sección.
 *
 * Comportamiento esperado: *"El 'recordatorio cada N minutos' cuenta todo el tiempo que la app
 * objetivo está en primer plano, incluida la navegación interna (comentarios, perfiles). Puede por
 * tanto dispararse mientras el usuario no está viendo la sección."* Y, deliberadamente, *"el tiempo
 * mostrado al usuario sigue contando solo con la sección visible: son dos relojes con reglas
 * distintas a propósito."*
 */
class D008_ContinuousLimitSpec {

    private val limitSeconds = 10

    /**
     * D-008: con la sección fuera de vista pero la app en primer plano, el reloj del **límite** sigue
     * avanzando y dispara el recordatorio (`RE_ENTRY`), mientras que el **tiempo activo** de
     * estadísticas no acumula nada. Es la asimetría que la decisión declara intencionada.
     */
    @Test
    fun D008_limitAdvancesDuringInternalNavigation_activeTimeDoesNot() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(sectionTarget(continuousUsageLimitSeconds = limitSeconds)))
        h.windows.appWindows += PKG_SECTION

        h.sections.result = SectionScan.Detected(sectionTarget())
        h.engine.onWindowStateChanged(PKG_SECTION)
        runCurrent()
        h.engine.onDecision(GateDecision.Continue(KEY_SECTION))
        runCurrent()
        val sessionId = h.store.openSession(KEY_SECTION)!!.id

        // Navegación interna inmediata: desde aquí no debe contarse tiempo activo.
        h.sections.result = SectionScan.NotDetected
        h.engine.onContentChanged(PKG_SECTION)

        // El límite se cumple durante la navegación interna.
        h.advanceWithTicks(limitSeconds * 1000L + GateEngine.TICK_MILLIS)

        assertEquals(
            "el recordatorio debe saltar aunque la sección no esté visible",
            2,
            h.effects.frictionLaunches
        )
        assertEquals(
            "y debe ser un gate de reingreso, no una apertura nueva",
            GateMode.RE_ENTRY,
            h.pendingRequest()!!.mode
        )
        assertEquals("sin abrir otra sesión", 1, h.store.sessions.size)
        assertEquals(
            "el tiempo activo no cuenta la navegación interna",
            0L,
            h.store.session(sessionId)!!.activeMillis
        )
    }

    /**
     * D-008: el límite debe contar **todo** el tiempo que la app está en primer plano. Una ventana
     * auxiliar (el teclado) no saca al usuario de la app, así que esos segundos deberían contar
     * igual y el recordatorio debería saltar en el tiempo configurado.
     *
     * **FALLA HOY** por el hallazgo **H5** de `docs/baseline/F1_summary.md`: mientras la salida está
     * en observación, `currentForegroundPackage` es el paquete de la ventana auxiliar, así que `inUse`
     * es falso y `continuousMillis` se congela durante los
     * `FOREGROUND_EXIT_CONFIRM_MILLIS` que tarda la confirmación en descartarla. El recordatorio se
     * retrasa esos segundos por cada aparición de teclado.
     *
     * Queda `@Ignore` para que un fallo en `spec/` siga significando regresión (D-010). Se activa al
     * corregir H5, decisión que todavía no existe.
     */
    @Test
    @Ignore("Falla por H5: la ventana auxiliar congela el reloj del límite durante la confirmación")
    fun D008_limitKeepsCountingDuringAuxWindow() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(sectionTarget(continuousUsageLimitSeconds = limitSeconds)))
        h.windows.appWindows += PKG_SECTION

        h.sections.result = SectionScan.Detected(sectionTarget())
        h.engine.onWindowStateChanged(PKG_SECTION)
        runCurrent()
        h.engine.onDecision(GateDecision.Continue(KEY_SECTION))
        runCurrent()

        // A mitad del límite aparece el teclado y se descarta como falsa salida.
        h.advanceWithTicks(limitSeconds * 1000L / 2)
        h.engine.onWindowStateChanged(PKG_IME)
        h.advanceWithTicks(GateEngine.FOREGROUND_EXIT_CONFIRM_MILLIS + GateEngine.TICK_MILLIS)

        // Lo que falta del límite, ya con la app de vuelta en primer plano.
        h.advanceWithTicks(limitSeconds * 1000L / 2 + GateEngine.TICK_MILLIS)

        assertEquals(
            "el recordatorio debe saltar al cumplirse el límite, contando el tiempo con el teclado",
            2,
            h.effects.frictionLaunches
        )
    }
}
