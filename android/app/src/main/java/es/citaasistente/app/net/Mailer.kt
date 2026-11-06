package es.citaasistente.app.net

import android.os.Handler
import android.os.Looper
import es.citaasistente.app.data.Settings
import java.util.Properties
import java.util.concurrent.Executors
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/** Envío por SMTP con TLS obligatorio (587 STARTTLS o 465 SSL). Usa una contraseña de aplicación, no la principal. */
object Mailer {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun send(s: Settings, subject: String, body: String, onDone: ((Boolean, String) -> Unit)? = null) {
        io.execute {
            var ok = false
            var msg: String
            try {
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
                ok = true; msg = "Enviado a ${s.mailTo.trim()}"
            } catch (e: Exception) {
                msg = "Error de correo: ${e.javaClass.simpleName} ${e.message.orEmpty().take(120)}"
            }
            if (onDone != null) main.post { onDone(ok, msg) }
        }
    }
}
