package com.rusure.app.domain.engine

import com.rusure.app.data.local.entity.AppTargetConfig
import com.rusure.app.domain.gate.GateCoordinator
import com.rusure.app.domain.gate.GateDecision
import com.rusure.app.domain.gate.GateMode
import com.rusure.app.domain.gate.GateRequest
import com.rusure.app.domain.gate.GateState
import com.rusure.app.domain.model.GateAction
import com.rusure.app.domain.model.TargetType
import com.rusure.app.domain.pause.PauseController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * La máquina de estados de la fricción: decide cuándo interrumpir, cronometra el uso continuo y
 * mantiene el estado en memoria de cada objetivo.
 *
 * Extraído de `RuSureAccessibilityService` en la fase F3 **sin cambiar comportamiento**: mismas
 * constantes, mismo orden de llamadas y los mismos bloques `synchronized`. El servicio queda como
 * adaptador de Android (recibe eventos, provee las implementaciones reales y lanza los efectos).
 *
 * No depende de Android, para que la máquina de estados sea verificable con tests JVM (D-010): el
 * reloj, las ventanas, el estado del dispositivo, la detección, la persistencia y los efectos
 * entran inyectados.
 *
 * El estado **no se persiste**: si muere el proceso se pierde (ver `DEEP_INIT_REPORT.md` E10/E11).
 */
class GateEngine(
    private val clock: Clock,
    private val windowProbe: WindowProbe,
    private val deviceState: DeviceState,
    private val appTargetProbe: AppTargetProbe,
    private val sectionProbe: SectionProbe,
    private val sessions: SessionStore,
    private val effects: EngineEffects,
    private val gateCoordinator: GateCoordinator,
    private val pauseController: PauseController,
    private val scope: CoroutineScope,
    private val tracer: Tracer = Tracer.None
) {

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

    /** Ticks acumulados desde el último volcado de tiempo activo a la persistencia. */
    private var ticksSinceFlush = 0

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

    /** Estado por objetivo, para acompañar a cada evento/transición en la traza. */
    fun runtimesSnapshot(): String = synchronized(lock) {
        if (runtimes.isEmpty()) {
            "-"
        } else {
            runtimes.entries.joinToString(" ") { (key, runtime) ->
                val suspended = when {
                    runtime.suspendedAtMillis == 0L -> ""
                    runtime.suspendGraceMillis == NO_EXPIRY -> "/susp(sin expiracion)"
                    else -> "/susp(${runtime.suspendGraceMillis}ms)"
                }
                "$key=${runtime.state}$suspended"
            }
        }
    }

    // --- Entradas ---

    /** El motor arranca: toma el estado inicial de la pausa global. */
    fun onStarted() {
        lastSeenPaused = pauseController.isPaused()
        tracer.trace { "servicio conectado | pausado=$lastSeenPaused" }
    }

    /** Nueva lista de objetivos habilitados (emisión del Flow de configuración). */
    fun onTargetsChanged(targets: List<AppTargetConfig>) {
        enabledTargets = targets
        tracer.trace {
            "objetivos habilitados | " +
                targets.joinToString(" ") { "${it.catalogKey}:${it.targetType}:${it.entryAction}" }
        }
    }

    /** `TYPE_WINDOW_STATE_CHANGED` de un paquete ajeno a RuSure. */
    fun onWindowStateChanged(packageName: String) {
        // Una entrada "fresca" a primer plano (cambia el paquete visible) equivale a una
        // APERTURA real de la app; los cambios de ventana del mismo paquete son navegación
        // interna (comentarios, perfil, buscador, etc.).
        val freshForeground = packageName != currentForegroundPackage
        tracer.trace {
            "evento WINDOW_STATE | pkg=$packageName fresco=$freshForeground " +
                "primerPlano=$currentForegroundPackage estados=[${runtimesSnapshot()}]"
        }
        if (freshForeground) {
            onForegroundPackageChanged(packageName)
        }
        handleAppGlobal(packageName, freshForeground)
        evaluateSections(packageName)
        // El visor de una sección (Reels) suele NO estar inflado/visible en el instante del
        // cambio de ventana y luego deja de emitir eventos al reproducirse (superficie de
        // video). Re-escaneamos unas veces tras la transición para capturarlo una vez listo.
        scheduleSectionSettleScan(packageName)
    }

    /** `TYPE_WINDOW_CONTENT_CHANGED` de un paquete ajeno a RuSure (con su propio throttle). */
    fun onContentChanged(packageName: String) {
        val now = clock.now()
        if (now - lastContentEvalMillis < CONTENT_EVAL_THROTTLE_MILLIS) return
        lastContentEvalMillis = now
        tracer.trace {
            "evento WINDOW_CONTENT | pkg=$packageName " +
                "enPrimerPlano=${packageName == currentForegroundPackage} " +
                "primerPlano=$currentForegroundPackage estados=[${runtimesSnapshot()}]"
        }
        if (packageName == currentForegroundPackage) {
            // freshForeground=false: no dispara gates nuevos (respeta "solo al abrir"),
            // pero permite reimponer el HOME si la app global está BLOCKED y sigue arriba.
            handleAppGlobal(packageName, freshForeground = false)
            evaluateSections(packageName)
            scheduleSectionSettleScan(packageName)
        }
    }

    /** Decisión del usuario en la pantalla de fricción. */
    fun onDecision(decision: GateDecision) {
        val runtime = runtimeFor(decision.catalogKey)
        tracer.trace {
            val tipo = if (decision is GateDecision.Continue) "Continuar" else "Salir"
            "decision ${decision.catalogKey} | $tipo"
        }
        when (decision) {
            is GateDecision.Continue -> {
                synchronized(lock) {
                    runtime.state = GateState.ALLOWED
                    runtime.continuousMillis = 0L
                    runtime.suspendedAtMillis = 0L
                    runtime.suspendGraceMillis = 0L
                }
                activeCatalogKey = decision.catalogKey
                tracer.trace { "transicion ${decision.catalogKey} -> ALLOWED | el usuario continuo" }
            }

            is GateDecision.Leave -> {
                val sessionId = synchronized(lock) { runtimeFor(decision.catalogKey).sessionId }
                sessionId?.let { id -> scope.launch { sessions.incrementCancelled(id) } }
                closeTarget(decision.catalogKey)
                effects.goHome()
            }
        }
    }

    /**
     * Un tick del reloj de uso ([TICK_MILLIS]). Lo invoca el bucle del adaptador. Barre las
     * suspensiones vencidas, resuelve las salidas en observación, acumula tiempo y dispara el
     * recordatorio de uso continuo.
     */
    fun onTick() {
        // Flanco de inicio/fin de la pausa global (ver PauseController, D-009). Se exige el
        // dispositivo activo para procesar un FIN, igual que el resto de la lógica de
        // salida/reingreso: si la pausa vence con la pantalla apagada o el teléfono
        // bloqueado, la reactivación se aplica en cuanto vuelva a estar activo.
        val paused = pauseController.isPaused()
        if (paused != lastSeenPaused && (paused || isDeviceActive())) {
            tracer.trace { "flanco de pausa | pausado=$paused estados=[${runtimesSnapshot()}]" }
            if (paused) onPauseStarted() else onPauseEnded()
            lastSeenPaused = paused
        }

        // Confirmar o descartar las salidas del primer plano que están en observación.
        confirmPendingExits()
        // Cerrar suspensiones de segundo plano cuya gracia venció (salidas que no volvieron).
        sweepExpiredSuspensions()
        val key = activeCatalogKey ?: return
        val runtime = runtimeFor(key)
        val config = enabledTargets.firstOrNull { it.catalogKey == key } ?: return

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

        tracer.trace {
            val (continuo, suspendido) = synchronized(lock) {
                runtime.continuousMillis to (runtime.suspendedAtMillis != 0L)
            }
            "tick $key | estado=${synchronized(lock) { runtime.state }} enUso=$inUse " +
                "suspendido=$suspendido limite=${continuo}/${config.continuousUsageLimitSeconds * 1000L}ms"
        }

        ticksSinceFlush++
        if (ticksSinceFlush >= FLUSH_EVERY_TICKS) {
            ticksSinceFlush = 0
            flushActiveTime(key)
        }

        if (reachedLimit) {
            tracer.trace { "transicion $key -> LIMIT_REACHED | limite alcanzado -> gate RE_ENTRY" }
            flushActiveTime(key)
            synchronized(lock) { runtime.state = GateState.LIMIT_REACHED }
            activeCatalogKey = null
            triggerGate(config, GateMode.RE_ENTRY)
        }
    }

    // --- Cambio de app en primer plano ---

    private fun onForegroundPackageChanged(newPackage: String) {
        // Pantalla apagándose o teléfono bloqueándose: el salto a keyguard/systemui NO es una salida
        // real de la app. No reclasificar el primer plano ni suspender como segundo plano; al
        // desbloquear, el objetivo retoma sin fricción y el límite no avanzó (ver isDeviceActive()).
        if (!isDeviceActive()) {
            tracer.trace { "primer plano IGNORADO (dispositivo inactivo) | pkg=$newPackage" }
            return
        }

        val previous = currentForegroundPackage
        currentForegroundPackage = newPackage

        // Volver al paquete cuya salida estaba pendiente de confirmar: nunca llegó a salir (p. ej. se
        // cerró el teclado). Se cancela la salida sin haber suspendido nada.
        val returnedFromPendingExit = synchronized(lock) { pendingExits.remove(newPackage) != null }
        if (returnedFromPendingExit) {
            tracer.trace { "primer plano $previous -> $newPackage | salida pendiente CANCELADA (regreso)" }
            return
        }

        if (previous == null) {
            tracer.trace { "primer plano (sin anterior) -> $newPackage" }
            return
        }

        // NO se suspende aquí. Un evento de otro paquete es solo un INDICIO de salida: se anota como
        // salida pendiente y el ticker la confirma o la descarta en confirmPendingExits(). Así, las
        // ventanas auxiliares (teclado del buscador o de los comentarios, persiana, diálogos) dejan
        // de interpretarse como "el usuario salió de la app" y no rearman la fricción.
        val recorded = synchronized(lock) {
            if (enabledTargets.none { it.packageName == previous }) return
            // El reloj de observación se cuenta desde el PRIMER indicio, no desde el último evento.
            if (!pendingExits.containsKey(previous)) {
                pendingExits[previous] = clock.now()
                true
            } else {
                false
            }
        }
        tracer.trace {
            val estado = if (recorded) "ANOTADA" else "ya en observacion"
            "primer plano $previous -> $newPackage | salida pendiente $estado " +
                "(confirmacion en ${FOREGROUND_EXIT_CONFIRM_MILLIS}ms)"
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

        val now = clock.now()
        val due = synchronized(lock) {
            val ready = pendingExits.filterValues { now - it >= FOREGROUND_EXIT_CONFIRM_MILLIS }.keys.toList()
            ready.forEach { pendingExits.remove(it) }
            ready
        }
        if (due.isEmpty()) return

        for (packageName in due) {
            if (windowProbe.hasApplicationWindow(packageName)) {
                // Falsa alarma: la app objetivo nunca dejó de ser la que el usuario está usando.
                // Solo se le devuelve el primer plano si este no pertenece ya a OTRO objetivo
                // vigilado (pantalla dividida o PiP: dos apps con ventana de aplicación a la vez).
                val foregroundTakenByAnotherTarget = synchronized(lock) {
                    val current = currentForegroundPackage
                    current != null && current != packageName &&
                        enabledTargets.any { it.packageName == current }
                }
                if (!foregroundTakenByAnotherTarget) currentForegroundPackage = packageName
                tracer.trace {
                    "salida DESCARTADA | pkg=$packageName conserva ventana de aplicacion " +
                        "primerPlanoDeOtroObjetivo=$foregroundTakenByAnotherTarget " +
                        "estados=[${runtimesSnapshot()}]"
                }
                continue
            }
            val toSuspend = synchronized(lock) {
                enabledTargets.filter { it.packageName == packageName }.map { it.catalogKey }
            }
            tracer.trace { "salida CONFIRMADA | pkg=$packageName suspende=$toSuspend" }
            // foregroundLeft=true: salida real del primer plano (inicio, otra app, kill). Un gate en
            // curso aquí se considera abandonado.
            toSuspend.forEach { suspendTarget(it, foregroundLeft = true) }
        }
    }

    // --- Apps globales ---

    private fun handleAppGlobal(pkg: String, freshForeground: Boolean) {
        val config = appTargetProbe.detect(pkg, enabledTargets) ?: return
        val runtime = runtimeFor(config.catalogKey)
        val state = synchronized(lock) { runtime.state }
        tracer.trace {
            val accion = when (state) {
                GateState.IDLE -> if (freshForeground) "gate INITIAL" else "nada (no fresco)"
                GateState.ALLOWED -> "reingreso"
                GateState.BLOCKED -> "reimponer HOME"
                else -> "nada ($state en curso)"
            }
            "APP_GLOBAL ${config.catalogKey} | estado=$state fresco=$freshForeground -> $accion"
        }
        when (state) {
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

        when (val scan = sectionProbe.scan(pkg, enabledTargets)) {
            // null transitorio: no concluir que se salió.
            SectionScan.Unavailable ->
                tracer.trace { "SECCION $pkg | arbol no disponible (sin conclusion)" }

            is SectionScan.Detected -> {
                val detected = scan.config
                val runtime = runtimeFor(detected.catalogKey)
                val state = synchronized(lock) { runtime.state }
                tracer.trace {
                    val accion = when (state) {
                        GateState.IDLE -> "gate INITIAL"
                        GateState.ALLOWED -> "reingreso"
                        GateState.BLOCKED -> "reimponer HOME"
                        else -> "nada ($state en curso)"
                    }
                    "SECCION ${detected.catalogKey} DETECTADA | estado=$state -> $accion"
                }
                when (state) {
                    GateState.IDLE -> triggerGate(detected, GateMode.INITIAL)
                    GateState.ALLOWED -> handleAllowedReentry(detected)
                    // Sección bloqueada aún visible: reimponer HOME hasta abandonarla.
                    GateState.BLOCKED -> reEnforceBlock()
                    else -> { /* GATING o LIMIT_REACHED: en curso, no hacer nada. */ }
                }
            }

            SectionScan.NotDetected -> {
                // Ninguna sección objetivo visible: suspender las activas (abrir comentarios u otra
                // pantalla dentro de la app oculta el visor; volver dentro de la gracia NO re-gatea) y
                // cerrar las bloqueadas (salir de la sección las libera para re-bloquear al reentrar).
                // foregroundLeft=false: el paquete sigue en primer plano (solo dejó de detectarse la
                // sección), así que un gate en curso (GATING) NO se aborta.
                tracer.trace {
                    "SECCION no detectada en $pkg | nav. interna -> suspende ${sections.map { it.catalogKey }}"
                }
                sections.forEach { section -> suspendTarget(section.catalogKey, foregroundLeft = false) }
            }
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

        tracer.trace { "settle scan programado | pkg=$pkg" }
        scope.launch {
            try {
                var acumulado = 0L
                for (delayMillis in SETTLE_SCAN_DELAYS_MILLIS) {
                    delay(delayMillis)
                    acumulado += delayMillis
                    if (pkg != currentForegroundPackage) {
                        tracer.trace {
                            "settle scan abortado (+${acumulado}ms) | primerPlano=$currentForegroundPackage"
                        }
                        break
                    }
                    tracer.trace { "settle scan +${acumulado}ms | pkg=$pkg" }
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
            tracer.trace { "gate ${config.catalogKey} $mode | PAUSA GLOBAL -> pasa sin friccion" }
            allowWithoutFriction(config, mode)
            return
        }

        // "Bloquear completamente": no se muestra pantalla de espera. NO se aplica el cooldown
        // largo de WAIT (haría que una reapertura rápida burlara el bloqueo); la reimposición del
        // HOME tiene su propia limitación de frecuencia. El estado pasa a BLOCKED y se mantiene
        // mientras el objetivo siga en primer plano.
        if (config.entryAction == GateAction.BLOCK) {
            tracer.trace { "gate ${config.catalogKey} $mode | entryAction=BLOCK -> bloqueo" }
            blockTarget(config, mode)
            return
        }

        // Debounce/cooldown global (solo WAIT): ignora cualquier intento de disparo dentro de la
        // ventana de enfriamiento posterior a una activación, neutralizando los falsos positivos
        // por ráfagas de eventos. El estado permanece IDLE para no bloquear un gate legítimo
        // posterior. (El límite de uso continuo dispara RE_ENTRY muy por encima de esta ventana.)
        synchronized(lock) {
            val now = clock.now()
            if (now - lastGateTriggerMillis < GATE_COOLDOWN_MILLIS) {
                tracer.trace {
                    "gate ${config.catalogKey} $mode DESCARTADO por cooldown " +
                        "(${now - lastGateTriggerMillis}ms < ${GATE_COOLDOWN_MILLIS}ms) estado sin cambios"
                }
                return
            }
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
        tracer.trace {
            "transicion ${config.catalogKey} -> GATING | $mode ${seconds}s (pantalla de friccion)"
        }

        scope.launch {
            val now = clock.now()
            // INITIAL inicia una nueva sesión (nueva apertura); RE_ENTRY continúa la actual.
            if (mode == GateMode.INITIAL) {
                val openId = sessions.openSessionId(config.catalogKey)
                val sessionId = openId ?: sessions.startSession(config.catalogKey, config.packageName, now)
                synchronized(lock) { runtime.sessionId = sessionId }
                sessions.incrementInterruptions(sessionId)
            } else {
                runtime.sessionId?.let { sessions.incrementInterruptions(it) }
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
        effects.launchFriction()
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
        tracer.trace {
            "transicion ${config.catalogKey} -> ALLOWED | sin friccion (pausa global, $mode)"
        }

        if (mode == GateMode.INITIAL) {
            scope.launch {
                val now = clock.now()
                val openId = sessions.openSessionId(config.catalogKey)
                val sessionId = openId ?: sessions.startSession(config.catalogKey, config.packageName, now)
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
                clock.now() - suspendedAt > runtime.suspendGraceMillis
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
            tracer.trace { "reingreso ${config.catalogKey} | gracia VENCIDA -> cierra y gate INITIAL" }
            closeTarget(config.catalogKey)
            triggerGate(config, GateMode.INITIAL)
        } else {
            tracer.trace { "reingreso ${config.catalogKey} | en gracia -> reanuda sin friccion" }
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
        tracer.trace {
            val graceLabel = if (grace == NO_EXPIRY) "sin expiracion" else "${grace}ms"
            "suspension $catalogKey | dejoPrimerPlano=$foregroundLeft gracia=$graceLabel -> $action"
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
                    runtime.suspendedAtMillis = clock.now()
                    runtime.suspendGraceMillis = grace
                }
            }
            SuspendAction.TIGHTEN -> {
                // Navegación interna -> segundo plano: endurecer y detener el seguimiento del límite.
                if (activeCatalogKey == catalogKey) activeCatalogKey = null
                flushActiveTime(catalogKey)
                synchronized(lock) {
                    val runtime = runtimeFor(catalogKey)
                    runtime.suspendedAtMillis = clock.now()
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
        val now = clock.now()
        val expired = synchronized(lock) {
            runtimes.filter { (_, runtime) ->
                runtime.suspendedAtMillis != 0L &&
                    now - runtime.suspendedAtMillis > runtime.suspendGraceMillis
            }.keys.toList()
        }
        if (expired.isNotEmpty()) tracer.trace { "barrido: gracia vencida -> cierra $expired" }
        expired.forEach { closeTarget(it) }
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
            lastBlockHomeMillis = clock.now()
        }
        activeCatalogKey = null
        tracer.trace { "transicion ${config.catalogKey} -> BLOCKED | $mode -> HOME" }

        scope.launch {
            val now = clock.now()
            if (mode == GateMode.INITIAL) {
                val openId = sessions.openSessionId(config.catalogKey)
                val sessionId = openId
                    ?: sessions.startSession(config.catalogKey, config.packageName, now)
                sessions.incrementInterruptions(sessionId)
                sessions.endSession(sessionId, now)
            } else {
                runtime.sessionId?.let {
                    sessions.incrementInterruptions(it)
                    sessions.endSession(it, now)
                }
            }
            synchronized(lock) { runtime.sessionId = null }
        }

        effects.goHome()
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
        if (pauseController.isPaused()) {
            tracer.trace { "reimposicion de bloqueo omitida | pausa global" }
            return
        }

        val shouldPress = synchronized(lock) {
            val now = clock.now()
            if (now - lastBlockHomeMillis < BLOCK_REPRESS_THROTTLE_MILLIS) {
                false
            } else {
                lastBlockHomeMillis = now
                true
            }
        }
        tracer.trace { "reimposicion de bloqueo | HOME=$shouldPress" }
        if (shouldPress) effects.goHome()
    }

    // --- Pausa global ---

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
        tracer.trace { "pausa iniciada | cierra bloqueados=$blocked" }
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
        tracer.trace { "pausa terminada | reevalua=$allowedKeys primerPlano=$currentForegroundPackage" }
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

    // --- Sesión y cierre ---

    private fun flushActiveTime(catalogKey: String) {
        val runtime = runtimeFor(catalogKey)
        val (sessionId, delta) = synchronized(lock) {
            val id = runtime.sessionId
            val d = runtime.unflushedActiveMillis
            runtime.unflushedActiveMillis = 0L
            id to d
        }
        if (sessionId != null && delta > 0) {
            scope.launch { sessions.addActiveTime(sessionId, delta) }
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
        tracer.trace { "transicion $catalogKey -> IDLE | cierre (sesion=$sessionId)" }
        if (sessionId != null) {
            scope.launch { sessions.endSession(sessionId, clock.now()) }
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
    private fun isDeviceActive(): Boolean = deviceState.isActive()

    companion object {
        /** Periodo del ticker de uso. El bucle vive en el adaptador; el cuerpo, en [onTick]. */
        const val TICK_MILLIS = 1000L

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
