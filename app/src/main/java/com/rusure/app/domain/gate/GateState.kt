package com.rusure.app.domain.gate

/**
 * Estado en memoria (en el servicio) del ciclo de fricción de un objetivo.
 *
 * IDLE ──(abre objetivo)──▶ GATING ──(Continuar)──▶ ALLOWED ──(supera límite)──▶ LIMIT_REACHED
 *   ▲                                                  │                                 │
 *   └────────────(sale del objetivo / Leave)──────────┴──────────(gate reingreso)───────┘
 *
 * Evita re-disparar el gate inmediatamente después de que el usuario pulsa "Continuar".
 */
enum class GateState {
    /** Sin fricción activa; el objetivo no está en primer plano o aún no se detectó. */
    IDLE,

    /** Pantalla de fricción mostrándose; esperando decisión del usuario. */
    GATING,

    /** Acceso concedido; contando tiempo de uso continuo. */
    ALLOWED,

    /** Superado el límite de uso continuo; pendiente de disparar gate de reingreso. */
    LIMIT_REACHED,

    /**
     * Objetivo configurado como "Bloquear completamente": el acceso se deniega y se reimpone el
     * regreso al inicio mientras el objetivo siga en primer plano (defiende la pulsación de HOME
     * frente a la animación de arranque de la app). Se resetea a [IDLE] al salir del objetivo.
     */
    BLOCKED
}
