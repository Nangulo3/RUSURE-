package com.rusure.app.engine.current

import com.rusure.app.domain.engine.GateEngine
import com.rusure.app.domain.gate.GateDecision
import com.rusure.app.engine.KEY_GLOBAL
import com.rusure.app.engine.PKG_GLOBAL
import com.rusure.app.engine.PKG_IME
import com.rusure.app.engine.PKG_OTHER_APP
import com.rusure.app.engine.globalTarget
import com.rusure.app.engine.newHarness
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * # COMPORTAMIENTO ACTUAL, NO ESPECIFICACIÓN
 *
 * Fija cómo infiere **hoy** el motor que el usuario salió de la app objetivo, en los puntos donde la
 * decisión del usuario **no está confirmada**. Si uno de estos tests falla, el comportamiento ha
 * cambiado sin decisión: hay que preguntar antes de actualizarlo (D-003 y D-010).
 *
 * Referencias: `docs/DEEP_INIT_REPORT.md` §33 preguntas **#1b** (valor del retardo de confirmación) y
 * **#2b** (valor del umbral de apertura nueva), §29 **S8** (el bloqueo de pantalla nunca re-gatea) y
 * el fallback documentado solo en el código de `confirmPendingExits` cuando la lista de ventanas no
 * está disponible.
 */
class ExitInferenceCurrent {

    /**
     * **PREGUNTA ABIERTA #1b** — el retardo con el que se confirma una salida es hoy 3 s, un valor
     * *provisional elegido en la implementación* (D-005 lo deja explícitamente sin confirmar).
     *
     * Este test lo clava para que cambiarlo no pase inadvertido, y comprueba la consecuencia
     * observable: a los 2 s la salida aún no se ha resuelto; a los 3 s sí.
     */
    @Test
    fun current_exitConfirmationDelay_is3s() = runTest {
        assertEquals(
            "si este valor cambia, es la pregunta #1b la que hay que cerrar primero",
            3_000L,
            GateEngine.FOREGROUND_EXIT_CONFIRM_MILLIS
        )

        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.windows.appWindows += PKG_GLOBAL
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        h.engine.onDecision(GateDecision.Continue(KEY_GLOBAL))
        runCurrent()
        val sessionId = h.store.openSession(KEY_GLOBAL)!!.id

        // Sale de verdad: la app pierde su ventana de aplicación.
        h.windows.appWindows -= PKG_GLOBAL
        h.engine.onWindowStateChanged(PKG_OTHER_APP)

        // A los 2 s la salida sigue en observación: nada se ha suspendido todavía.
        h.advanceWithTicks(2_000)
        assertNull("a los 2 s la salida no debe estar resuelta", h.store.session(sessionId)!!.endMillis)

        // A los 3 s se confirma, y la gracia empieza a contar DESDE AQUÍ.
        h.advanceWithTicks(GateEngine.BACKGROUND_GRACE_MILLIS + 2 * GateEngine.TICK_MILLIS)
        assertNotNull("pasada la confirmación y la gracia, la sesión se cierra", h.store.session(sessionId)!!.endMillis)
    }

    /**
     * **PREGUNTA ABIERTA #2b** — el umbral que convierte un regreso en apertura nueva es hoy la
     * constante de 7 s. D-006 confirmó que debe ser un **ajuste del usuario**, pero su valor por
     * defecto y su rango siguen sin decidir.
     */
    @Test
    fun current_newOpeningThreshold_isHardcoded7s() {
        assertEquals(
            "D-006 exige que esto deje de ser una constante; el valor sigue abierto (#2b)",
            7_000L,
            GateEngine.BACKGROUND_GRACE_MILLIS
        )
    }

    /**
     * Comportamiento actual, **documentado solo en el código** (`confirmPendingExits`): si la lista de
     * ventanas no está disponible, se opta por lo conservador y **se confirma la salida**, que es lo
     * que hacía el motor antes de la confirmación diferida.
     *
     * No forma parte del texto confirmado de D-005, así que se fija aquí y no en `spec/`.
     */
    @Test
    fun current_windowListUnavailable_confirmsExit() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.windows.appWindows += PKG_GLOBAL
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        h.engine.onDecision(GateDecision.Continue(KEY_GLOBAL))
        runCurrent()
        val sessionId = h.store.openSession(KEY_GLOBAL)!!.id

        // Aparece el teclado (la app NO se ha ido) pero el sistema no da la lista de ventanas.
        h.windows.windowsUnavailable = true
        h.engine.onWindowStateChanged(PKG_IME)
        h.advanceWithTicks(
            GateEngine.FOREGROUND_EXIT_CONFIRM_MILLIS +
                GateEngine.BACKGROUND_GRACE_MILLIS +
                2 * GateEngine.TICK_MILLIS
        )

        assertNotNull(
            "sin lista de ventanas se confirma la salida, aunque fuera una ventana auxiliar",
            h.store.session(sessionId)!!.endMillis
        )
    }

    /**
     * **S8** — bloquear el teléfono dentro de un objetivo no cuenta nunca como salida, por largo que
     * sea el bloqueo: al desbloquear se reanuda sin fricción.
     *
     * **D-011 decide lo contrario** (si el bloqueo supera el umbral de D-006, el regreso es apertura
     * nueva), pero **no está implementada**. Este test fija lo de hoy; al implementar D-011 debe
     * moverse a `spec/` reescrito.
     */
    @Test
    fun current_screenLockOfAnyLength_neverReGates() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.windows.appWindows += PKG_GLOBAL
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        h.engine.onDecision(GateDecision.Continue(KEY_GLOBAL))
        runCurrent()
        val sessionId = h.store.openSession(KEY_GLOBAL)!!.id

        // Pantalla bloqueada media hora. Los eventos que llegan mientras tanto no reclasifican nada.
        h.device.active = false
        h.engine.onWindowStateChanged(PKG_IME)
        h.advanceWithTicks(30 * 60 * 1000L)
        h.device.active = true

        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()

        assertEquals(
            "hoy, desbloquear nunca vuelve a mostrar fricción (S8); D-011 lo cambiará",
            1,
            h.effects.frictionLaunches
        )
        assertNull("y la sesión sigue siendo la misma", h.store.session(sessionId)!!.endMillis)
    }

    /**
     * Comportamiento actual señalado en el plan de la fase F3 como "**NO lo corrijas**":
     * `freshForeground` se calcula **antes** de `onForegroundPackageChanged`, así que cancelar una
     * salida pendiente no evita que el evento se trate como entrada fresca.
     *
     * Consecuencia observable: con el runtime en `IDLE` y una salida en observación, basta **cerrar el
     * teclado** para que el regreso se gatee como una apertura nueva, sin que el usuario haya salido
     * de la app en ningún momento.
     */
    @Test
    fun current_returnFromPendingExit_withIdleRuntime_gatesAsFreshForeground() = runTest {
        val h = newHarness()
        h.engine.onTargetsChanged(listOf(globalTarget()))
        h.windows.appWindows += PKG_GLOBAL

        // Abre, y el usuario decide no continuar: el objetivo vuelve a IDLE sin salir de la app.
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()
        h.engine.onDecision(GateDecision.Leave(KEY_GLOBAL))
        runCurrent()
        assertEquals(1, h.effects.frictionLaunches)

        // Pasa el cooldown global para que no sea él quien suprima el siguiente gate.
        h.advanceWithTicks(GateEngine.GATE_COOLDOWN_MILLIS + GateEngine.TICK_MILLIS)

        // Aparece el teclado: se anota una salida pendiente del paquete objetivo.
        h.engine.onWindowStateChanged(PKG_IME)
        h.advanceWithTicks(GateEngine.TICK_MILLIS)

        // Y se cierra antes de la confirmación. La salida se cancela... pero el evento ya venía
        // marcado como entrada fresca.
        h.engine.onWindowStateChanged(PKG_GLOBAL)
        runCurrent()

        assertEquals(
            "cerrar el teclado con el runtime en IDLE gatea como apertura nueva",
            2,
            h.effects.frictionLaunches
        )
    }
}
