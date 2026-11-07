package es.citaasistente.app.net

import es.citaasistente.app.data.ProxyEntry
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Prueba REAL de un proxy: abre una conexión HTTPS a través de él (con usuario y contraseña)
 * y lee qué IP y país ve internet. Así se detecta: proxy caído, clave mala o IP que no es de España.
 */
object ProxyTester {

    data class Result(
        val ok: Boolean,
        val ip: String = "",
        val country: String = "",
        val ms: Long = 0L,
        val detail: String = ""
    ) {
        val isSpain: Boolean get() = country.equals("ES", ignoreCase = true)

        /** 1 = funciona (España o país desconocido) · 2 = falla · 3 = funciona pero la IP NO es de España. */
        val status: Int
            get() = when {
                !ok -> 2
                country.isNotBlank() && !isSpain -> 3
                else -> 1
            }

        fun summary(): String =
            if (!ok) detail
            else "IP $ip · ${country.ifBlank { "país desconocido" }} · $ms ms"
    }

    private val ENDPOINTS = listOf(
        "https://ipwho.is/",
        "https://ipapi.co/json/",
        "https://api.ipify.org?format=json"
    )

    /** Se llama SIEMPRE fuera del hilo principal. */
    fun test(p: ProxyEntry, timeoutMs: Int = 10_000): Result {
        ProxyAuth.register(p)

        val route = ProxySession.javaProxy(p)
        val started = System.currentTimeMillis()
        var last = "Sin respuesta del proxy"

        for (endpoint in ENDPOINTS) {
            var conn: HttpURLConnection? = null

            try {
                conn = URL(endpoint).openConnection(route) as HttpURLConnection
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) CitaAsistente")

                val code = conn.responseCode

                if (code == 407) {
                    return Result(false, detail = "El proxy rechazó el usuario o la contraseña (407)")
                }

                if (code !in 200..299) {
                    last = "El servicio de prueba respondió HTTP $code"
                    continue
                }

                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = parse(text)

                if (parsed != null) {
                    return Result(
                        ok = true,
                        ip = parsed.first,
                        country = parsed.second,
                        ms = System.currentTimeMillis() - started
                    )
                }

                last = "Respuesta inesperada a través del proxy"
            } catch (e: IOException) {
                last = describe(e)

                // Si el propio proxy no responde, no tiene sentido probar los demás servicios.
                if (e is ConnectException || e is UnknownHostException) break
            } catch (e: Exception) {
                last = "${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}"
            } finally {
                conn?.disconnect()
            }
        }

        return Result(false, detail = last)
    }

    /** Devuelve (ip, códigoPaís) o null si la respuesta no es válida. Soporta ipwho.is, ipapi.co e ipify. */
    fun parse(text: String): Pair<String, String>? {
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }

        if (o.has("success") && !o.optBoolean("success", true)) return null
        if (o.optBoolean("error", false)) return null

        val ip = o.optString("ip").ifBlank { o.optString("query") }.trim()
        if (ip.isBlank()) return null

        val country = o.optString("country_code")
            .ifBlank { o.optString("countryCode") }
            .trim()
            .uppercase()

        return Pair(ip, country)
    }

    private fun describe(e: IOException): String {
        val m = e.message.orEmpty()

        return when {
            m.contains("407") || m.contains("authenticat", ignoreCase = true) ->
                "El proxy rechazó el usuario o la contraseña"
            e is ConnectException ->
                "El proxy no acepta conexiones (puerto cerrado o bloqueado)"
            e is UnknownHostException ->
                "No se encuentra el servidor del proxy (revisa el host)"
            e is SocketTimeoutException ->
                "El proxy no respondió a tiempo"
            e is SSLException ->
                "Error TLS a través del proxy"
            else ->
                "${e.javaClass.simpleName}: ${m.take(80)}"
        }
    }
}
