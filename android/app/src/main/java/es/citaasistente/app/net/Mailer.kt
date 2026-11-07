package es.citaasistente.app.net

import android.os.Handler
import android.os.Looper
import es.citaasistente.app.data.ProxyEntry
import es.citaasistente.app.data.Settings
import java.util.Properties
import java.util.concurrent.Executors
import javax.mail.AuthenticationFailedException
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Envío por SMTP con TLS obligatorio (587 STARTTLS o 465 SSL). Usa una contraseña de aplicación, no la principal.
 *
 * Si hay un proxy activo (cola en marcha), la conexión SMTP sale por ese proxy HTTP (túnel CONNECT).
 * Muchos proxies solo permiten el puerto 443: si el proxy no deja pasar el correo, se reintenta una vez
 * por la conexión normal para no perder el informe. Un error de usuario/contraseña de correo NO se reintenta.
 */
object Mailer {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun send(s: Settings, subject: String, body: String, onDone: ((Boolean, String) -> Unit)? = null) {
        val px = ProxySession.active()

        io.execute {
            var ok = false
            var msg: String
            try {
                val via = deliverWithFallback(s, subject, body, px)
                ok = true
                msg = "Enviado a ${s.mailTo.trim()}$via"
            } catch (e: Exception) {
                msg = "Error de correo: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}"
            }
            if (onDone != null) main.post { onDone(ok, msg) }
        }
    }

    private fun deliverWithFallback(s: Settings, subject: String, body: String, px: ProxyEntry?): String {
        if (px == null) {
            deliver(s, subject, body, null)
            return ""
        }

        try {
            deliver(s, subject, body, px)
            return " (vía proxy ${px.label()})"
        } catch (e: AuthenticationFailedException) {
            throw e
        } catch (e: Exception) {
            deliver(s, subject, body, null)
            return " (sin proxy: el proxy no dejó pasar el correo)"
        }
    }

    private fun deliver(s: Settings, subject: String, body: String, px: ProxyEntry?) {
        val props = Properties().apply {
            put("mail.smtp.host", s.smtpHost.trim())
            put("mail.smtp.port", s.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "20000")
            put("mail.smtp.timeout", "20000")
            put("mail.smtp.writetimeout", "20000")
            if (s.smtpPort == 465) put("mail.smtp.ssl.enable", "true")
            else { put("mail.smtp.starttls.enable", "true"); put("mail.smtp.starttls.required", "true") }
            put("mail.smtp.ssl.checkserveridentity", "true")

            if (px != null) {
                put("mail.smtp.proxy.host", px.host)
                put("mail.smtp.proxy.port", px.port.toString())
                if (px.user.isNotBlank()) {
                    put("mail.smtp.proxy.user", px.user)
                    put("mail.smtp.proxy.password", px.pass)
                }
            }
        }
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(s.smtpUser.trim(), s.smtpPass)
        })
        val m = MimeMessage(session)
        m.setFrom(InternetAddress(s.smtpUser.trim()))
        m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(s.mailTo.trim()))
        m.setSubject(subject, "UTF-8")
        m.setText(body, "UTF-8")
        Transport.send(m)
    }
}
