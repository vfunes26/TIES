package es.citaasistente.app.net

import android.content.Context
import es.citaasistente.app.data.Session
import es.citaasistente.app.data.Store
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Si la app se cierra por un error, manda un aviso breve al master (sin datos personales:
 * solo versi贸n, perfil, tipo de error y las l铆neas del c贸digo de la app). Si no hay red en ese momento,
 * queda guardado y se env铆a al abrir la app otra vez.
 */
object CrashReporter {

    private const val PREFS = "citaasistente_crash"
    private const val MIN_GAP_MS = 10L * 60L * 1000L

    @Volatile
    private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true

        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                report(app, thread, error)
            } catch (e: Throwable) {
                // nunca romper el cierre normal
            }
            previous?.uncaughtException(thread, error)
        }

        Thread { sendPending(app) }.start()
    }

    private fun report(app: Context, thread: Thread, error: Throwable) {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong("lastAt", 0L) < MIN_GAP_MS) return

        val frames = error.stackTrace
            .filter { it.className.startsWith("es.citaasistente") }
            .take(6)
            .joinToString("\n") { "  ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

        val text = buildString {
            append("馃挜 FALLO DE LA APP\n")
            append("Versi贸n ${es.citaasistente.app.BuildConfig.VERSION_NAME} 路 ${Session.label(app)}\n")
            append("Hilo: ${thread.name}\n")
            append("Error: ${error.javaClass.simpleName}")
            error.cause?.let { append(" (causa: ${it.javaClass.simpleName})") }
            if (frames.isNotBlank()) append("\n$frames")
        }

        p.edit().putLong("lastAt", now).putString("pending", text).commit()

        val t = Thread { sendPending(app) }
        t.start()
        t.join(4000)
    }

    private fun sendPending(app: Context) {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val text = p.getString("pending", "") ?: ""
        if (text.isBlank()) return

        val s = Store.get(app).settings
        if (!s.telegramReady()) return

        try {
            val body = "chat_id=" + URLEncoder.encode(s.telegramChatId.trim(), "UTF-8") +
                "&text=" + URLEncoder.encode(text.take(3500), "UTF-8")

            val conn = URL("https://api.telegram.org/bot${s.telegramToken.trim()}/sendMessage")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val ok = conn.responseCode in 200..299
            conn.disconnect()

            if (ok) p.edit().remove("pending").commit()
        } catch (e: Exception) {
            // queda pendiente para el pr贸ximo arranque
        }
    }
}
