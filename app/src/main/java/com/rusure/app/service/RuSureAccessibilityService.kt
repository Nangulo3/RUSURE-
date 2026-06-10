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
import com.rusure.app.domain.model.GateAction
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

    /**
     * Marca temporal del último gate disparado. Sirve de debounce/cooldown global para silenciar
     * los disparos múltiples consecutivos que provoca la ráfaga de eventos del AccessibilityService
     * justo tras una activación. Acceso bajo [lock].
     */
    private var lastGateTriggerMillis = 0L

    /**
     * Marca temporal de la última pulsación de HOME por reimposición de bloqueo. Limita la
     * frecuencia de re-pulsado mientras un objetivo BLOCKED sigue en primer plano. Acceso bajo [lock].
     */
    private var lastBlockHomeMillis = 0L

    private data class TargetRuntime(
        var state: GateState = GateState.IDLE,
        var sessionId: Long? = null,
        var continuousMillis: Long = 0L,
        var unflushedActiveMillis: Long = 0L,
        /**
         * Instante (epoch millis) en que el objetivo ALLOWED quedó suspendido por una salida
         * transitoria (link dentro del video, comentarios, recientes, breve paso por otra app o el
         * inicio). 0 = no suspendido. Mientras esté dentro de [RESUME_GRACE_MILLIS] el reingreso se
         * reanuda sin fricción; pasada la ventana se cierra y el siguiente ingreso vuelve a gatear.
         */
        var suspendedAtMillis: Long = 0L
    )

    /** Acción a aplicar al perder visibilidad/foco un objetivo. */
    private enum class SuspendAction { SUSPEND, CLOSE, NONE }

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
                // Una entrada "fresca" a primer plano (cambia el paquete visible) equivale a una
                // APERTURA real de la app; los cambios de ventana del mismo paquete son navegación
                // interna (comentarios, perfil, buscador, etc.).
                val freshForeground = pkg != currentForegroundPackage
                if (freshForeground) {
                    onForegroundPackageChanged(pkg)
                }
                handleAppGlobal(pkg, freshForeground)
                evaluateSections(pkg)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastContentEvalMillis < CONTENT_EVAL_THROTTLE_MILLIS) return
                lastContentEvalMillis = now
                if (pkg == currentForegroundPackage) {
                    // freshForeground=false: no dispara gates nuevos (respeta "solo al abrir"),
                    // pero permite reimponer el HOME si la app global está BLOCKED y sigue arriba.
                    handleAppGlobal(pkg, freshForeground = false)
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
            // Suspender (no cerrar) el seguimiento de los objetivos del paquete anterior: si el
            // usuario vuelve dentro de la ventana de gracia (p. ej. siguió un link de un video que
            // abrió un navegador externo) se reanuda sin re-mostrar la fricción.
            val toSuspend = synchronized(lock) {
                enabledTargets.filter { it.packageName == previous }.map { it.catalogKey }
            }
            // foregroundLeft=true: el objetivo dejó realmente el primer plano (ir al inicio, otra
            // app, o matar la app desde recientes). Un gate en curso aquí se considera abandonado.
            toSuspend.forEach { suspendTarget(it, foregroundLeft = true) }
        }
    }

    // --- Apps globales ---

    private fun handleAppGlobal(pkg: String, freshForeground: Boolean) {
        val config = detector.detectAppTarget(pkg, enabledTargets) ?: return
        val runtime = runtimeFor(config.catalogKey)
        when (synchronized(lock) { runtime.state }) {
            // App global (ej. TikTok): la fricción se dispara EXCLUSIVAMENTE en la apertura real
            // de la app (entrada fresca a primer plano) o tras reiniciarla por completo. Mientras
            // el objetivo permanezca IDLE por navegación interna del mismo paquete (comentarios,
            // perfil, buscador), NO se vuelve a disparar: solo el límite de uso continuo
            // (GateMode.RE_ENTRY) o un reinicio completo reactivan la fricción.
            GateState.IDLE -> if (freshForeground) triggerGate(config, GateMode.INITIAL)
            GateState.ALLOWED -> handleAllowedReentry(config)
            // Sigue en primer plano pese al bloqueo: reimponer HOME hasta que salga realmente.
            GateState.BLOCKED -> reEnforceBlock()
            else -> { /* GATING o LIMIT_REACHED en curso: no hacer nada. */ }
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
                GateState.ALLOWED -> handleAllowedReentry(detected)
                // Sección bloqueada aún visible: reimponer HOME hasta abandonarla.
                GateState.BLOCKED -> reEnforceBlock()
                else -> { /* GATING o LIMIT_REACHED: en curso, no hacer nada. */ }
            }
        } else {
            // Ninguna sección objetivo visible: suspender las activas (abrir comentarios u otra
            // pantalla dentro de la app oculta el visor; volver dentro de la gracia NO re-gatea) y
            // cerrar las bloqueadas (salir de la sección las libera para re-bloquear al reentrar).
            // foregroundLeft=false: el paquete sigue en primer plano (solo dejó de detectarse la
            // sección), así que un gate en curso (GATING) NO se aborta.
            sections.forEach { section -> suspendTarget(section.catalogKey, foregroundLeft = false) }
        }
    }

    // --- Disparo del gate ---

    private fun triggerGate(config: AppTargetConfig, mode: GateMode) {
        // "Bloquear completamente": no se muestra pantalla de espera. NO se aplica el cooldown
        // largo de WAIT (haría que una reapertura rápida burlara el bloqueo); la reimposición del
        // HOME tiene su propia limitación de frecuencia. El estado pasa a BLOCKED y se mantiene
        // mientras el objetivo siga en primer plano.
        if (config.entryAction == GateAction.BLOCK) {
            blockTarget(config, mode)
            return
        }

        // Debounce/cooldown global (solo WAIT): ignora cualquier intento de disparo dentro de la
        // ventana de enfriamiento posterior a una activación, neutralizando los falsos positivos
        // por ráfagas de eventos. El estado permanece IDLE para no bloquear un gate legítimo
        // posterior. (El límite de uso continuo dispara RE_ENTRY muy por encima de esta ventana.)
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now - lastGateTriggerMillis < GATE_COOLDOWN_MILLIS) return
            lastGateTriggerMillis = now
        }

        val seconds = when (mode) {
            GateMode.INITIAL -> config.initialTimerSeconds
            GateMode.RE_ENTRY -> config.reEntryTimerSeconds
        }

        val runtime = runtimeFor(config.catalogKey)
        synchronized(lock) {
            runtime.state = GateState.GATING
            runtime.continuousMillis = 0L
            runtime.suspendedAtMillis = 0L
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

    /**
     * Reingreso a un objetivo en estado ALLOWED. Si la suspensión sigue dentro de la ventana de
     * gracia (salida transitoria: link dentro del video, comentarios, recientes, breve paso por
     * otra app o el inicio) se reanuda el seguimiento SIN volver a mostrar fricción. Si la gracia
     * venció, se cierra la sesión y el ingreso se trata como una apertura nueva (gate INITIAL).
     */
    private fun handleAllowedReentry(config: AppTargetConfig) {
        val expired = synchronized(lock) {
            val runtime = runtimeFor(config.catalogKey)
            val suspendedAt = runtime.suspendedAtMillis
            if (suspendedAt != 0L &&
                System.currentTimeMillis() - suspendedAt > RESUME_GRACE_MILLIS
            ) {
                true
            } else {
                // En gracia (o nunca suspendido): reanudar. Limpiar la marca de suspensión.
                runtime.suspendedAtMillis = 0L
                false
            }
        }
        if (expired) {
            closeTarget(config.catalogKey)
            triggerGate(config, GateMode.INITIAL)
        } else {
            activeCatalogKey = config.catalogKey
        }
    }

    /**
     * Salida de un objetivo. Si está ALLOWED y aún no suspendido, lo SUSPENDE: vuelca el tiempo
     * activo y detiene el contador, pero conserva estado y sesión y marca el instante de suspensión
     * (la ventana de gracia se cuenta desde la primera salida, no se refresca). Si está BLOCKED, lo
     * cierra (preserva el re-bloqueo al reentrar).
     *
     * [foregroundLeft] indica si el objetivo realmente abandonó el primer plano (true) o si solo
     * dejó de detectarse la sección sin cambiar el paquete visible (false). Un gate en curso
     * (GATING/LIMIT_REACHED) solo se cierra cuando [foregroundLeft] es true: cubre el caso de matar
     * la app desde recientes (o irse al inicio) con la fricción abierta, dejándola lista para volver
     * a gatear en el próximo ingreso. Si el paquete sigue en primer plano, el gate se respeta.
     */
    private fun suspendTarget(catalogKey: String, foregroundLeft: Boolean) {
        val action = synchronized(lock) {
            val runtime = runtimeFor(catalogKey)
            when (runtime.state) {
                GateState.ALLOWED ->
                    if (runtime.suspendedAtMillis == 0L) SuspendAction.SUSPEND else SuspendAction.NONE
                GateState.BLOCKED -> SuspendAction.CLOSE
                GateState.GATING, GateState.LIMIT_REACHED ->
                    if (foregroundLeft) SuspendAction.CLOSE else SuspendAction.NONE
                GateState.IDLE -> SuspendAction.NONE
            }
        }
        when (action) {
            SuspendAction.SUSPEND -> {
                if (activeCatalogKey == catalogKey) activeCatalogKey = null
                flushActiveTime(catalogKey)
                synchronized(lock) {
                    runtimeFor(catalogKey).suspendedAtMillis = System.currentTimeMillis()
                }
            }
            SuspendAction.CLOSE -> closeTarget(catalogKey)
            SuspendAction.NONE -> { /* No hay seguimiento reanudable que tocar. */ }
        }
    }

    /**
     * Finaliza las suspensiones cuya ventana de gracia venció sin que el usuario regresara, para
     * que la sesión no quede abierta indefinidamente. El siguiente ingreso al objetivo se tratará
     * como una apertura nueva (gate INITIAL).
     */
    private fun sweepExpiredSuspensions() {
        val now = System.currentTimeMillis()
        val expired = synchronized(lock) {
            runtimes.filter { (_, runtime) ->
                runtime.suspendedAtMillis != 0L &&
                    now - runtime.suspendedAtMillis > RESUME_GRACE_MILLIS
            }.keys.toList()
        }
        expired.forEach { closeTarget(it) }
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

    /**
     * Aplica el bloqueo completo: registra el intento como interrupción sobre una sesión cerrada
     * de inmediato (cuenta como apertura sin tiempo de uso), pasa el estado a [GateState.BLOCKED] y
     * devuelve al usuario al inicio. El estado BLOCKED hace que [reEnforceBlock] reimponga el HOME
     * en los eventos siguientes hasta que el objetivo deja el primer plano (donde [closeTarget] lo
     * resetea a IDLE). Esto evita que la animación de arranque de la app gane la carrera a una
     * única pulsación de HOME.
     */
    private fun blockTarget(config: AppTargetConfig, mode: GateMode) {
        val runtime = runtimeFor(config.catalogKey)
        synchronized(lock) {
            runtime.state = GateState.BLOCKED
            runtime.continuousMillis = 0L
            runtime.suspendedAtMillis = 0L
            lastBlockHomeMillis = System.currentTimeMillis()
        }
        activeCatalogKey = null

        serviceScope.launch {
            val now = System.currentTimeMillis()
            if (mode == GateMode.INITIAL) {
                val openId = repository.getOpenSession(config.catalogKey)?.id
                val sessionId = openId
                    ?: repository.startSession(config.catalogKey, config.packageName, now)
                repository.incrementInterruptions(sessionId)
                repository.endSession(sessionId, now)
            } else {
                runtime.sessionId?.let {
                    repository.incrementInterruptions(it)
                    repository.endSession(it, now)
                }
            }
            synchronized(lock) { runtime.sessionId = null }
        }

        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Reimpone el bloqueo mientras el objetivo BLOCKED siga en primer plano: vuelve a pulsar HOME
     * (con una limitación de frecuencia corta) para vencer la animación de arranque de la app, que
     * puede tapar una única pulsación inicial. No registra interrupciones adicionales.
     */
    private fun reEnforceBlock() {
        val shouldPress = synchronized(lock) {
            val now = System.currentTimeMillis()
            if (now - lastBlockHomeMillis < BLOCK_REPRESS_THROTTLE_MILLIS) {
                false
            } else {
                lastBlockHomeMillis = now
                true
            }
        }
        if (shouldPress) performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // --- Decisiones del usuario ---

    private fun handleDecision(decision: GateDecision) {
        val runtime = runtimeFor(decision.catalogKey)
        when (decision) {
            is GateDecision.Continue -> {
                synchronized(lock) {
                    runtime.state = GateState.ALLOWED
                    runtime.continuousMillis = 0L
                    runtime.suspendedAtMillis = 0L
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
                // Cerrar suspensiones cuya gracia venció (salidas que no volvieron).
                sweepExpiredSuspensions()
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
            runtime.suspendedAtMillis = 0L
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

        /** Ventana de enfriamiento (debounce) entre activaciones consecutivas: 2.5 s. */
        private const val GATE_COOLDOWN_MILLIS = 2500L

        /**
         * Ventana de gracia tras salir de un objetivo ALLOWED. Si el usuario regresa dentro de esta
         * ventana (siguió un link de un video que abrió otra app, miró comentarios, o salió/entró
         * brevemente) se reanuda el seguimiento SIN volver a mostrar fricción. Pasada la ventana, el
         * reingreso cuenta como apertura nueva y se vuelve a gatear.
         *
         * Es deliberadamente corta (7 s): cerrar la app desde recientes y reabrirla siempre tarda
         * más que esto (ir a recientes → deslizar la tarjeta → ir al inicio → tocar el ícono), de
         * modo que ese flujo siempre supera la gracia y vuelve a gatear. Ajustar aquí para hacerlo
         * más o menos estricto.
         */
        private const val RESUME_GRACE_MILLIS = 7_000L

        /**
         * Frecuencia mínima entre pulsaciones de HOME al reimponer un bloqueo. Suficientemente
         * corta para vencer la animación de arranque de la app sin saturar de acciones globales.
         */
        private const val BLOCK_REPRESS_THROTTLE_MILLIS = 400L
    }
}
