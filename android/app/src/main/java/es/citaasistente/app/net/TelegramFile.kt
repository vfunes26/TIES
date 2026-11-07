package es.citaasistente.app.net

import es.citaasistente.app.data.ProxyEntry
import es.citaasistente.app.data.Settings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Envía un ARCHIVO a tu chat de Telegram (sendDocument). Se usa para el respaldo y el historial.
 * Siempre se llama fuera del hilo principal. Si hay un proxy activo sale por él; si el proxy falla,
 * se reintenta una vez por la conexión normal.
 */
object TelegramFile {

    fun sendDocument(
        s: Settings,
        bytes: ByteArray,
        fileName: String,
        caption: String
    ): Pair<Boolean, String> {
        val px = ProxySession.active()
        if (px == null) return post(s, bytes, fileName, caption, null)

        return try {
            val r = post(s, bytes, fileName, caption, px)
            Pair(r.first, r.second + " (vía proxy)")
        } catch (e: IOException) {
            val r = post(s, bytes, fileName, caption, null)
            Pair(r.first, r.second + " (sin proxy: el proxy falló)")
        }
    }

    private fun post(
        s: Settings,
        bytes: ByteArray,
        fileName: String,
        caption: String,
        px: ProxyEntry?
    ): Pair<Boolean, String> {
        val boundary = "----TIES" + System.currentTimeMillis()
        val url = URL("https://api.telegram.org/bot${s.telegramToken.trim()}/sendDocument")

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

                field("chat_id", s.telegramChatId.trim())
                if (caption.isNotBlank()) field("caption", caption.take(900))

                out.write(
                    ("--$boundary\r\nContent-Disposition: form-data; name=\"document\"; " +
                        "filename=\"$fileName\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                        .toByteArray(Charsets.UTF_8)
                )
                out.write(bytes)
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }

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
