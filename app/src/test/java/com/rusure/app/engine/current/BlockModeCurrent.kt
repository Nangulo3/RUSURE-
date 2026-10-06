package com.rusure.app.engine.current

import com.rusure.app.domain.engine.SectionScan
import com.rusure.app.domain.model.GateAction
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * # COMPORTAMIENTO ACTUAL, NO ESPECIFICACIÓN
 *
 * Fija cómo se comporta **hoy** el modo Bloqueado, en los dos puntos que siguen siendo preguntas
 * abiertas: `docs/DEEP_INIT_REPORT.md` §33 **#5** (si bloquear una sección debe expulsar de la app
 * entera) y **#6** (si un intento bloqueado cuenta como apertura en estadísticas). Ambos quedaron
 * confirmados *como hechos* en dispositivo (`F1_summary.md`, paso 7), no como decisiones.
 */
class BlockModeCurrent {

    /**
     * **PREGUNTA ABIERTA #5** — el único recurso del motor para imponer un bloqueo es volver al
     * inicio, así que bloquear una **sección** saca al usuario de **toda** la app, no solo de la
     * sección. Verificado en dispositivo: bloquear la sección vigilada cerró la app entera.
     */
    @Test
    fun current_blockOnSection_sendsHomeFromWholeApp() = runTest {
        val h = newHarness()
        val target = sectionTarget(entryAction = GateAction.BLOCK)
        h.engine.onTargetsChanged(listOf(target))
        h.windows.appWindows += PKG_SECTION
        h.sections.result = SectionScan.Detected(target)

        h.engine.onWindowStateChanged(PKG_SECTION)
        runCurrent()

        assertEquals("bloquear una sección vuelve al inicio", 1, h.effects.homePresses)
        assertEquals("y no muestra pantalla de espera", 0, h.effects.frictionLaunches)
    }

    /**
     * **PREGUNTA ABIERTA #6** — un intento bloqueado crea una sesión, la marca con una interrupción y
     * la cierra en el mismo instante: en estadísticas aparece como una **apertura de 0 minutos**.
     */
    @Test
    fun current_blockedAttempt_countsAsOpeningWithZeroTime() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget(entryAction = GateAction.BLOCK)))
        h.windows.appWindows += PKG_GLOBAL

        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()

        assertEquals("se registra una sesión", 1, h.store.sessions.size)
        val session = h.store.sessions.first()
        assertEquals("con una interrupción", 1, session.interruptions)
        assertNotNull("cerrada en el acto", session.endMillis)
        assertEquals("y sin tiempo de uso", 0L, session.activeMillis)
        assertEquals("sin contarla como acceso cancelado", 0, session.cancelled)
    }

    /**
     * Comportamiento actual: mientras el objetivo bloqueado siga en primer plano, cada evento vuelve a
     * pulsar HOME, con un limitador de frecuencia de 400 ms que evita saturar de acciones globales.
     *
     * En dispositivo bastó **una sola** pulsación (`F1_summary.md` paso 7), así que la reimposición es
     * una red de seguridad contra la animación de arranque, no el camino normal.
     */
    @Test
    fun current_blockedTargetStillForeground_repressesHomeAtMost400ms() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget(entryAction = GateAction.BLOCK)))
        h.windows.appWindows += PKG_GLOBAL
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        assertEquals(1, h.effects.homePresses)

        // Dos eventos seguidos dentro de la ventana de 400 ms: no deben añadir pulsaciones.
        h.engine.onContentChanged(PKG_GLOBAL)
        h.engine.onContentChanged(PKG_GLOBAL)
        assertEquals("el limitador descarta las reimposiciones seguidas", 1, h.effects.homePresses)

        // Pasada la ventana, el siguiente evento sí vuelve a pulsar.
        h.advanceWithTicks(1_000)
        h.engine.onContentChanged(PKG_GLOBAL)
        assertEquals("pasados 400 ms vuelve a imponerse", 2, h.effects.homePresses)
    }

    /**
     * Comportamiento actual: salir de la sección bloqueada (sin salir de la app) **cierra** el
     * objetivo, de modo que volver a entrar vuelve a bloquear y a sumar otra "apertura".
     *
     * El número exacto de pulsaciones de HOME **no es estable**, y eso es parte de lo que se fija
     * aquí: al cerrarse el objetivo vuelve a `IDLE`, lo que hace que el siguiente evento programe un
     * *settle scan* (§10.2 evento E); ese re-escaneo puede ser el que detecte la sección y aplique el
     * bloqueo, y sus re-evaluaciones posteriores encuentran el objetivo en `BLOCKED` y disparan
     * [com.rusure.app.domain.engine.GateEngine] → `reEnforceBlock`, que vuelve a pulsar HOME cada
     * 400 ms. Por eso se comprueba "al menos una pulsación más", no un número.
     */
    @Test
    fun current_leavingBlockedSection_closesTarget_soReentryBlocksAgain() = runTest {
        val h = newHarness()
        val target = sectionTarget(entryAction = GateAction.BLOCK)
        h.engine.onTargetsChanged(listOf(target))
        h.windows.appWindows += PKG_SECTION
        h.sections.result = SectionScan.Detected(target)
        h.engine.onWindowStateChanged(PKG_SECTION)
        runCurrent()

        // Sale de la sección: el objetivo bloqueado se cierra.
        h.sections.result = SectionScan.NotDetected
        h.advanceWithTicks(1_000)
        h.engine.onContentChanged(PKG_SECTION)
        runCurrent()

        // Vuelve a la sección: se bloquea otra vez y cuenta otra apertura.
        h.sections.result = SectionScan.Detected(target)
        h.advanceWithTicks(1_000)
        h.engine.onContentChanged(PKG_SECTION)
        runCurrent()

        assertEquals("cada reentrada al bloqueo suma una apertura", 2, h.store.sessions.size)
        assertEquals(KEY_SECTION, h.store.sessions.last().catalogKey)
        assertTrue(
            "y vuelve a expulsar al inicio (al menos una pulsación más que la primera)",
            h.effects.homePresses >= 2
        )
    }

    /** Guarda de coherencia: el objetivo global del catálogo ficticio es el que se usa arriba. */
    @Test
    fun current_blockedGlobalTarget_usesItsOwnCatalogKey() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget(entryAction = GateAction.BLOCK)))
        h.windows.appWindows += PKG_GLOBAL
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        assertEquals(KEY_GLOBAL, h.store.sessions.first().catalogKey)
    }
}
