package com.rusure.app.ui.settings

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.rusure.app.service.RuSureAccessibilityService

/** Comprueba si el servicio de accesibilidad de RuSure está habilitado por el usuario. */
object AccessibilityStatus {

    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, RuSureAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServices)
        while (colonSplitter.hasNext()) {
            val component = colonSplitter.next()
            if (component.equals(expected, ignoreCase = true)) return true
        }
        return false
    }
}
