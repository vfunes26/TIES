package es.citaasistente.app.net

import android.os.Handler
import android.os.Looper
import es.citaasistente.app.data.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Envío de mensajes a tu bot de Telegram (API oficial, HTTPS). El token vive cifrado en los ajustes. */
object Telegram {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun send(s: Settings, text: String, onDone: ((Boolean, String) -> Unit)? = null) {
        io.execute {
            var ok = false
            var msg: String
            try {
                val body = "chat_id=" + URLEncoder.encode(s.telegramChatId.trim(), "UTF-8") +
                    "&text=" + URLEncoder.encode(text.take(3900), "UTF-8") + "&disable_web_page_preview=true"
                val conn = URL("https://api.telegram.org/bot${s.telegramToken.trim()}/sendMessage").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000; conn.readTimeout = 15000; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                ok = code in 200..299
                msg = if (ok) "Enviado" else "Error $code: " + Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(resp)?.groupValues?.get(1).orEmpty()
                conn.disconnect()
            } catch (e: Exception) {
                msg = "Sin conexión o datos inválidos: ${e.javaClass.simpleName}"
            }
            if (onDone != null) main.post { onDone(ok, msg) }
        }
    }
}
