package es.citaasistente.app.net

import android.os.Handler
import android.os.Looper
import es.citaasistente.app.data.ProxyEntry
import es.citaasistente.app.data.Settings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Envío de mensajes a tu bot de Telegram (API oficial, HTTPS). El token vive cifrado en los ajustes.
 *
 * Si hay un proxy activo (cola en marcha), el mensaje sale por ese proxy. El proxy se fija en el
 * momento de llamar a send(), así los avisos finales siguen saliendo por el proxy aunque la cola
 * termine justo después. Si el proxy falla, se reintenta una vez por la conexión normal para no perder el aviso.
 */
object Telegram {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun send(s: Settings, text: String, onDone: ((Boolean, String) -> Unit)? = null) {
        val px = ProxySession.active()

        io.execute {
            var ok = false
            var msg: String
            try {
                val r = postWithFallback(s, text, px)
                ok = r.first
                msg = r.second
            } catch (e: Exception) {
                msg = "Sin conexión o datos inválidos: ${e.javaClass.simpleName}"
            }
            if (onDone != null) main.post { onDone(ok, msg) }
        }
    }

    private fun postWithFallback(s: Settings, text: String, px: ProxyEntry?): Pair<Boolean, String> {
        if (px == null) return post(s, text, null)

        return try {
            val r = post(s, text, px)
            Pair(r.first, r.second + " (vía proxy)")
        } catch (e: IOException) {
            val r = post(s, text, null)
            Pair(r.first, r.second + " (sin proxy: el proxy falló)")
        }
    }

    /** Lanza IOException si no hay conexión; devuelve (éxito, mensaje) si Telegram responde. */
    private fun post(s: Settings, text: String, px: ProxyEntry?): Pair<Boolean, String> {
        val body = "chat_id=" + URLEncoder.encode(s.telegramChatId.trim(), "UTF-8") +
            "&text=" + URLEncoder.encode(text.take(3900), "UTF-8") + "&disable_web_page_preview=true"

        val url = URL("https://api.telegram.org/bot${s.telegramToken.trim()}/sendMessage")

        val conn = if (px != null) {
            ProxyAuth.register(px)
            url.openConnection(ProxySession.javaProxy(px)) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }

        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val ok = code in 200..299
            val msg = if (ok) "Enviado" else "Error $code: " +
                Regex("\"description\"\\s*:\\s*\"([^\"]+)\"").find(resp)?.groupValues?.get(1).orEmpty()

            Pair(ok, msg)
        } finally {
            conn.disconnect()
        }
    }
}
