package com.rusure.app.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
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
 * Intercepta la apertura de apps y secciones objetivo, dispara la pantalla de fricción y mide el
 * tiempo de uso continuo para forzar gates de reingreso.
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
     * Último valor de [PauseController.isPaused] observado por el ticker. Permite detectar los
     * flancos de inicio/fin de la pausa global (ver [onPauseStarted]/[onPauseEnded]) sin depender
     * de coleccionar el [kotlinx.coroutines.flow.StateFlow] por separado. Acceso solo desde el
     * ticker (hilo único).
     */
    private var lastSeenPaused = false

    /**
     * Indica que ya hay una secuencia de re-escaneo diferido en curso para capturar un visor de
     * sección recién abierto (ver [scheduleSectionSettleScan]). Evita encolar secuencias solapadas.
     * Acceso bajo [lock].
     */
    private var settleScanInProgress = false

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

    /**
     * Salidas del primer plano PENDIENTES DE CONFIRMAR, por paquete: instante en que llegó el primer
     * evento de otro paquete. Un evento con otro `packageName` NO demuestra que el usuario saliera de
     * la app: el teclado, la persiana de notificaciones, los diálogos del sistema y las hojas de
     * compartir son ventanas auxiliares que se superponen sin que la app deje de ser la que el
     * usuario está usando. La salida solo se da por buena si, pasados
     * [FOREGROUND_EXIT_CONFIRM_MILLIS], la app objetivo ya no tiene una ventana de aplicación en
     * pantalla (ver [confirmPendingExits]). Acceso bajo [lock].
     */
    private val pendingExits = HashMap<String, Long>()

    private data class TargetRuntime(
        var state: GateState = GateState.IDLE,
        var sessionId: Long? = null,
        var continuousMillis: Long = 0L,
        var unflushedActiveMillis: Long = 0L,
        /**
         * Instante (epoch millis) en que el objetivo ALLOWED quedó suspendido por una salida
         * transitoria (navegación interna: comentarios/perfil/descripción; o salida del primer
         * plano: recientes, otra app, el inicio). 0 = no suspendido. Mientras el transcurrido desde
         * aquí no supere [suspendGraceMillis] el reingreso se reanuda sin fricción; pasada la ventana
         * se cierra y el siguiente ingreso vuelve a gatear.
         */
        var suspendedAtMillis: Long = 0L,
        /**
         * Ventana de gracia aplicable a la suspensión en curso, fijada al suspender según la causa:
         * [NO_EXPIRY] si solo se perdió la sección (app aún en primer plano, navegación interna) o
         * [BACKGROUND_GRACE_MILLIS] si la app dejó el primer plano. 0 cuando no hay suspensión.
         */
        var suspendGraceMillis: Long = 0L
    )

    /** Acción a aplicar al perder visibilidad/foco un objetivo. */
    private enum class SuspendAction { SUSPEND, TIGHTEN, CLOSE, NONE }

    override fun onServiceConnected() {
        super.onServiceConnected()

        lastSeenPaused = pauseController.isPaused()

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
                // El visor de una sección (Reels) suele NO estar inflado/visible en el instante del
                // cambio de ventana y luego deja de emitir eventos al reproducirse (superficie de
                // video). Re-escaneamos unas veces tras la transición para capturarlo una vez listo.
                scheduleSectionSettleScan(pkg)
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
                    scheduleSectionSettleScan(pkg)
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
        // Pantalla apagándose o teléfono bloqueándose: el salto a keyguard/systemui NO es una salida
        // real de la app. No reclasificar el primer plano ni suspender como segundo plano; al
        // desbloquear, el objetivo retoma sin fricción y el límite no avanzó (ver isDeviceActive()).
        if (!isDeviceActive()) return

        val previous = currentForegroundPackage
        currentForegroundPackage = newPackage

        // Volver al paquete cuya salida estaba pendiente de confirmar: nunca llegó a salir (p. ej. se
        // cerró el teclado). Se cancela la salida sin haber suspendido nada.
        val returnedFromPendingExit = synchronized(lock) { pendingExits.remove(newPackage) != null }
        if (returnedFromPendingExit) return

        if (previous == null) return

        // NO se suspende aquí. Un evento de otro paquete es solo un INDICIO de salida: se anota como
        // salida pendiente y el ticker la confirma o la descarta en confirmPendingExits(). Así, las
        // ventanas auxiliares (teclado del buscador o de los comentarios, persiana, diálogos) dejan
        // de interpretarse como "el usuario salió de la app" y no rearman la fricción.
        synchronized(lock) {
            if (enabledTargets.none { it.packageName == previous }) return
            // El reloj de observación se cuenta desde el PRIMER indicio, no desde el último evento.
            if (!pendingExits.containsKey(previous)) {
                pendingExits[previous] = System.currentTimeMillis()
            }
        }
    }

    /**
     * Resuelve las salidas pendientes cuya ventana de confirmación ya venció:
     *
     * - Si la app sigue teniendo una **ventana de aplicación** en pantalla, la ventana ajena era
     *   auxiliar (teclado, diálogo, persiana): se descarta la salida y se restaura el primer plano,
     *   de modo que el objetivo conserva su estado, su sesión y su reloj de límite.
     * - Si ya no la tiene, la salida fue real: se suspenden sus objetivos igual que antes
     *   (`foregroundLeft = true`), con la gracia corta de segundo plano.
     *
     * Si la lista de ventanas no está disponible, se opta por el comportamiento conservador
     * (confirmar la salida), que es el que existía antes de esta confirmación diferida.
     */
    private fun confirmPendingExits() {
        // Con la pantalla apagada o el teléfono bloqueado no se concluye nada: la observación queda
        // en espera hasta que el usuario vuelva a usar el dispositivo (misma regla que en
        // onForegroundPackageChanged; apagar la pantalla no es salir de la app).
        if (!isDeviceActive()) return

        val now = System.currentTimeMillis()
        val due = synchronized(lock) {
            val ready = pendingExits.filterValues { now - it >= FOREGROUND_EXIT_CONFIRM_MILLIS }.keys.toList()
            ready.forEach { pendingExits.remove(it) }
            ready
        }
        if (due.isEmpty()) return

        for (packageName in due) {
            if (hasApplicationWindow(packageName)) {
                // Falsa alarma: la app objetivo nunca dejó de ser la que el usuario está usando.
                // Solo se le devuelve el primer plano si este no pertenece ya a OTRO objetivo
                // vigilado (pantalla dividida o PiP: dos apps con ventana de aplicación a la vez).
                val foregroundTakenByAnotherTarget = synchronized(lock) {
                    val current = currentForegroundPackage
                    current != null && current != packageName &&
                        enabledTargets.any { it.packageName == current }
                }
                if (!foregroundTakenByAnotherTarget) currentForegroundPackage = packageName
                continue
            }
            val toSuspend = synchronized(lock) {
                enabledTargets.filter { it.packageName == packageName }.map { it.catalogKey }
            }
            // foregroundLeft=true: salida real del primer plano (inicio, otra app, kill). Un gate en
            // curso aquí se considera abandonado.
            toSuspend.forEach { suspendTarget(it, foregroundLeft = true) }
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
        if (visibleWindows.isNullOrEmpty()) return false
        return visibleWindows.any { window ->
            window != null &&
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                window.root?.packageName?.toString() == packageName
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

    /**
     * Re-evalúa las secciones del paquete a intervalos cortos tras un evento de navegación. Cubre el
     * caso en que el visor de una sección (p. ej. el reproductor de Reels) aún no está inflado/visible
     * en el instante del evento y, una vez a pantalla completa, deja de emitir eventos de accesibilidad
     * (es una superficie de video): sin esto la detección "se pierde" la apertura y la fricción no
     * salta hasta que algo (abrir comentarios) vuelve a mover el árbol.
     *
     * Solo se programa si hay alguna sección del paquete en [GateState.IDLE] (aún sin capturar): una
     * vez ALLOWED/GATING/BLOCKED no aporta nada. Una única secuencia activa a la vez ([settleScanInProgress]).
     */
    private fun scheduleSectionSettleScan(pkg: String) {
        val needsScan = enabledTargets.any {
            it.targetType == TargetType.SECTION && it.packageName == pkg &&
                synchronized(lock) { runtimeFor(it.catalogKey).state == GateState.IDLE }
        }
        if (!needsScan) return

        synchronized(lock) {
            if (settleScanInProgress) return
            settleScanInProgress = true
        }

        serviceScope.launch {
            try {
                for (delayMillis in SETTLE_SCAN_DELAYS_MILLIS) {
                    delay(delayMillis)
                    if (pkg != currentForegroundPackage) break
                    evaluateSections(pkg)
                }
            } finally {
                synchronized(lock) { settleScanInProgress = false }
            }
        }
    }

    // --- Disparo del gate ---

    private fun triggerGate(config: AppTargetConfig, mode: GateMode) {
        // Protección en pausa global (ver docs/DECISIONS.md D-009): deja pasar sin fricción,
        // incluso a un objetivo BLOCK. Antes que cualquier otra comprobación.
        if (pauseController.isPaused()) {
            allowWithoutFriction(config, mode)
            return
        }

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
            runtime.suspendGraceMillis = 0L
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
     * Deja pasar un objetivo sin mostrar fricción porque la protección está en pausa global (ver
     * [PauseController] y `docs/DECISIONS.md` D-009). Equivale a pulsar "Continuar" sin pantalla:
     * el objetivo pasa a [GateState.ALLOWED] y su tiempo se sigue contando con normalidad. En modo
     * INITIAL reutiliza la sesión abierta (o abre una) SIN incrementar interrupciones, porque no
     * hubo ninguna: la apertura y el tiempo de uso cuentan en estadísticas igual que si la
     * protección estuviera activa.
     */
    private fun allowWithoutFriction(config: AppTargetConfig, mode: GateMode) {
        val runtime = runtimeFor(config.catalogKey)
        synchronized(lock) {
            runtime.state = GateState.ALLOWED
            runtime.continuousMillis = 0L
            runtime.suspendedAtMillis = 0L
            runtime.suspendGraceMillis = 0L
        }
        activeCatalogKey = config.catalogKey

        if (mode == GateMode.INITIAL) {
            serviceScope.launch {
                val now = System.currentTimeMillis()
                val openId = repository.getOpenSession(config.catalogKey)?.id
                val sessionId = openId ?: repository.startSession(config.catalogKey, config.packageName, now)
                synchronized(lock) { runtime.sessionId = sessionId }
            }
        }
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
                System.currentTimeMillis() - suspendedAt > runtime.suspendGraceMillis
            ) {
                true
            } else {
                // En gracia (o nunca suspendido): reanudar. Limpiar la marca de suspensión.
                runtime.suspendedAtMillis = 0L
                runtime.suspendGraceMillis = 0L
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
        // Navegación interna (la sección perdió visibilidad pero la app sigue en primer plano) NUNCA
        // expira: comentarios/perfil/historias/pestañas de cualquier duración reanudan sin fricción.
        // Dejar el primer plano sí es estricto: gracia corta y nueva sesión al volver más tarde.
        val grace = if (foregroundLeft) BACKGROUND_GRACE_MILLIS else NO_EXPIRY
        val action = synchronized(lock) {
            val runtime = runtimeFor(catalogKey)
            when (runtime.state) {
                GateState.ALLOWED ->
                    if (runtime.suspendedAtMillis == 0L) {
                        SuspendAction.SUSPEND
                    } else if (foregroundLeft && runtime.suspendGraceMillis > grace) {
                        // Estaba en navegación interna (sin expiración) y ahora la app deja el primer
                        // plano: endurecer a la gracia corta, reiniciando el reloj desde aquí.
                        SuspendAction.TIGHTEN
                    } else {
                        SuspendAction.NONE
                    }
                GateState.BLOCKED -> SuspendAction.CLOSE
                GateState.GATING, GateState.LIMIT_REACHED ->
                    if (foregroundLeft) SuspendAction.CLOSE else SuspendAction.NONE
                GateState.IDLE -> SuspendAction.NONE
            }
        }
        when (action) {
            SuspendAction.SUSPEND -> {
                // Solo al pasar a segundo plano se deja de rastrear (el reloj de límite se detiene).
                // En navegación interna se CONSERVA activeCatalogKey para que el límite (reloj de
                // pared) siga avanzando mientras la app permanezca en primer plano.
                if (foregroundLeft && activeCatalogKey == catalogKey) activeCatalogKey = null
                flushActiveTime(catalogKey)
                synchronized(lock) {
                    val runtime = runtimeFor(catalogKey)
                    runtime.suspendedAtMillis = System.currentTimeMillis()
                    runtime.suspendGraceMillis = grace
                }
            }
            SuspendAction.TIGHTEN -> {
                // Navegación interna -> segundo plano: endurecer y detener el seguimiento del límite.
                if (activeCatalogKey == catalogKey) activeCatalogKey = null
                flushActiveTime(catalogKey)
                synchronized(lock) {
                    val runtime = runtimeFor(catalogKey)
                    runtime.suspendedAtMillis = System.currentTimeMillis()
                    runtime.suspendGraceMillis = grace
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
                    now - runtime.suspendedAtMillis > runtime.suspendGraceMillis
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
            runtime.suspendGraceMillis = 0L
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
        // Protección en pausa global: no reimponer HOME (el objetivo ya se dejó pasar, ver
        // triggerGate/allowWithoutFriction; este BLOCKED es residual de antes de la pausa y
        // onPauseStarted ya lo cerró, pero por si acaso no se reafirma aquí).
        if (pauseController.isPaused()) return

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
                    runtime.suspendGraceMillis = 0L
                }
                activeCatalogKey = decision.catalogKey
            }

            is GateDecision.Leave -> {
                val sessionId = synchronized(lock) { runtimeFor(decision.catalogKey).sessionId }
                sessionId?.let { id -> serviceScope.launch { repository.incrementCancelled(id) } }
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

                // Flanco de inicio/fin de la pausa global (ver PauseController, D-009). Se exige el
                // dispositivo activo para procesar un FIN, igual que el resto de la lógica de
                // salida/reingreso: si la pausa vence con la pantalla apagada o el teléfono
                // bloqueado, la reactivación se aplica en cuanto vuelva a estar activo.
                val paused = pauseController.isPaused()
                if (paused != lastSeenPaused && (paused || isDeviceActive())) {
                    if (paused) onPauseStarted() else onPauseEnded()
                    lastSeenPaused = paused
                }

                // Confirmar o descartar las salidas del primer plano que están en observación.
                confirmPendingExits()
                // Cerrar suspensiones de segundo plano cuya gracia venció (salidas que no volvieron).
                sweepExpiredSuspensions()
                val key = activeCatalogKey ?: continue
                val runtime = runtimeFor(key)
                val config = enabledTargets.firstOrNull { it.catalogKey == key } ?: continue

                // En uso real cuando la app del objetivo está en primer plano y el dispositivo activo
                // (pantalla encendida, no bloqueado). Incluye la navegación interna del mismo paquete.
                val inUse = isDeviceActive() && config.packageName == currentForegroundPackage

                val reachedLimit = synchronized(lock) {
                    if (runtime.state != GateState.ALLOWED) return@synchronized false
                    // LÍMITE de uso continuo (reloj de pared): avanza mientras la app siga en primer
                    // plano, INCLUSO en navegación interna (comentarios/perfil). No avanza en segundo
                    // plano ni con el teléfono bloqueado. Dispara exacto al tiempo configurado.
                    if (inUse) runtime.continuousMillis += TICK_MILLIS
                    // Tiempo ACTIVO (stats): solo cuando la sección/objetivo está realmente visible
                    // (no suspendido) y el dispositivo activo. Se cuenta con normalidad también
                    // durante la pausa (D-009, decisión #3).
                    if (inUse && runtime.suspendedAtMillis == 0L) {
                        runtime.unflushedActiveMillis += TICK_MILLIS
                    }
                    // El recordatorio de uso continuo NO salta mientras la protección está pausada, ni
                    // tras vencer la pausa hasta que onPauseEnded() la haya procesado (con el teléfono
                    // bloqueado el fin queda diferido y el reloj acumulado en la pausa no debe disparar).
                    !paused && !lastSeenPaused &&
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

    /**
     * Al iniciarse la pausa global (ver [PauseController], D-009): cierra los objetivos que
     * estaban en [GateState.BLOCKED] para que, mientras la pausa siga activa, el próximo ingreso
     * entre sin fricción ([triggerGate] lo resuelve al ver [PauseController.isPaused]). No toca
     * GATING/LIMIT_REACHED (la pantalla de fricción ya en curso se respeta) ni ALLOWED (sigue
     * contando tiempo con normalidad).
     */
    private fun onPauseStarted() {
        val blocked = synchronized(lock) {
            runtimes.filterValues { it.state == GateState.BLOCKED }.keys.toList()
        }
        blocked.forEach { closeTarget(it) }
    }

    /**
     * Al terminar la pausa global (por vencimiento natural o "Reanudar ahora"): cada objetivo que
     * quedó [GateState.ALLOWED] durante la pausa se reevalúa contra su configuración real.
     *
     * - Si su app/sección sigue siendo la que está en pantalla ahora mismo, se dispara un gate
     *   INITIAL (respeta `entryAction`: espera o HOME) que reutiliza la sesión abierta durante la
     *   pausa, así que no cuenta como una apertura extra. Solo se dispara UNO: [GateCoordinator]
     *   solo guarda una solicitud de fricción pendiente a la vez.
     * - El resto se cierra ([closeTarget]): vuelve a IDLE y el próximo ingreso gatea con
     *   normalidad.
     */
    private fun onPauseEnded() {
        val allowedKeys = synchronized(lock) {
            lastGateTriggerMillis = 0L
            runtimes.filterValues { it.state == GateState.ALLOWED }.keys.toList()
        }
        if (allowedKeys.isEmpty()) return

        // APP_GLOBAL antes que SECTION: si ambos son candidatos visibles, prioriza el más externo.
        val ordered = allowedKeys.sortedBy { key ->
            val type = enabledTargets.firstOrNull { it.catalogKey == key }?.targetType
            if (type == TargetType.APP_GLOBAL) 0 else 1
        }

        var gateFired = false
        for (key in ordered) {
            val config = enabledTargets.firstOrNull { it.catalogKey == key }
            if (config == null) {
                closeTarget(key)
                continue
            }
            val runtime = runtimeFor(key)
            val visible = config.packageName == currentForegroundPackage &&
                synchronized(lock) { runtime.suspendedAtMillis == 0L }
            if (visible && !gateFired) {
                triggerGate(config, GateMode.INITIAL)
                gateFired = true
            } else {
                closeTarget(key)
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
            runtime.suspendGraceMillis = 0L
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

    /**
     * El usuario está realmente usando el dispositivo: pantalla encendida y NO bloqueada. Apagar la
     * pantalla o bloquear/desbloquear el teléfono SIN salir de la app no debe contar como salida ni
     * avanzar el límite de uso continuo; al volver, el objetivo retoma sin fricción.
     */
    private fun isDeviceActive(): Boolean =
        powerManager.isInteractive && !keyguardManager.isKeyguardLocked

    companion object {
        private const val TICK_MILLIS = 1000L
        private const val FLUSH_EVERY_TICKS = 5
        private const val CONTENT_EVAL_THROTTLE_MILLIS = 300L

        /**
         * Instantes (relativos, acumulativos) en que se re-evalúan las secciones tras un evento de
         * navegación, para capturar un visor que se infla con retraso y luego deja de emitir eventos.
         * Cubren ~2.6 s en total, suficiente para que el reproductor de Reels/Shorts termine de
         * renderizar sin penalizar el rendimiento.
         */
        private val SETTLE_SCAN_DELAYS_MILLIS = longArrayOf(400L, 600L, 700L, 900L)

        /** Ventana de enfriamiento (debounce) entre activaciones consecutivas: 2.5 s. */
        private const val GATE_COOLDOWN_MILLIS = 2500L

        /**
         * Ventana de gracia tras DEJAR el primer plano un objetivo ALLOWED (ir al inicio, otra app o
         * cerrar la app desde recientes). Si el usuario vuelve dentro de esta ventana se reanuda el
         * seguimiento SIN re-mostrar fricción; pasada la ventana, el reingreso cuenta como apertura
         * nueva y se vuelve a gatear.
         *
         * Es deliberadamente corta (7 s): salir realmente de la app y volver "más tarde" siempre
         * tarda más que esto, de modo que ese flujo supera la gracia y vuelve a gatear (nueva sesión
         * tras pasar a segundo plano).
         */
        private const val BACKGROUND_GRACE_MILLIS = 7_000L

        /**
         * Tiempo de observación antes de dar por buena una salida del primer plano. Un evento de otro
         * paquete solo abre la observación; pasada esta ventana se comprueba si la app objetivo sigue
         * teniendo una ventana de aplicación en pantalla (ver [confirmPendingExits]).
         *
         * 3 s son suficientes para que termine la animación de transición entre apps —de modo que un
         * cambio real de aplicación se confirme— y para que una ventana auxiliar (teclado del
         * buscador o de los comentarios, persiana, diálogo) quede descartada como falsa salida. La
         * gracia de segundo plano [BACKGROUND_GRACE_MILLIS] se cuenta A PARTIR de la confirmación.
         */
        private const val FOREGROUND_EXIT_CONFIRM_MILLIS = 3_000L

        /**
         * Centinela de gracia "sin expiración" para la navegación interna: la sección perdió
         * visibilidad pero la app sigue en primer plano (comentarios/respuestas, perfil del creador,
         * historias, descripción, pestañas internas). Esa navegación NUNCA re-muestra fricción, dure
         * lo que dure; la suspensión interna solo termina si la app deja el primer plano (se endurece
         * a [BACKGROUND_GRACE_MILLIS]) o si se alcanza el límite de uso continuo.
         */
        private const val NO_EXPIRY = Long.MAX_VALUE

        /**
         * Frecuencia mínima entre pulsaciones de HOME al reimponer un bloqueo. Suficientemente
         * corta para vencer la animación de arranque de la app sin saturar de acciones globales.
         */
        private const val BLOCK_REPRESS_THROTTLE_MILLIS = 400L
    }
}
