package com.rusure.app.domain.detection

import android.view.accessibility.AccessibilityNodeInfo
import com.rusure.app.data.local.entity.AppTargetConfig
import com.rusure.app.domain.catalog.TargetCatalog
import com.rusure.app.domain.model.TargetType

/**
 * Resuelve qué objetivo está activo a partir de los eventos de accesibilidad.
 *
 * - Apps globales: se detectan por nombre de paquete.
 * - Secciones (Reels/Shorts): se detectan inspeccionando el árbol de nodos, priorizando
 *   resource-ids estables y, como respaldo, content-descriptions (variantes ES/EN).
 */
class TargetDetector {

    /** Límite de nodos a inspeccionar para no penalizar el rendimiento en árboles grandes. */
    private val maxNodesVisited = 600

    /** Devuelve la config de la app global habilitada que corresponde al paquete, si existe. */
    fun detectAppTarget(
        packageName: String,
        enabledTargets: List<AppTargetConfig>
    ): AppTargetConfig? =
        enabledTargets.firstOrNull {
            it.targetType == TargetType.APP_GLOBAL && it.packageName == packageName
        }

    /**
     * Devuelve la config de sección habilitada que está actualmente visible dentro del árbol,
     * o null si ninguna sección objetivo está activa.
     */
    fun detectSection(
        root: AccessibilityNodeInfo?,
        packageName: String,
        enabledTargets: List<AppTargetConfig>
    ): AppTargetConfig? {
        if (root == null) return null

        val sections = enabledTargets.filter {
            it.targetType == TargetType.SECTION && it.packageName == packageName
        }
        if (sections.isEmpty()) return null

        for (section in sections) {
            val entry = TargetCatalog.entryFor(section.catalogKey) ?: continue
            if (matchesByViewId(root, entry.viewIdMatchers)) return section
        }

        // Respaldo por content-description: solo si la pestaña correspondiente está seleccionada,
        // para evitar falsos positivos con accesos directos no activos.
        val selectedDescriptions = collectSelectedDescriptions(root)
        if (selectedDescriptions.isNotEmpty()) {
            for (section in sections) {
                val entry = TargetCatalog.entryFor(section.catalogKey) ?: continue
                if (entry.contentDescMatchers.any { matcher ->
                        selectedDescriptions.any { it.contains(matcher, ignoreCase = true) }
                    }
                ) {
                    return section
                }
            }
        }

        return null
    }

    private fun matchesByViewId(
        root: AccessibilityNodeInfo,
        viewIds: List<String>
    ): Boolean {
        for (viewId in viewIds) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(viewId)
            } catch (_: Exception) {
                null
            }
            if (!nodes.isNullOrEmpty() && nodes.any { it != null && it.isVisibleToUser }) {
                return true
            }
        }
        return false
    }

    /** Recolecta los textos/descripciones de los nodos actualmente seleccionados y visibles. */
    private fun collectSelectedDescriptions(root: AccessibilityNodeInfo): List<String> {
        val result = ArrayList<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < maxNodesVisited) {
            val node = queue.removeFirst()
            visited++

            if (node.isSelected && node.isVisibleToUser) {
                node.contentDescription?.let { result.add(it.toString()) }
                node.text?.let { result.add(it.toString()) }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
    }
}
