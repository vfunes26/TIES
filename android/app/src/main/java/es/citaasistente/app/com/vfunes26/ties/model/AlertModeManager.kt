// android/app/src/main/java/com/vfunes26/ties/model/AlertModeManager.kt
package com.vfunes26.ties.model

import android.content.Context
import android.content.SharedPreferences

class AlertModeManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("cita_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALERTS_ENABLED = "alert_detection_enabled"
        private const val KEY_AUTOFILL_ENABLED = "auto_fill_enabled"
    }

    var isAlertDetectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERTS_ENABLED, true) // ON por defecto
        set(value) = prefs.edit().putBoolean(KEY_ALERTS_ENABLED, value).apply()

    var isAutoFillEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTOFILL_ENABLED, false) // OFF por defecto (seguridad)
        set(value) = prefs.edit().putBoolean(KEY_AUTOFILL_ENABLED, value).apply()

    /**
     * Alterna el estado y devuelve el nuevo valor
     */
    fun toggleAlerts(): Boolean {
        isAlertDetectionEnabled = !isAlertDetectionEnabled
        return isAlertDetectionEnabled
    }

    fun toggleAutoFill(): Boolean {
        isAutoFillEnabled = !isAutoFillEnabled
        return isAutoFillEnabled
    }
}