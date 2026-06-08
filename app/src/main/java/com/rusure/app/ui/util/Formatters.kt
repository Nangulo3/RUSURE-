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

/** Formatea un timestamp epoch a hora local "HH:mm", o "—" si es nulo. */
fun formatTimestamp(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0L) return "—"
    val formatter = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
    return formatter.format(Date(epochMillis))
}
