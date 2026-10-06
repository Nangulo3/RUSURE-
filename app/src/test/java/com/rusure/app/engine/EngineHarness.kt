package com.rusure.app.engine

import com.rusure.app.data.local.entity.AppTargetConfig
import com.rusure.app.domain.engine.AppTargetProbe
import com.rusure.app.domain.engine.Clock
import com.rusure.app.domain.engine.DeviceState
import com.rusure.app.domain.engine.EngineEffects
import com.rusure.app.domain.engine.GateEngine
import com.rusure.app.domain.engine.SectionScan
import com.rusure.app.domain.engine.SectionProbe
import com.rusure.app.domain.engine.SessionStore
import com.rusure.app.domain.gate.GateCoordinator
import com.rusure.app.domain.model.GateAction
import com.rusure.app.domain.model.TargetType
import com.rusure.app.domain.pause.PauseSource
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent

/**
 * Dobles y andamiaje para probar [GateEngine] en la JVM (ver `docs/DECISIONS.md` D-010).
 *
 * Reglas del banco de pruebas:
 *
 * 1. **Una sola fuente de tiempo.** [FakeClock] lee `testScheduler.currentTime`, así que el reloj
 *    del motor y el del `TestScope` son el mismo: todo avanza con `advanceTimeBy` o `runCurrent` y
 *    nada depende del reloj de pared.
 * 2. **`StandardTestDispatcher`, nunca `Unconfined`.** Las corrutinas que el motor lanza para crear
 *    la sesión siguen siendo asíncronas, como en producción. Eso preserva la condición de carrera
 *    del informe §17.3 (la raíz de B15) en lugar de esconderla.
 * 3. **Paquetes ficticios.** Nunca se nombra TikTok, Instagram ni YouTube: las reglas no dependen de
 *    la app (regla 4 de `CLAUDE.md`).
 * 4. **El reloj no arranca en 0.** El motor usa `suspendedAtMillis == 0L` como centinela de "no
 *    suspendido", así que una suspensión registrada en el instante 0 sería indistinguible de no
 *    haber suspensión. [newHarness] adelanta el tiempo virtual hasta [CLOCK_START] antes de empezar.
 */

/** Paquete de la app vigilada completa (objetivo APP_GLOBAL). */
const val PKG_GLOBAL = "com.example.globalapp"

/** Paquete de la app con una sección vigilada (objetivo SECTION). */
const val PKG_SECTION = "com.example.sectionapp"

/** Ventana auxiliar: teclado. No es una app distinta aunque emita eventos con su paquete. */
const val PKG_IME = "com.example.ime"

/** Ventana auxiliar: interfaz del sistema (persiana, notificaciones). */
const val PKG_SYSTEM_UI = "com.example.systemui"

/** Otra aplicación real: salir aquí sí es salir. */
const val PKG_OTHER_APP = "com.example.otherapp"

const val KEY_GLOBAL = "global_target"
const val KEY_SECTION = "section_target"

/**
 * Instante virtual en que arranca cada test. Cualquier valor claramente mayor que las ventanas del
 * motor sirve; lo importante es que no sea 0 (ver regla 4).
 */
const val CLOCK_START = 1_000_000L

fun globalTarget(
    initialTimerSeconds: Int = 5,
    continuousUsageLimitSeconds: Int = 300,
    reEntryTimerSeconds: Int = 5,
    entryAction: GateAction = GateAction.WAIT
) = AppTargetConfig(
    id = 1L,
    catalogKey = KEY_GLOBAL,
    packageName = PKG_GLOBAL,
    targetType = TargetType.APP_GLOBAL,
    displayName = "App vigilada",
    enabled = true,
    initialTimerSeconds = initialTimerSeconds,
    continuousUsageLimitSeconds = continuousUsageLimitSeconds,
    reEntryTimerSeconds = reEntryTimerSeconds,
    entryAction = entryAction
)

fun sectionTarget(
    initialTimerSeconds: Int = 5,
    continuousUsageLimitSeconds: Int = 300,
    reEntryTimerSeconds: Int = 5,
    entryAction: GateAction = GateAction.WAIT
) = AppTargetConfig(
    id = 2L,
    catalogKey = KEY_SECTION,
    packageName = PKG_SECTION,
    targetType = TargetType.SECTION,
    sectionKey = "section",
    displayName = "Seccion vigilada",
    enabled = true,
    initialTimerSeconds = initialTimerSeconds,
    continuousUsageLimitSeconds = continuousUsageLimitSeconds,
    reEntryTimerSeconds = reEntryTimerSeconds,
    entryAction = entryAction
)

/** Reloj del motor atado al planificador virtual del test: una sola fuente de tiempo. */
class FakeClock(private val scope: TestScope) : Clock {
    override fun now(): Long = scope.testScheduler.currentTime
}

/**
 * Ventanas del sistema. [appWindows] contiene los paquetes que conservan una ventana de
 * aplicación visible; con [windowsUnavailable] se simula que la lista no está disponible, caso en
 * que el motor opta por confirmar la salida.
 */
class FakeWindowProbe : com.rusure.app.domain.engine.WindowProbe {
    val appWindows = mutableSetOf<String>()
    var windowsUnavailable = false

    override fun hasApplicationWindow(packageName: String): Boolean =
        if (windowsUnavailable) false else packageName in appWindows
}

/** Pantalla encendida y sin keyguard. */
class FakeDeviceState(var active: Boolean = true) : DeviceState {
    override fun isActive(): Boolean = active
}

/** Pausa global controlable con el mismo reloj virtual. */
class FakePauseSource(var pausedUntilMillis: Long = 0L) : PauseSource {
    override fun isPaused(now: Long): Boolean = now < pausedUntilMillis
}

/** Detección de apps globales, con la misma regla que el detector real. */
class FakeAppTargetProbe : AppTargetProbe {
    override fun detect(
        packageName: String,
        enabledTargets: List<AppTargetConfig>
    ): AppTargetConfig? = enabledTargets.firstOrNull {
        it.targetType == TargetType.APP_GLOBAL && it.packageName == packageName
    }
}

/** Detección de secciones: el test decide qué ve el motor en la ventana activa. */
class FakeSectionProbe(var result: SectionScan = SectionScan.NotDetected) : SectionProbe {
    override fun scan(packageName: String, enabledTargets: List<AppTargetConfig>): SectionScan =
        result
}

/** Sesión de uso registrada por [FakeSessionStore]. */
data class FakeSession(
    val id: Long,
    val catalogKey: String,
    val packageName: String,
    val startMillis: Long,
    var endMillis: Long? = null,
    var activeMillis: Long = 0L,
    var interruptions: Int = 0,
    var cancelled: Int = 0
)

/** Persistencia de sesiones en memoria, con la misma semántica de "sesión abierta". */
class FakeSessionStore : SessionStore {
    val sessions = mutableListOf<FakeSession>()
    private var nextId = 100L

    /** Sesión abierta (sin cierre) del objetivo, que es lo que el motor reutiliza. */
    fun openSession(catalogKey: String): FakeSession? =
        sessions.lastOrNull { it.catalogKey == catalogKey && it.endMillis == null }

    fun session(id: Long): FakeSession? = sessions.firstOrNull { it.id == id }

    override suspend fun openSessionId(catalogKey: String): Long? = openSession(catalogKey)?.id

    override suspend fun startSession(
        catalogKey: String,
        packageName: String,
        nowMillis: Long
    ): Long {
        val session = FakeSession(nextId++, catalogKey, packageName, nowMillis)
        sessions += session
        return session.id
    }

    override suspend fun incrementInterruptions(sessionId: Long) {
        session(sessionId)?.let { it.interruptions++ }
    }

    override suspend fun incrementCancelled(sessionId: Long) {
        session(sessionId)?.let { it.cancelled++ }
    }

    override suspend fun addActiveTime(sessionId: Long, deltaMillis: Long) {
        session(sessionId)?.let { it.activeMillis += deltaMillis }
    }

    override suspend fun endSession(sessionId: Long, nowMillis: Long) {
        session(sessionId)?.let { it.endMillis = nowMillis }
    }
}

/** Efectos del motor sobre el sistema, contados. */
class FakeEffects : EngineEffects {
    var frictionLaunches = 0
        private set
    var homePresses = 0
        private set

    override fun launchFriction() {
        frictionLaunches++
    }

    override fun goHome() {
        homePresses++
    }
}

/**
 * El motor con todos sus dobles, listo para dirigir desde un test.
 *
 * Usar siempre con `runTest`: el [TestScope] que se le pasa es el que ejecuta las corrutinas del
 * motor y, a la vez, la fuente de tiempo del [FakeClock].
 */
class EngineHarness(val scope: TestScope) {
    val clock = FakeClock(scope)
    val windows = FakeWindowProbe()
    val device = FakeDeviceState()
    val pause = FakePauseSource()
    val appTargets = FakeAppTargetProbe()
    val sections = FakeSectionProbe()
    val store = FakeSessionStore()
    val effects = FakeEffects()
    val coordinator = GateCoordinator()

    val engine = GateEngine(
        clock = clock,
        windowProbe = windows,
        deviceState = device,
        appTargetProbe = appTargets,
        sectionProbe = sections,
        sessions = store,
        effects = effects,
        gateCoordinator = coordinator,
        pauseSource = pause,
        scope = scope,
        tracer = com.rusure.app.domain.engine.Tracer.None
    )

    /** La solicitud de fricción pendiente, si el motor publicó alguna. */
    fun pendingRequest() = coordinator.peekRequest()

    /**
     * Avanza el tiempo virtual [millis] entregando un [GateEngine.onTick] por cada periodo del
     * ticker, igual que hace el bucle del servicio.
     */
    fun advanceWithTicks(millis: Long) {
        var remaining = millis
        while (remaining >= GateEngine.TICK_MILLIS) {
            scope.advanceTimeBy(GateEngine.TICK_MILLIS)
            engine.onTick()
            // El tick puede lanzar corrutinas (cerrar la sesión, volcar tiempo): hay que dejarlas
            // correr, igual que en producción corren en Dispatchers.Default justo después. Sin esto,
            // el último tick de un avance deja su escritura sin ejecutar.
            scope.runCurrent()
            remaining -= GateEngine.TICK_MILLIS
        }
        if (remaining > 0) {
            scope.advanceTimeBy(remaining)
            scope.runCurrent()
        }
    }
}

/** Crea el motor con sus dobles y coloca el reloj virtual en [CLOCK_START] (ver regla 4). */
fun TestScope.newHarness(): EngineHarness {
    advanceTimeBy(CLOCK_START)
    return EngineHarness(this)
}
