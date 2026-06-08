package com.rusure.app.domain.gate

/** Modo del gate de fricción. */
enum class GateMode {
    /** Primer gate al abrir el objetivo. */
    INITIAL,

    /** Gate forzado tras superar el límite de uso continuo. */
    RE_ENTRY
}

/**
 * Solicitud de fricción que el servicio deposita antes de lanzar [com.rusure.app.ui.interruption.InterruptionActivity].
 * La Activity la consume para configurar su ViewModel.
 */
data class GateRequest(
    val catalogKey: String,
    val displayName: String,
    val mode: GateMode,
    val seconds: Int
)

/** Decisión tomada por el usuario en la pantalla de fricción. */
sealed interface GateDecision {
    val catalogKey: String

    /** El usuario esperó y pulsó "Continuar": se permite el acceso al objetivo. */
    data class Continue(override val catalogKey: String) : GateDecision

    /** El usuario eligió no continuar: se cierra y vuelve al Home. */
    data class Leave(override val catalogKey: String) : GateDecision
}
