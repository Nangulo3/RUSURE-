package com.rusure.app.domain.engine

/**
 * Costuras del motor de fricción: las tres dependencias que el motor necesita del mundo exterior y
 * que no se pueden observar desde una prueba JVM (el reloj, las ventanas del sistema y si el usuario
 * está usando el teléfono).
 *
 * Existen para que la máquina de estados sea verificable sin dispositivo (ver `docs/DECISIONS.md`
 * D-010). El servicio provee las implementaciones reales; los tests, dobles con valores controlados.
 *
 * Son `fun interface` a propósito: en producción se construyen con una lambda sobre la API de
 * Android, y en los tests con un doble que además deja inspeccionar lo que se le preguntó.
 */

/**
 * Reloj del motor. Todas las marcas de tiempo y todos los plazos (confirmación de salida, gracia de
 * segundo plano, cooldown, límite de uso continuo) se miden con este reloj y con ninguno más.
 *
 * La implementación real es `System.currentTimeMillis()`, es decir, **tiempo de pared**: no es
 * monótono y puede saltar si cambia la hora del sistema (ver `DEEP_INIT_REPORT.md` E17). La costura
 * no cambia eso; solo lo hace sustituible.
 */
fun interface Clock {
    /** Milisegundos desde epoch. */
    fun now(): Long
}

/**
 * Pregunta al sistema si un paquete conserva alguna **ventana de aplicación** visible. Es la señal
 * con la que la confirmación diferida de salida (D-005) distingue "hay una ventana ajena superpuesta"
 * (teclado, persiana, diálogo del sistema) de "el usuario cambió de aplicación".
 */
fun interface WindowProbe {
    /** `true` si [packageName] tiene una ventana de tipo aplicación en pantalla. */
    fun hasApplicationWindow(packageName: String): Boolean
}

/**
 * Indica si el usuario está realmente usando el dispositivo: pantalla encendida y no bloqueada.
 * Apagar la pantalla o bloquear el teléfono sin salir de la app no cuenta hoy como salida ni avanza
 * el límite de uso continuo (comportamiento actual S8; D-011 lo cambia, aún sin implementar).
 */
fun interface DeviceState {
    /** `true` con la pantalla encendida y sin keyguard. */
    fun isActive(): Boolean
}
