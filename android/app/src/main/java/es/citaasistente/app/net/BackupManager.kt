package es.citaasistente.app.net

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import es.citaasistente.app.data.BackupCodec
import es.citaasistente.app.data.BackupData
import es.citaasistente.app.data.BackupPrefs
import es.citaasistente.app.data.Status
import es.citaasistente.app.data.Store
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Respaldo automático cifrado:
 *  - Archivo en Drive (o donde elijas con el selector de archivos de Android): se reescribe a los ~20 s
 *    de cualquier cambio en las solicitudes y también cada ~15 min si cambió algo (ajustes, proxies...).
 *  - Telegram: se manda el mismo archivo al confirmarse una cita nueva y como máximo 1 vez al día.
 */
object BackupManager {

    private const val DEBOUNCE_MS = 20_000L
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    data class Result(val ok: Boolean, val message: String)

    @Volatile
    private var app: Context? = null

    private val main by lazy { Handler(Looper.getMainLooper()) }

    private val task = Runnable {
        val c = app
        if (c != null) {
            Thread { runNow(c, force = false, sendTelegram = false) }.start()
        }
    }

    /** Lo llama Store al crearse: así cualquier cambio en las solicitudes programa un respaldo. */
    fun attach(ctx: Context) {
        app = ctx.applicationContext
        Store.changeListener = { schedule() }
    }

    private fun schedule() {
        main.removeCallbacks(task)
        main.postDelayed(task, DEBOUNCE_MS)
    }

    /** Llamado por el trabajo periódico: respalda solo si hay algo nuevo. */
    fun flushIfDue(ctx: Context) {
        try {
            runNow(ctx, force = false, sendTelegram = false)
        } catch (e: Exception) {
            // el siguiente ciclo lo reintenta
        }
    }

    /** Se llama SIEMPRE fuera del hilo principal. */
    @Synchronized
    fun runNow(ctx: Context, force: Boolean, sendTelegram: Boolean): Result {
        val appCtx = ctx.applicationContext
        val store = Store.get(appCtx)
        val prefs = BackupPrefs.get(appCtx)

        val pass = store.backupPass
        if (pass.length < BackupData.MIN_PASS) {
            return Result(false, "Falta la frase de respaldo (mínimo ${BackupData.MIN_PASS} caracteres).")
        }

        val s = store.settings
        val tgOn = prefs.telegram && s.telegramReady()
        val hasDrive = prefs.uri.isNotBlank()

        if (!hasDrive && !tgOn) {
            return Result(false, "Elige un archivo en Drive o activa Telegram para guardar el respaldo.")
        }

        val now = System.currentTimeMillis()
        val body = BackupData.body(store)
        val hash = BackupData.hash(body)
        val changed = hash != prefs.lastHash
        val citas = store.requests().count { it.status == Status.CON_CITA }

        val driveDue = hasDrive && (force || changed)
        val tgDue = tgOn && (
            sendTelegram ||
                citas > prefs.lastTgCitas ||
                (changed && now - prefs.lastTgAt >= DAY_MS)
            )

        if (!driveDue && !tgDue) return Result(true, "Sin cambios desde el último respaldo.")

        val bytes = BackupCodec.encrypt(
            BackupData.wrap(body, now).toString().toByteArray(Charsets.UTF_8),
            pass.toCharArray()
        )

        val parts = ArrayList<String>()
        var allOk = true

        if (driveDue) {
            val err = writeTo(appCtx, Uri.parse(prefs.uri), bytes)
            if (err == null) {
                prefs.lastHash = hash
                prefs.lastOkAt = now
                prefs.lastError = ""
                parts.add("Drive ✔")
            } else {
                allOk = false
                prefs.lastError = err
                parts.add("Drive ✖ ($err)")
            }
        }

        if (tgDue) {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
            val caption = "💾 Respaldo TIES · ${store.requests().size} solicitudes · " +
                SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "ES")).format(Date(now))
            try {
                val r = TelegramFile.sendDocument(s, bytes, "TIES-respaldo-$stamp.ties", caption)
                if (r.first) {
                    prefs.lastTgAt = now
                    prefs.lastTgCitas = citas
                    if (!hasDrive) {
                        prefs.lastHash = hash
                        prefs.lastOkAt = now
                    }
                    parts.add("Telegram ✔")
                } else {
                    allOk = false
                    parts.add("Telegram ✖ (${r.second})")
                }
            } catch (e: Exception) {
                allOk = false
                parts.add("Telegram ✖ (${e.javaClass.simpleName})")
            }
        }

        return Result(allOk, parts.joinToString(" · "))
    }

    /** Devuelve null si se escribió bien, o el motivo del fallo. */
    private fun writeTo(ctx: Context, uri: Uri, bytes: ByteArray): String? {
        return try {
            val out: OutputStream? = try {
                ctx.contentResolver.openOutputStream(uri, "wt")
            } catch (e: Exception) {
                ctx.contentResolver.openOutputStream(uri, "w")
            }

            if (out == null) {
                "no se pudo abrir el archivo"
            } else {
                out.use { it.write(bytes) }
                null
            }
        } catch (e: SecurityException) {
            "permiso perdido: vuelve a elegir el archivo"
        } catch (e: Exception) {
            e.javaClass.simpleName + ": " + e.message.orEmpty().take(60)
        }
    }

    fun readFrom(ctx: Context, uri: Uri): ByteArray {
        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("No se pudo abrir el archivo")
        return input.use { it.readBytes() }
    }

    fun displayName(ctx: Context, uri: Uri): String {
        return try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "archivo elegido"
        } catch (e: Exception) {
            "archivo elegido"
        }
    }
}
