package com.rusure.app.engine.spec

import com.rusure.app.domain.engine.SectionScan
import com.rusure.app.domain.gate.GateDecision
import com.rusure.app.engine.KEY_SECTION
import com.rusure.app.engine.PKG_SECTION
import com.rusure.app.engine.newHarness
import com.rusure.app.engine.sectionTarget
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **D-007** — La navegación interna es inmune a la fricción mientras la app siga en primer plano.
 *
 * Comportamiento esperado: *"Salir de la sección vigilada sin salir de la app (comentarios, perfil,
 * feed, buscador, historias, pestañas internas) **nunca** vuelve a mostrar la pantalla intermedia,
 * dure lo que dure. La única interrupción posible en ese caso es el límite de uso continuo."*
 *
 * Y **D-001**, cuyo comportamiento esperado es que entrar en los comentarios de una publicación *"es
 * navegación interna y, por sí solo, no debe provocar la pantalla intermedia de fricción"*.
 */
class D007_InternalNavigationSpec {

    /**
     * D-001 + D-007: la sección deja de detectarse durante 40 minutos sin que la app abandone el
     * primer plano. Al volver a la sección no debe haber fricción: la suspensión por navegación
     * interna no expira nunca.
     *
     * El límite de uso continuo se configura muy por encima de los 40 min para que no interfiera:
     * es la *otra* interrupción que D-007 sí admite.
     */
    @Test
    fun D007_sectionNotDetectedSamePackage_40min_noGateOnReturn() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(sectionTarget(continuousUsageLimitSeconds = 60 * 60 * 24)))
        h.windows.appWindows += PKG_SECTION

        // Entra a la sección vigilada y acepta la fricción.
        h.sections.result = SectionScan.Detected(sectionTarget())
        h.engine.onWindowStateChanged(PKG_SECTION)
        runCurrent()
        h.engine.onDecision(GateDecision.Continue(KEY_SECTION))
        runCurrent()
        val sessionId = h.store.openSession(KEY_SECTION)!!.id
        assertEquals(1, h.effects.frictionLaunches)

        // Navega dentro de la app: la sección deja de verse, pero el paquete sigue en primer plano.
        h.sections.result = SectionScan.NotDetected
        h.engine.onContentChanged(PKG_SECTION)
        h.advanceWithTicks(40 * 60 * 1000L)

        assertEquals(
            "la navegación interna no debe cerrar la sesión por mucho que dure",
            1,
            h.store.sessions.size
        )
        assertNull(h.store.session(sessionId)!!.endMillis)

        // Vuelve a la sección: sin fricción.
        h.sections.result = SectionScan.Detected(sectionTarget())
        h.engine.onContentChanged(PKG_SECTION)
        runCurrent()

        assertEquals(
            "volver a la sección tras 40 min de navegación interna no debe gatear",
            1,
            h.effects.frictionLaunches
        )
        assertEquals(1, h.store.sessions.size)
    }

    /**
     * D-007: la inmunidad vale "mientras la app siga en primer plano". Si la app **deja** el primer
     * plano durante la navegación interna, la suspensión se endurece a la gracia corta y el regreso
     * posterior sí es una apertura nueva. Es el límite explícito de la decisión, no una excepción.
     */
    @Test
    fun D007_internalNavigationThenRealExit_becomesNewOpening() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(sectionTarget(continuousUsageLimitSeconds = 60 * 60 * 24)))
        h.windows.appWindows += PKG_SECTION
        h.sections.result = SectionScan.Detected(sectionTarget())
        h.engine.onWindowStateChanged(PKG_SECTION)
        runCurrent()
        h.engine.onDecision(GateDecision.Continue(KEY_SECTION))
        runCurrent()

        // Navegación interna...
        h.sections.result = SectionScan.NotDetected
        h.engine.onContentChanged(PKG_SECTION)
        h.advanceWithTicks(5_000)

        // ...y ahora sí sale de la app de verdad.
        h.windows.appWindows -= PKG_SECTION
        h.engine.onWindowStateChanged(com.rusure.app.engine.PKG_OTHER_APP)
        h.advanceWithTicks(30_000)

        // Al volver, la sección se detecta de nuevo: apertura nueva.
        h.windows.appWindows += PKG_SECTION
        h.sections.result = SectionScan.Detected(sectionTarget())
        h.engine.onWindowStateChanged(PKG_SECTION)
        runCurrent()

        assertEquals(
            "tras abandonar el primer plano, el regreso sí es apertura nueva",
            2,
            h.effects.frictionLaunches
        )
    }
}
