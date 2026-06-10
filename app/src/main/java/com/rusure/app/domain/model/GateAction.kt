package com.rusure.app.domain.model

/**
 * Acción que ejecuta RuSure cuando se detecta la entrada a un objetivo.
 *
 * - [WAIT]: muestra la pantalla de espera/fricción con cuenta regresiva (comportamiento por
 *   defecto). El usuario puede continuar tras agotar el temporizador.
 * - [BLOCK]: bloquea por completo el acceso. No se muestra cuenta regresiva: el intento se
 *   registra como interrupción y se devuelve al usuario al inicio del sistema.
 */
enum class GateAction {
    WAIT,
    BLOCK
}
