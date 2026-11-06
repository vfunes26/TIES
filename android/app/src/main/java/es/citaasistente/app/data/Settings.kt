package es.citaasistente.app.data

import org.json.JSONObject

/** Ajustes de comportamiento, avisos (Telegram/correo) y procesamiento de la cola. Se guardan cifrados. */
data class Settings(
    // --- Alertas ---
    val sourceAlertsApp: Boolean = true,    // interpretar notificaciones de Extranjería Alerts (opcional)
    val sourceTelegram: Boolean = true,     // interpretar mensajes del bot en Telegram (etiqueta CITA_DISPONIBLE)
    val strictMatch: Boolean = false,
    val autoLaunch: Boolean = true,         // arrancar solo el autorrelleno al detectar una alerta que coincide
    val screenOnOnAlert: Boolean = true,    // encender pantalla al arrancar por alerta
    val silenceHours: Int = 0,
    // --- Procesamiento ---
    val autoConsent: Boolean = false,       // marcar la casilla de conformidad final
    val retryMinutes: Int = 5,              // pausa entre pasadas completas si no hay citas (mín. 3)
    val batchSize: Int = 5,                 // solicitudes por lote
    val batchPauseMinutes: Int = 10,        // pausa entre lotes
    val minGapSeconds: Int = 45,            // separación mínima entre el inicio de dos solicitudes (mín. 30)
    val captchaTimeoutMinutes: Int = 5,     // tiempo para resolver captcha/SMS antes de pasar a la siguiente
    // --- Telegram ---
    val telegramEnabled: Boolean = false,
    val telegramToken: String = "",
    val telegramChatId: String = "",
    val tgAlerts: Boolean = true,           // alertas de cita detectada
    val tgProcess: Boolean = true,          // inicio / captcha pendiente / sin citas
    val tgResults: Boolean = true,          // CON CITA / fallida
    // --- Correo (SMTP) ---
    val mailEnabled: Boolean = false,
    val smtpHost: String = "smtp.gmail.com",
    val smtpPort: Int = 587,
    val smtpUser: String = "",
    val smtpPass: String = "",
    val mailTo: String = "",
    val mailOnRegistered: Boolean = true,
    val mailOnResult: Boolean = true,
) {
    fun telegramReady() = telegramEnabled && telegramToken.isNotBlank() && telegramChatId.isNotBlank()
    fun mailReady() = mailEnabled && smtpHost.isNotBlank() && smtpUser.isNotBlank() && smtpPass.isNotBlank() && mailTo.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("sourceAlertsApp", sourceAlertsApp).put("sourceTelegram", sourceTelegram).put("strictMatch", strictMatch)
        .put("autoLaunch", autoLaunch).put("screenOnOnAlert", screenOnOnAlert).put("silenceHours", silenceHours)
        .put("autoConsent", autoConsent).put("retryMinutes", retryMinutes).put("batchSize", batchSize)
        .put("batchPauseMinutes", batchPauseMinutes).put("minGapSeconds", minGapSeconds)
        .put("captchaTimeoutMinutes", captchaTimeoutMinutes)
        .put("telegramEnabled", telegramEnabled).put("telegramToken", telegramToken).put("telegramChatId", telegramChatId)
        .put("tgAlerts", tgAlerts).put("tgProcess", tgProcess).put("tgResults", tgResults)
        .put("mailEnabled", mailEnabled).put("smtpHost", smtpHost).put("smtpPort", smtpPort)
        .put("smtpUser", smtpUser).put("smtpPass", smtpPass).put("mailTo", mailTo)
        .put("mailOnRegistered", mailOnRegistered).put("mailOnResult", mailOnResult)

    companion object {
        fun fromJson(o: JSONObject): Settings {
            val d = Settings()
            return Settings(
                sourceAlertsApp = o.optBoolean("sourceAlertsApp", d.sourceAlertsApp),
                sourceTelegram = o.optBoolean("sourceTelegram", d.sourceTelegram),
                strictMatch = o.optBoolean("strictMatch", d.strictMatch),
                autoLaunch = o.optBoolean("autoLaunch", d.autoLaunch),
                screenOnOnAlert = o.optBoolean("screenOnOnAlert", d.screenOnOnAlert),
                silenceHours = o.optInt("silenceHours", d.silenceHours).coerceAtLeast(0),
                autoConsent = o.optBoolean("autoConsent", d.autoConsent),
                retryMinutes = o.optInt("retryMinutes", d.retryMinutes).coerceAtLeast(3),
                batchSize = o.optInt("batchSize", d.batchSize).coerceIn(1, 50),
                batchPauseMinutes = o.optInt("batchPauseMinutes", d.batchPauseMinutes).coerceIn(0, 240),
                minGapSeconds = o.optInt("minGapSeconds", d.minGapSeconds).coerceAtLeast(30),
                captchaTimeoutMinutes = o.optInt("captchaTimeoutMinutes", d.captchaTimeoutMinutes).coerceIn(2, 30),
                telegramEnabled = o.optBoolean("telegramEnabled", d.telegramEnabled),
                telegramToken = o.optString("telegramToken"), telegramChatId = o.optString("telegramChatId"),
                tgAlerts = o.optBoolean("tgAlerts", d.tgAlerts), tgProcess = o.optBoolean("tgProcess", d.tgProcess),
                tgResults = o.optBoolean("tgResults", d.tgResults),
                mailEnabled = o.optBoolean("mailEnabled", d.mailEnabled),
                smtpHost = o.optString("smtpHost", d.smtpHost), smtpPort = o.optInt("smtpPort", d.smtpPort),
                smtpUser = o.optString("smtpUser"), smtpPass = o.optString("smtpPass"), mailTo = o.optString("mailTo"),
                mailOnRegistered = o.optBoolean("mailOnRegistered", d.mailOnRegistered),
                mailOnResult = o.optBoolean("mailOnResult", d.mailOnResult),
            )
        }
    }
}
