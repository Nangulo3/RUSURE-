package com.rusure.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.rusure.app.data.local.entity.AppTargetConfig
import com.rusure.app.data.repository.RuSureRepository
import com.rusure.app.domain.detection.TargetDetector
import com.rusure.app.domain.gate.GateCoordinator
import com.rusure.app.domain.gate.GateDecision
import com.rusure.app.domain.gate.GateMode
import com.rusure.app.domain.gate.GateRequest
import com.rusure.app.domain.gate.GateState
import com.rusure.app.RuSureApp
import com.rusure.app.domain.model.TargetType
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
 * Intercepta la apertura de apps y secciones objetivo, dispara la pantalla de fricción y mide el
 * tiempo de uso continuo para forzar gates de reingreso.
 */
class RuSureAccessibilityService : AccessibilityService() {

    private val container by lazy { (application as RuSureApp).container }
    private val repository: RuSureRepository get() = container.repository
    private val detector: TargetDetector get() = container.detector
    private val gateCoordinator: GateCoordinator get() = container.gateCoordinator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Estado en memoria por objetivo (catalogKey). Acceso siempre bajo [lock]. */
    private val runtimes = HashMap<String, TargetRuntime>()
    private val lock = Any()

    @Volatile
    private var enabledTargets: List<AppTargetConfig> = emptyList()

    @Volatile
    private var currentForegroundPackage: String? = null

    /** Objetivo actualmente en seguimiento de tiempo (ALLOWED y en primer plano). */
    @Volatile
    private var activeCatalogKey: String? = null

    private var lastContentEvalMillis = 0L

    private data class TargetRuntime(
        var state: GateState = GateState.IDLE,
        var sessionId: Long? = null,
        var continuousMillis: Long = 0L,
        var unflushedActiveMillis: Long = 0L
    )

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceScope.launch {
            repository.observeEnabledTargets().collectLatest { targets ->
                enabledTargets = targets
            }
        }

        serviceScope.launch {
            gateCoordinator.decisions.collectLatest { decision ->
                handleDecision(decision)
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
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (pkg != currentForegroundPackage) {
                    onForegroundPackageChanged(pkg)
                }
                handleAppGlobal(pkg)
                evaluateSections(pkg)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastContentEvalMillis < CONTENT_EVAL_THROTTLE_MILLIS) return
                lastContentEvalMillis = now
                if (pkg == currentForegroundPackage) {
                    evaluateSections(pkg)
                }
            }
        }
    }

    override fun onInterrupt() { /* No-op: no usamos feedback hablado. */ }

    override fun onUnbind(intent: Intent?): Boolean {
        serviceScope.cancel()
        return super.onUnbind(intent)
    }

    // --- Cambio de app en primer plano ---

    private fun onForegroundPackageChanged(newPackage: String) {
        val previous = currentForegroundPackage
        currentForegroundPackage = newPackage

        if (previous != null) {
            // Cerrar el seguimiento de cualquier objetivo del paquete anterior.
            val toClose = synchronized(lock) {
                enabledTargets.filter { it.packageName == previous }.map { it.catalogKey }
            }
            toClose.forEach { closeTarget(it) }
        }
    }

    // --- Apps globales ---

    private fun handleAppGlobal(pkg: String) {
        val config = detector.detectAppTarget(pkg, enabledTargets) ?: return
        val runtime = runtimeFor(config.catalogKey)
        val shouldGate = synchronized(lock) { runtime.state == GateState.IDLE }
        if (shouldGate) {
            triggerGate(config, GateMode.INITIAL)
        } else if (synchronized(lock) { runtime.state == GateState.ALLOWED }) {
            activeCatalogKey = config.catalogKey
        }
    }

    // --- Secciones ---

    private fun evaluateSections(pkg: String) {
        val sections = enabledTargets.filter {
            it.targetType == TargetType.SECTION && it.packageName == pkg
        }
        if (sections.isEmpty()) return

        val root = rootInActiveWindow ?: return // null transitorio: no concluir que se salió.
        val detected = detector.detectSection(root, pkg, enabledTargets)

        if (detected != null) {
            val runtime = runtimeFor(detected.catalogKey)
            val state = synchronized(lock) { runtime.state }
            when (state) {
                GateState.IDLE -> triggerGate(detected, GateMode.INITIAL)
                GateState.ALLOWED -> activeCatalogKey = detected.catalogKey
                else -> { /* GATING o LIMIT_REACHED: en curso, no hacer nada. */ }
            }
        } else {
            // Ninguna sección objetivo visible: cerrar las que estuvieran activas en este paquete.
            sections.forEach { section ->
                val runtime = runtimeFor(section.catalogKey)
                val active = synchronized(lock) { runtime.state == GateState.ALLOWED }
                if (active) closeTarget(section.catalogKey)
            }
        }
    }

    // --- Disparo del gate ---

    private fun triggerGate(config: AppTargetConfig, mode: GateMode) {
        val seconds = when (mode) {
            GateMode.INITIAL -> config.initialTimerSeconds
            GateMode.RE_ENTRY -> config.reEntryTimerSeconds
        }

        val runtime = runtimeFor(config.catalogKey)
        synchronized(lock) {
            runtime.state = GateState.GATING
            runtime.continuousMillis = 0L
        }
        activeCatalogKey = null

        serviceScope.launch {
            val now = System.currentTimeMillis()
            // INITIAL inicia una nueva sesión (nueva apertura); RE_ENTRY continúa la actual.
            if (mode == GateMode.INITIAL) {
                val openId = repository.getOpenSession(config.catalogKey)?.id
                val sessionId = openId ?: repository.startSession(config.catalogKey, config.packageName, now)
                synchronized(lock) { runtime.sessionId = sessionId }
                repository.incrementInterruptions(sessionId)
            } else {
                runtime.sessionId?.let { repository.incrementInterruptions(it) }
            }
        }

        gateCoordinator.publishRequest(
            GateRequest(
                catalogKey = config.catalogKey,
                displayName = config.displayName,
                mode = mode,
                seconds = seconds
            )
        )
        launchInterruptionScreen()
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

    // --- Decisiones del usuario ---

    private fun handleDecision(decision: GateDecision) {
        val runtime = runtimeFor(decision.catalogKey)
        when (decision) {
            is GateDecision.Continue -> {
                synchronized(lock) {
                    runtime.state = GateState.ALLOWED
                    runtime.continuousMillis = 0L
                }
                activeCatalogKey = decision.catalogKey
            }

            is GateDecision.Leave -> {
                closeTarget(decision.catalogKey)
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
    }

    // --- Gestor de tiempos (uso continuo) ---

    private fun startUsageTicker() {
        serviceScope.launch {
            var ticksSinceFlush = 0
            while (isActive) {
                delay(TICK_MILLIS)
                val key = activeCatalogKey ?: continue
                val runtime = runtimeFor(key)

                val config = enabledTargets.firstOrNull { it.catalogKey == key } ?: continue

                val reachedLimit = synchronized(lock) {
                    if (runtime.state != GateState.ALLOWED) return@synchronized false
                    runtime.continuousMillis += TICK_MILLIS
                    runtime.unflushedActiveMillis += TICK_MILLIS
                    runtime.continuousMillis >= config.continuousUsageLimitSeconds * 1000L
                }

                ticksSinceFlush++
                if (ticksSinceFlush >= FLUSH_EVERY_TICKS) {
                    ticksSinceFlush = 0
                    flushActiveTime(key)
                }

                if (reachedLimit) {
                    flushActiveTime(key)
                    synchronized(lock) { runtime.state = GateState.LIMIT_REACHED }
                    activeCatalogKey = null
                    triggerGate(config, GateMode.RE_ENTRY)
                }
            }
        }
    }

    private fun flushActiveTime(catalogKey: String) {
        val runtime = runtimeFor(catalogKey)
        val (sessionId, delta) = synchronized(lock) {
            val id = runtime.sessionId
            val d = runtime.unflushedActiveMillis
            runtime.unflushedActiveMillis = 0L
            id to d
        }
        if (sessionId != null && delta > 0) {
            serviceScope.launch { repository.addActiveTime(sessionId, delta) }
        }
    }

    /** Cierra el seguimiento de un objetivo: vuelca tiempo, finaliza sesión y resetea estado. */
    private fun closeTarget(catalogKey: String) {
        if (activeCatalogKey == catalogKey) activeCatalogKey = null
        flushActiveTime(catalogKey)

        val runtime = runtimeFor(catalogKey)
        val sessionId = synchronized(lock) {
            val id = runtime.sessionId
            runtime.state = GateState.IDLE
            runtime.continuousMillis = 0L
            runtime.sessionId = null
            id
        }
        if (sessionId != null) {
            serviceScope.launch { repository.endSession(sessionId, System.currentTimeMillis()) }
        }
    }

    private fun runtimeFor(catalogKey: String): TargetRuntime = synchronized(lock) {
        runtimes.getOrPut(catalogKey) { TargetRuntime() }
    }

    companion object {
        private const val TICK_MILLIS = 1000L
        private const val FLUSH_EVERY_TICKS = 5
        private const val CONTENT_EVAL_THROTTLE_MILLIS = 300L
    }
}
