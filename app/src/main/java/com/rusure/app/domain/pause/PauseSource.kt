package com.rusure.app.domain.pause

/**
 * Consulta de la pausa global (D-009) **con el reloj que le pasa quien pregunta**.
 *
 * Existe para que [com.rusure.app.domain.engine.GateEngine] mida la pausa con la misma costura
 * [com.rusure.app.domain.engine.Clock] que todo lo demás, en lugar de leer el reloj del sistema por
 * su cuenta: así la pausa es verificable en tests JVM con tiempo virtual (D-010) y no queda una
 * lectura de reloj fuera de las costuras.
 *
 * La implementación real es [PauseController], que además persiste el instante de fin.
 */
fun interface PauseSource {

    /** `true` si, en [now] (epoch millis), la pausa global sigue vigente. */
    fun isPaused(now: Long): Boolean
}
