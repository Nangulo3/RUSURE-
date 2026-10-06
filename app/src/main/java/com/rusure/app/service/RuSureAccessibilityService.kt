package com.rusure.app.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.rusure.app.RuSureApp
import com.rusure.app.data.local.entity.AppTargetConfig
import com.rusure.app.data.repository.RuSureRepository
import com.rusure.app.domain.detection.TargetDetector
import com.rusure.app.domain.engine.AppTargetProbe
import com.rusure.app.domain.engine.Clock
import com.rusure.app.domain.engine.DeviceState
import com.rusure.app.domain.engine.EngineEffects
import com.rusure.app.domain.engine.GateEngine
import com.rusure.app.domain.engine.SectionProbe
import com.rusure.app.domain.engine.SectionScan
import com.rusure.app.domain.engine.SessionStore
import com.rusure.app.domain.engine.Tracer
import com.rusure.app.domain.engine.WindowProbe
import com.rusure.app.domain.gate.GateCoordinator
import com.rusure.app.domain.pause.PauseController
import com.rusure.app.ui.interruption.InterruptionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Adaptador de Android para el motor de fricción.
 *
 * Toda la política —cuándo interrumpir, qué cuenta como salir de la app, la temporización y el
 * estado por objetivo— vive en [GateEngine] (`domain/engine/`), que no depende de Android y por eso
 * se puede probar en la JVM (ver `docs/DECISIONS.md` D-010). Aquí solo queda lo que necesita el
 * sistema: recibir los eventos de accesibilidad, proveer el reloj, las ventanas, el estado del
 * dispositivo y la detección de nodos, y ejecutar los dos efectos (lanzar la pantalla de fricción y
 * volver al inicio).
 */
class RuSureAccessibilityService : AccessibilityService() {

    private val container by lazy { (application as RuSureApp).container }
    private val repository: RuSureRepository get() = container.repository
    private val detector: TargetDetector get() = container.detector
    private val gateCoordinator: GateCoordinator get() = container.gateCoordinator
    private val pauseController: PauseController get() = container.pauseController

    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val keyguardManager by lazy {
        getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // --- Costuras del motor (ver domain/engine/Seams.kt) ---

    /** Reloj real: tiempo de pared del sistema. */
    private val clock = Clock { System.currentTimeMillis() }

    /** Ventanas reales, a través del binding de accesibilidad. */
    private val windowProbe = WindowProbe { packageName -> hasApplicationWindow(packageName) }

    /** Estado real del dispositivo. */
    private val deviceState = DeviceState {
        powerManager.isInteractive && !keyguardManager.isKeyguardLocked
    }

    /** Detección de apps globales: por nombre de paquete, sin tocar el árbol de nodos. */
    private val appTargetProbe = AppTargetProbe { packageName, enabled ->
        detector.detectAppTarget(packageName, enabled)
    }

    /**
     * Detección de secciones sobre la ventana activa. Un `rootInActiveWindow` nulo es transitorio y
     * se reporta como [SectionScan.Unavailable]: el motor no concluye que la sección dejó de verse.
     */
    private val sectionProbe = SectionProbe { packageName, enabled ->
        val root = rootInActiveWindow
            ?: return@SectionProbe SectionScan.Unavailable
        detector.detectSection(root, packageName, enabled)
            ?.let { SectionScan.Detected(it) }
            ?: SectionScan.NotDetected
    }

    /** Operaciones de sesión, delegadas en la única fachada de persistencia. */
    private val sessionStore = object : SessionStore {
        override suspend fun openSessionId(catalogKey: String): Long? =
            repository.getOpenSession(catalogKey)?.id

        override suspend fun startSession(
            catalogKey: String,
            packageName: String,
            nowMillis: Long
        ): Long = repository.startSession(catalogKey, packageName, nowMillis)

        override suspend fun incrementInterruptions(sessionId: Long) =
            repository.incrementInterruptions(sessionId)

        override suspend fun incrementCancelled(sessionId: Long) =
            repository.incrementCancelled(sessionId)

        override suspend fun addActiveTime(sessionId: Long, deltaMillis: Long) =
            repository.addActiveTime(sessionId, deltaMillis)

        override suspend fun endSession(sessionId: Long, nowMillis: Long) =
            repository.endSession(sessionId, nowMillis)
    }

    /** Los dos efectos del motor sobre el sistema. */
    private val engineEffects = object : EngineEffects {
        override fun launchFriction() = launchInterruptionScreen()
        override fun goHome() {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    // --- Traza de diagnóstico ---

    /**
     * La traza solo existe en compilaciones depurables. Se decide con el flag del propio paquete en
     * lugar de `BuildConfig.DEBUG` para no tener que activar `buildFeatures.buildConfig`.
     */
    private val traceEnabled: Boolean by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /**
     * Emite la traza del motor en `RuSure/Engine` (ver `docs/DEVICE_CHECKLIST.md`). El mensaje se
     * construye de forma perezosa: en release no se evalúa.
     *
     * **Nunca** se registra texto ni descripciones de contenido de los nodos: la traza solo contiene
     * nombres de paquete, claves de catálogo, estados y marcas de tiempo.
     */
    private val tracer = Tracer { message ->
        if (traceEnabled) Log.d(TRACE_TAG, message())
    }

    private val engine by lazy {
        GateEngine(
            clock = clock,
            windowProbe = windowProbe,
            deviceState = deviceState,
            appTargetProbe = appTargetProbe,
            sectionProbe = sectionProbe,
            sessions = sessionStore,
            effects = engineEffects,
            gateCoordinator = gateCoordinator,
            pauseSource = pauseController,
            scope = serviceScope,
            tracer = tracer
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        engine.onStarted()

        serviceScope.launch {
            repository.observeEnabledTargets().collectLatest { targets: List<AppTargetConfig> ->
                engine.onTargetsChanged(targets)
            }
        }

        serviceScope.launch {
            gateCoordinator.decisions.collectLatest { decision ->
                engine.onDecision(decision)
            }
        }

        startUsageTicker()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // Ignorar eventos generados por la propia RuSure (pantalla de fricción incluida).
        if (pkg == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> engine.onWindowStateChanged(pkg)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> engine.onContentChanged(pkg)
        }
    }

    override fun onInterrupt() { /* No-op: no usamos feedback hablado. */ }

    override fun onUnbind(intent: Intent?): Boolean {
        tracer.trace { "servicio desvinculado | estados=[${engine.runtimesSnapshot()}]" }
        serviceScope.cancel()
        return super.onUnbind(intent)
    }

    /** Bucle del ticker de uso: el cuerpo de cada tick vive en [GateEngine.onTick]. */
    private fun startUsageTicker() {
        serviceScope.launch {
            while (isActive) {
                delay(GateEngine.TICK_MILLIS)
                engine.onTick()
            }
        }
    }

    /**
     * Indica si [packageName] tiene alguna ventana de tipo aplicación visible. Es la señal que
     * distingue "hay una ventana ajena superpuesta" (el teclado es `TYPE_INPUT_METHOD`, la persiana y
     * los diálogos del sistema son ventanas del sistema) de "el usuario cambió de aplicación".
     * Requiere `flagRetrieveInteractiveWindows`, ya activo en `accessibility_service_config.xml`.
     */
    private fun hasApplicationWindow(packageName: String): Boolean {
        val visibleWindows = try {
            windows
        } catch (_: Exception) {
            null
        }
        if (visibleWindows.isNullOrEmpty()) {
            tracer.trace { "ventanas no disponibles | pkg=$packageName (se confirmara la salida)" }
            return false
        }
        return visibleWindows.any { window ->
            window != null &&
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                window.root?.packageName?.toString() == packageName
        }
    }

    private fun launchInterruptionScreen() {
        val intent = Intent(this, InterruptionActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }
        startActivity(intent)
    }

    companion object {
        /**
         * Etiqueta de la traza de diagnóstico (`adb logcat -s RuSure/Engine`). Solo se emite en
         * compilaciones depurables y nunca contiene texto de los nodos.
         */
        private const val TRACE_TAG = "RuSure/Engine"
    }
}
