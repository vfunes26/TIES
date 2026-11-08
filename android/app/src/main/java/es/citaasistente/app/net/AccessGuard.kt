package es.citaasistente.app.net

import android.content.Context
import es.citaasistente.app.data.AccessPrefs
import es.citaasistente.app.data.Role
import es.citaasistente.app.data.Session
import es.citaasistente.app.data.Store

/**
 * Vigilante del acceso del Usuario UI/UX: cada 30 min le pregunta a Google si la clave de aplicación
 * sigue existiendo. Si Google la rechaza (revocada), cierra la sesión y la app vuelve a pedir correo y clave.
 * Sin internet NO cierra la sesión, salvo que pasen más de 24 h sin poder verificar.
 * El master no se ve afectado.
 */
object AccessGuard {

    private const val CHECK_MS = 30L * 60L * 1000L
    private const val GRACE_MS = 24L * 60L * 60L * 1000L

    /** Sin red. Si hay una sesión de Usuario UI/UX sin credenciales de Google (versión anterior), la cierra. */
    fun localCheck(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val role = Session.role(app) ?: return false
        if (role != Role.USER) return true

        val ap = AccessPrefs.get(app)
        if (ap.email.isBlank() || ap.key.isBlank()) {
            Session.logout(app)
            return false
        }
        return true
    }

    /** Fuera del hilo principal. Devuelve false si la sesión quedó cerrada. */
    @Synchronized
    fun checkIfDue(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val role = Session.role(app) ?: return false
        if (role != Role.USER) return true

        val ap = AccessPrefs.get(app)
        if (ap.email.isBlank() || ap.key.isBlank()) {
            Session.logout(app)
            return false
        }

        val now = System.currentTimeMillis()
        if (now - ap.lastCheckAt < CHECK_MS) return true

        val r = GoogleAuth.verify(ap.email, ap.key)

        return when (r.verdict) {
            GoogleAuth.Verdict.OK -> {
                ap.lastCheckAt = now
                ap.lastOkAt = now
                true
            }
            GoogleAuth.Verdict.REJECTED -> {
                revoke(app, ap, now, "Google ya no acepta la clave de aplicación.")
                false
            }
            GoogleAuth.Verdict.OFFLINE -> {
                // No se actualiza lastCheckAt: se reintenta en el siguiente ciclo.
                if (now - ap.lastOkAt > GRACE_MS) {
                    revoke(app, ap, now, "Pasaron más de 24 h sin poder verificar la clave con Google.")
                    false
                } else {
                    true
                }
            }
        }
    }

    private fun revoke(app: Context, ap: AccessPrefs, now: Long, reason: String) {
        val email = ap.email
        ap.key = ""
        ap.lastCheckAt = now
        Session.logout(app)

        val s = Store.get(app).settings
        if (s.telegramReady()) {
            Telegram.send(
                s,
                "🔒 ACCESO CERRADO\n👤 Usuario UI/UX ($email)\n$reason\nSe cerró la sesión en ese teléfono."
            )
        }
    }
}
