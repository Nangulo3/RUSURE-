package com.rusure.app.ui.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Formatea una duración en milisegundos a un texto compacto en español (ej. "1h 5m", "45s"). */
fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "0s"
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
    return buildString {
        if (hours > 0) append("${hours}h ")
        if (hours > 0 || minutes > 0) append("${minutes}m ")
        append("${seconds}s")
    }.trim()
}

/**
 * Formatea un valor de segundos como minutos enteros (ej. "5 min"). Usado en la configuración de
 * uso continuo, donde NUNCA deben mostrarse segundos: cualquier resto se redondea al minuto más
 * cercano (mínimo 1 minuto).
 */
fun formatMinutes(seconds: Int): String {
    val minutes = ((seconds + 30) / 60).coerceAtLeast(1)
    return "$minutes min"
}

/**
 * Formatea una duración en milisegundos a horas y minutos, sin segundos (ej. "1h 47m", "34m").
 * Pensado para el dashboard, donde el segundo es ruido visual.
 */
fun formatDurationHoursMinutes(millis: Long): String {
    if (millis <= 0L) return "0m"
    val hours = TimeUnit.MILLISECONDS.toHours(millis)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
    return buildString {
        if (hours > 0) append("${hours}h ")
        append("${minutes}m")
    }.trim()
}

/**
 * Formatea una duración en milisegundos como cuenta atrás "m:ss" (ej. "4:05"), redondeando hacia
 * arriba al segundo. Mínimo "0:00" (nunca negativo). Usado en la tarjeta de pausa del dashboard.
 */
fun formatCountdown(millis: Long): String {
    if (millis <= 0L) return "0:00"
    val totalSeconds = (millis + 999L) / 1000L
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** Formatea un timestamp epoch a hora local "HH:mm", o "—" si es nulo. */
fun formatTimestamp(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0L) return "—"
    val formatter = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
    return formatter.format(Date(epochMillis))
}

/**
 * Formatea un timestamp epoch como tiempo relativo compacto en español (ej. "ahora", "hace 5m",
 * "hace 2h", "hace 3d"). Por encima de una semana cae a la fecha absoluta. Devuelve "—" si es nulo.
 */
fun formatRelativeTime(epochMillis: Long?, nowMillis: Long = System.currentTimeMillis()): String {
    if (epochMillis == null || epochMillis <= 0L) return "—"
    val diff = nowMillis - epochMillis
    if (diff < TimeUnit.MINUTES.toMillis(1)) return "ahora"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        minutes < 60 -> "hace ${minutes}m"
        hours < 24 -> "hace ${hours}h"
        days < 7 -> "hace ${days}d"
        else -> formatTimestamp(epochMillis)
    }
}
