package com.rusure.app.domain.model

/**
 * Tipo de objetivo configurable en RuSure.
 *
 * - [APP_GLOBAL]: toda la app dispara fricción al abrirse (ej. TikTok).
 * - [SECTION]: solo una sección concreta dentro de una app dispara fricción
 *   (ej. Reels en Instagram o Shorts en YouTube), permitiendo navegar el resto.
 */
enum class TargetType {
    APP_GLOBAL,
    SECTION
}
