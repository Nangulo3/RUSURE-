package com.rusure.app.domain.catalog

import com.rusure.app.domain.model.GateAction
import com.rusure.app.domain.model.TargetType

/**
 * Entrada del catálogo curado de objetivos soportados. Contiene los criterios de detección
 * (deliberadamente fuera de la base de datos para poder actualizarlos sin migraciones) y los
 * valores por defecto usados al sembrar la configuración.
 *
 * Detección:
 * - [viewIdMatchers]: resource-ids estables del nodo de la sección (preferente, robusto).
 * - [contentDescMatchers]: textos / content-descriptions con variantes ES/EN como respaldo,
 *   comparados con `contains` case-insensitive.
 */
data class CatalogEntry(
    val catalogKey: String,
    val packageName: String,
    val displayName: String,
    val targetType: TargetType,
    val sectionKey: String? = null,
    val viewIdMatchers: List<String> = emptyList(),
    val contentDescMatchers: List<String> = emptyList(),
    val defaultInitialTimerSeconds: Int,
    val defaultContinuousUsageLimitSeconds: Int,
    val defaultReEntryTimerSeconds: Int,
    val defaultEntryAction: GateAction = GateAction.WAIT
)

/**
 * Catálogo estático de apps/secciones soportadas por RuSure.
 * Para añadir soporte a una nueva app/sección basta con agregar una [CatalogEntry] aquí.
 */
object TargetCatalog {

    const val KEY_INSTAGRAM_REELS = "instagram_reels"
    const val KEY_YOUTUBE_SHORTS = "youtube_shorts"
    const val KEY_TIKTOK_GLOBAL = "tiktok_global"

    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            catalogKey = KEY_INSTAGRAM_REELS,
            packageName = "com.instagram.android",
            displayName = "Instagram · Reels",
            targetType = TargetType.SECTION,
            sectionKey = "reels",
            // Aislamiento ESTRICTO: solo los contenedores del VISOR de Reels a pantalla completa.
            // Deliberadamente NO se incluye el botón de la pestaña (clips_tab/reels_tab) porque
            // existe también en el feed principal, ni la rejilla de Reels del perfil. Estos ids
            // solo están presentes (y visibles) dentro del reproductor de Reels, de modo que la
            // fricción NUNCA salta en Feed, Comentarios, Perfil o Historias.
            viewIdMatchers = listOf(
                "com.instagram.android:id/clips_viewer_view_pager",
                "com.instagram.android:id/clips_viewer_root",
                "com.instagram.android:id/clips_video_container"
            ),
            // Sin respaldo por content-description: el texto "Reels" aparece como pestaña
            // seleccionada también en el Perfil (rejilla de reels) y dispararía falsos positivos.
            // La detección queda anclada exclusivamente a los ViewNodes del visor de Reels.
            contentDescMatchers = emptyList(),
            defaultInitialTimerSeconds = 10,
            defaultContinuousUsageLimitSeconds = 300,
            defaultReEntryTimerSeconds = 15
        ),
        CatalogEntry(
            catalogKey = KEY_YOUTUBE_SHORTS,
            packageName = "com.google.android.youtube",
            displayName = "YouTube · Shorts",
            targetType = TargetType.SECTION,
            sectionKey = "shorts",
            viewIdMatchers = listOf(
                "com.google.android.youtube:id/reel_recycler",
                "com.google.android.youtube:id/shorts_container",
                "com.google.android.youtube:id/reel_player_page_container"
            ),
            contentDescMatchers = listOf("Shorts", "Short"),
            defaultInitialTimerSeconds = 10,
            defaultContinuousUsageLimitSeconds = 300,
            defaultReEntryTimerSeconds = 15
        ),
        CatalogEntry(
            catalogKey = KEY_TIKTOK_GLOBAL,
            packageName = "com.zhiliaoapp.musically",
            displayName = "TikTok",
            targetType = TargetType.APP_GLOBAL,
            sectionKey = null,
            viewIdMatchers = emptyList(),
            contentDescMatchers = emptyList(),
            defaultInitialTimerSeconds = 10,
            defaultContinuousUsageLimitSeconds = 300,
            defaultReEntryTimerSeconds = 15
        )
    )

    private val byKey: Map<String, CatalogEntry> = entries.associateBy { it.catalogKey }

    fun entryFor(catalogKey: String): CatalogEntry? = byKey[catalogKey]

    /** Secciones del catálogo que pertenecen a un package dado. */
    fun sectionsForPackage(packageName: String): List<CatalogEntry> =
        entries.filter { it.packageName == packageName && it.targetType == TargetType.SECTION }
}
