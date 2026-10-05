package com.rusure.app.domain.engine

import com.rusure.app.data.local.entity.AppTargetConfig

/**
 * Contratos que [GateEngine] necesita para hablar con el mundo exterior sin depender de Android.
 * Las implementaciones reales viven en `service/`; los tests inyectan dobles.
 *
 * Las otras tres dependencias (reloj, ventanas y estado del dispositivo) están en `Seams.kt`.
 */

/** Resultado de buscar una sección objetivo en el árbol de nodos de la ventana activa. */
sealed interface SectionScan {

    /**
     * El árbol no estaba disponible (`rootInActiveWindow` nulo, de forma transitoria). **No se
     * concluye nada**: en particular, no se interpreta como que la sección dejó de verse.
     */
    data object Unavailable : SectionScan

    /** El árbol se pudo leer y ninguna sección objetivo del paquete está visible. */
    data object NotDetected : SectionScan

    /** El árbol se pudo leer y esta sección objetivo está visible. */
    data class Detected(val config: AppTargetConfig) : SectionScan
}

/** Detección de secciones (Reels/Shorts) sobre la ventana activa. */
fun interface SectionProbe {
    fun scan(packageName: String, enabledTargets: List<AppTargetConfig>): SectionScan
}

/** Detección de apps globales (por nombre de paquete). */
fun interface AppTargetProbe {
    fun detect(packageName: String, enabledTargets: List<AppTargetConfig>): AppTargetConfig?
}

/**
 * Operaciones de sesión que el motor necesita de la persistencia. Es el subconjunto de
 * [com.rusure.app.data.repository.RuSureRepository] que el motor usa, y nada más.
 */
interface SessionStore {

    /** Id de la sesión abierta del objetivo (sin `endEpochMillis`), o null si no hay ninguna. */
    suspend fun openSessionId(catalogKey: String): Long?

    /** Abre una sesión nueva y devuelve su id. */
    suspend fun startSession(catalogKey: String, packageName: String, nowMillis: Long): Long

    suspend fun incrementInterruptions(sessionId: Long)

    suspend fun incrementCancelled(sessionId: Long)

    suspend fun addActiveTime(sessionId: Long, deltaMillis: Long)

    suspend fun endSession(sessionId: Long, nowMillis: Long)
}

/** Las dos únicas acciones del motor sobre el sistema. */
interface EngineEffects {

    /** Lanza la pantalla de fricción sobre la app objetivo. */
    fun launchFriction()

    /** Devuelve al usuario al inicio (`GLOBAL_ACTION_HOME`). */
    fun goHome()
}

/**
 * Traza de diagnóstico del motor. La implementación real decide si emitir (solo en compilaciones
 * depurables) y el mensaje se construye de forma perezosa, así que en release no se evalúa.
 *
 * **Nunca** debe registrar texto ni descripciones de contenido de los nodos.
 */
fun interface Tracer {
    fun trace(message: () -> String)

    companion object {
        /** Tracer que no registra nada: el valor por defecto del motor. */
        val None = Tracer { }
    }
}
