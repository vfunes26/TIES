package es.citaasistente.app.net

import es.citaasistente.app.data.ProxyEntry
import es.citaasistente.app.data.Settings
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Envía una FOTO a un chat de Telegram (sendPhoto). Fuera del hilo principal. Usa el proxy activo y, si falla, reintenta directo. */
object TelegramPhoto {

    fun send(s: Settings, chatId: String, file: File, caption: String): Pair<Boolean, String> {
        val bytes = file.readBytes()
        val px = ProxySession.active()
        if (px == null) return post(s, chatId, bytes, caption, null)

        return try {
            post(s, chatId, bytes, caption, px)
        } catch (e: IOException) {
            post(s, chatId, bytes, caption, null)
        }
    }

    private fun post(s: Settings, chatId: String, bytes: ByteArray, caption: String, px: ProxyEntry?): Pair<Boolean, String> {
        val boundary = "----TIES" + System.currentTimeMillis()
        val url = URL("https://api.telegram.org/bot${s.telegramToken.trim()}/sendPhoto")

        val conn = if (px != null) {
            ProxyAuth.register(px)
            url.openConnection(ProxySession.javaProxy(px)) as HttpURLConnection
        } else {
            url.openConnection() as HttpURLConnection
        }

        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            conn.outputStream.use { out ->
                fun field(name: String, value: String) {
                    out.write(
                        ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
                            .toByteArray(Charsets.UTF_8)
                    )
                }

                field("chat_id", chatId.trim())
                if (caption.isNotBlank()) field("caption", caption.take(900))

                out.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"photo\"; " +
                        "filename=\"captura.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n")
                        .toByteArray(Charsets.UTF_8)
                )
                out.write(bytes)
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            Pair(code in 200..299, if (code in 200..299) "Enviado" else "Error $code")
        } finally {
            conn.disconnect()
        }
    }
}
