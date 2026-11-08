package es.citaasistente.app.net

import es.citaasistente.app.data.AccessPolicy
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.Session

/**
 * Pregunta a Google si el correo y la clave de aplicación siguen siendo válidos: abre una sesión con
 * smtp.gmail.com (587, TLS) y la cierra. NO envía ningún correo. Siempre fuera del hilo principal.
 */
object GoogleAuth {

    enum class Verdict { OK, REJECTED, OFFLINE }

    data class Check(val verdict: Verdict, val detail: String = "")

    fun verify(email: String, key: String): Check {
        val props = Properties().apply {
            put("mail.smtp.host", "smtp.gmail.com")
            put("mail.smtp.port", "587")
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
            put("mail.smtp.ssl.checkserveridentity", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
        }

        val transport = try {
            Session.getInstance(props).getTransport("smtp")
        } catch (e: Exception) {
            return Check(Verdict.OFFLINE, "No se pudo preparar la conexión")
        }

        return try {
            transport.connect("smtp.gmail.com", 587, email.trim(), AccessPolicy.normalizeKey(key))
            Check(Verdict.OK)
        } catch (e: AuthenticationFailedException) {
            Check(Verdict.REJECTED, "Google rechazó el correo o la clave de aplicación")
        } catch (e: Exception) {
            Check(Verdict.OFFLINE, "Sin conexión con Google (${e.javaClass.simpleName})")
        } finally {
            try {
                transport.close()
            } catch (e: Exception) {
                // nada que hacer
            }
        }
    }
}
