package com.rusure.app.domain.gate

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Puente desacoplado entre el [com.rusure.app.service.RuSureAccessibilityService] (que decide
 * cuándo interrumpir) y la [com.rusure.app.ui.interruption.InterruptionActivity] (que muestra la
 * pantalla y recoge la decisión del usuario). Singleton de proceso provisto por el AppContainer.
 */
class GateCoordinator {

    private val pending = AtomicReference<GateRequest?>(null)

    private val _decisions = MutableSharedFlow<GateDecision>(extraBufferCapacity = 8)

    /** Stream de decisiones del usuario; el servicio lo colecciona. */
    val decisions: SharedFlow<GateDecision> = _decisions.asSharedFlow()

    /** El servicio publica la solicitud justo antes de lanzar la Activity. */
    fun publishRequest(request: GateRequest) {
        pending.set(request)
    }

    /** La Activity consume la solicitud pendiente (una sola vez). */
    fun consumeRequest(): GateRequest? = pending.getAndSet(null)

    /** Permite a la Activity recuperar la solicitud sin limpiarla (ej. recreación de proceso). */
    fun peekRequest(): GateRequest? = pending.get()

    /** La Activity emite la decisión del usuario. */
    fun submitDecision(decision: GateDecision) {
        _decisions.tryEmit(decision)
    }
}
