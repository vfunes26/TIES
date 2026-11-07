package es.citaasistente.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import es.citaasistente.app.data.BackupCodec
import es.citaasistente.app.data.BackupData
import es.citaasistente.app.data.BackupPrefs
import es.citaasistente.app.data.Catalog
import es.citaasistente.app.data.HistoryReport
import es.citaasistente.app.data.Ledger
import es.citaasistente.app.data.Request
import es.citaasistente.app.data.Session
import es.citaasistente.app.data.Retention
import es.citaasistente.app.data.Status
import es.citaasistente.app.data.Store
import es.citaasistente.app.net.BackupManager
import es.citaasistente.app.net.Mailer
import es.citaasistente.app.net.TelegramFile
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Respaldo (Drive + Telegram), restauración, estadísticas por mes e historial de 30 días.
 *  - MASTER: ve y cambia todo (frase, archivo de Drive, Telegram).
 *  - Usuario UI/UX: restaura el respaldo que le dio el master, respalda (por Telegram), ve estadísticas e historial.
 * La pantalla se arma por código para no depender de archivos de diseño.
 */
class BackupActivity : AppCompatActivity() {

    private lateinit var store: Store
    private lateinit var prefs: BackupPrefs
    private lateinit var catalog: Catalog

    private lateinit var tvStatus: TextView
    private lateinit var tvMonths: TextView
    private lateinit var btnHistory: MaterialButton
    private lateinit var etPass: EditText

    private val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "ES"))

    private class PickCreate : ActivityResultContract<String, Uri?>() {
        override fun createIntent(context: Context, input: String): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, input)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK) intent?.data else null
    }

    private class PickOpen : ActivityResultContract<Unit, Uri?>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == Activity.RESULT_OK) intent?.data else null
    }

    private val createTarget = registerForActivityResult(PickCreate()) { uri ->
        if (uri != null) linkTarget(uri, runBackupAfter = true)
    }

    private val openSource = registerForActivityResult(PickOpen()) { uri ->
        if (uri != null) askPassAndRestore(uri)
    }

    // ------------------------------------------------------------------ pantalla

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Session.isLoggedIn(this)) {
            finish()
            return
        }

        store = Store.get(this)
        prefs = BackupPrefs.get(this)
        catalog = Catalog.load(this)

        val master = Session.isMaster(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(32))
        }

        root.addView(text("Respaldo, historial y estadísticas", 20f, bold = true))
        root.addView(
            text(
                if (master) {
                    "Tus solicitudes y citas se guardan cifradas en Drive y Telegram. Si desinstalas o actualizas, las recuperas aquí."
                } else {
                    "Carga aquí el respaldo que te dio el master, consulta tus estadísticas y el historial. La configuración la gestiona el master."
                },
                13f
            )
        )

        tvStatus = text("", 13f)
        root.addView(spaced(tvStatus, 12))

        if (master) {
            root.addView(text("1. Frase de respaldo", 15f, bold = true))
            etPass = EditText(this).apply {
                hint = "Frase (mínimo ${BackupData.MIN_PASS} caracteres)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            root.addView(etPass)
            root.addView(button("Guardar frase") { savePass() })
            root.addView(text("Anótala en un lugar seguro: sin ella NO se puede abrir el respaldo.", 12f))

            root.addView(spaced(text("2. Dónde se guarda", 15f, bold = true), 14))
            root.addView(button("Elegir / crear archivo de respaldo en Drive") { createTarget.launch("TIES-respaldo.ties") })

            val cbTelegram = CheckBox(this).apply {
                text = "Enviar también copia por Telegram (al confirmarse una cita y 1 vez al día)"
                isChecked = prefs.telegram
                setOnCheckedChangeListener { _, checked ->
                    prefs.telegram = checked
                    refresh()
                }
            }
            root.addView(cbTelegram)
        }

        root.addView(spaced(text(if (master) "3. Respaldar" else "1. Respaldar", 15f, bold = true), 14))
        root.addView(button("Respaldar ahora") {
            if (!master && store.backupPass.length < BackupData.MIN_PASS) {
                toast("Primero carga el respaldo del master (así se guarda la frase).")
            } else {
                runInBackground("Respaldando…") { BackupManager.runNow(this, force = true, sendTelegram = true) }
            }
        })
        if (!master) {
            root.addView(text("Tus cambios se envían cifrados por Telegram al master.", 12f))
        }

        root.addView(spaced(text(if (master) "4. Recuperar después de reinstalar" else "2. Cargar el respaldo del master", 15f, bold = true), 14))
        root.addView(button("Restaurar desde un archivo…") { openSource.launch(Unit) })
        root.addView(
            text(
                if (master) {
                    "Elige el archivo .ties (en Drive, o el que te llegó por Telegram) y escribe tu frase."
                } else {
                    "Elige el archivo .ties que te dio el master y escribe la frase que él te indicó. Se cargan las solicitudes, la configuración y los proxies."
                },
                12f
            )
        )

        root.addView(spaced(text(if (master) "5. Estadísticas por mes" else "3. Estadísticas por mes", 15f, bold = true), 14))
        tvMonths = text("", 13f)
        root.addView(tvMonths)

        root.addView(spaced(text(if (master) "6. Historial (30 días)" else "4. Historial (30 días)", 15f, bold = true), 14))
        root.addView(text("Con cita, canceladas y vencidas se conservan 30 días. Las pendientes viven 30 días y luego pasan aquí como «Vencida».", 12f))
        btnHistory = button("Ver historial") { showHistory() }
        root.addView(btnHistory)
        root.addView(button("Enviar historial por Telegram") { sendHistoryTelegram() })
        root.addView(button("Enviar historial por correo") { sendHistoryMail() })

        root.addView(spaced(button("Cerrar") { finish() }, 18))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        store.runMaintenance()
        refresh()
    }

    private fun refresh() {
        val passOk = store.backupPass.length >= BackupData.MIN_PASS
        val drive = if (prefs.uri.isBlank()) "sin elegir ✖" else "${BackupManager.displayName(this, Uri.parse(prefs.uri))} ✔"
        val tg = when {
            !store.settings.telegramReady() -> "Telegram no está configurado en Ajustes"
            prefs.telegram -> "activada ✔"
            else -> "desactivada"
        }
        val last = if (prefs.lastOkAt == 0L) "nunca" else fmt.format(Date(prefs.lastOkAt))
        val all = store.requests()
        val history = all.count { it.archivedAt > 0L }

        val master = Session.isMaster(this)

        tvStatus.text = buildString {
            append("Sesión: ${Session.label(this@BackupActivity)}\n")
            if (master) {
                append("Frase de respaldo: ${if (passOk) "configurada ✔" else "FALTA ✖"}\n")
                append("Archivo en Drive: $drive\n")
            }
            append("Copia por Telegram: $tg\n")
            append("Último respaldo: $last\n")
            if (prefs.lastError.isNotBlank()) append("Último error: ${prefs.lastError}\n")
            append("Solicitudes activas: ${all.size - history} · En el historial: $history")
        }

        tvMonths.text = Ledger.toText(store.ledgerRaw())
        btnHistory.text = "Ver historial ($history)"
    }

    // ------------------------------------------------------------------ respaldo

    private fun savePass() {
        val p = etPass.text.toString()
        if (p.length < BackupData.MIN_PASS) {
            toast("La frase debe tener al menos ${BackupData.MIN_PASS} caracteres.")
            return
        }
        store.backupPass = p
        etPass.setText("")
        toast("Frase guardada. Anótala: sin ella no se puede recuperar el respaldo.")
        refresh()
    }

    private fun linkTarget(uri: Uri, runBackupAfter: Boolean) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            toast("No se pudo guardar el permiso sobre ese archivo. Elige otro.")
            return
        }

        prefs.uri = uri.toString()
        prefs.lastHash = ""
        refresh()

        if (runBackupAfter) {
            if (store.backupPass.length < BackupData.MIN_PASS) {
                toast("Archivo elegido. Ahora guarda tu frase de respaldo y pulsa «Respaldar ahora».")
            } else {
                runInBackground("Respaldando…") { BackupManager.runNow(this, force = true, sendTelegram = false) }
            }
        }
    }

    private fun runInBackground(label: String, block: () -> BackupManager.Result) {
        toast(label)
        Thread {
            val r = try {
                block()
            } catch (e: Exception) {
                BackupManager.Result(false, "Error: ${e.javaClass.simpleName} ${e.message.orEmpty().take(80)}")
            }
            runOnUiThread {
                toast((if (r.ok) "✔ " else "✖ ") + r.message)
                refresh()
            }
        }.start()
    }

    // ------------------------------------------------------------------ restaurar

    private fun askPassAndRestore(uri: Uri) {
        val input = EditText(this).apply {
            hint = "Frase del respaldo"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(store.backupPass)
        }

        AlertDialog.Builder(this)
            .setTitle("Frase del respaldo")
            .setView(input)
            .setPositiveButton("Continuar") { _, _ -> decryptAndConfirm(uri, input.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun decryptAndConfirm(uri: Uri, pass: String) {
        toast("Abriendo el respaldo…")

        Thread {
            var json: JSONObject? = null
            var error: String? = null

            try {
                val bytes = BackupManager.readFrom(this, uri)
                json = BackupData.parse(BackupCodec.decrypt(bytes, pass.toCharArray()))
            } catch (e: BackupCodec.WrongPassphraseException) {
                error = "Frase incorrecta (o el archivo está dañado)."
            } catch (e: BackupCodec.NotABackupException) {
                error = "Ese archivo no es un respaldo de TIES."
            } catch (e: Exception) {
                error = "No se pudo leer el respaldo: ${e.javaClass.simpleName}"
            }

            runOnUiThread {
                val ok = json
                if (ok == null) {
                    toast(error ?: "No se pudo abrir el respaldo.")
                } else {
                    confirmRestore(uri, pass, ok)
                }
            }
        }.start()
    }

    private fun confirmRestore(uri: Uri, pass: String, json: JSONObject) {
        val s = BackupData.peek(json, store.requests())
        val whenText = if (s.createdAt > 0L) fmt.format(Date(s.createdAt)) else "fecha desconocida"

        val msg = "Respaldo del $whenText\n\n" +
            "• Solicitudes en el archivo: ${s.requests} (nuevas aquí: ${s.added}, más recientes: ${s.updated})\n" +
            "• Meses de estadísticas: ${s.months}\n" +
            "• Proxies: ${s.proxies}\n" +
            "• Ajustes (Telegram, correo...): ${if (s.hasSettings) "sí, se reemplazan los actuales" else "no incluidos"}\n\n" +
            "Las solicitudes se combinan con las que ya tengas (gana la más reciente)."

        AlertDialog.Builder(this)
            .setTitle("¿Restaurar?")
            .setMessage(msg)
            .setPositiveButton("Restaurar") { _, _ ->
                try {
                    BackupData.restore(store, json)
                    store.backupPass = pass
                    if (Session.isMaster(this)) linkTarget(uri, runBackupAfter = false)
                    store.runMaintenance()
                    refresh()
                    toast("✔ Restaurado. Revisa Ajustes y reinicia la app para aplicar todo.")
                } catch (e: Exception) {
                    toast("No se pudo restaurar: ${e.javaClass.simpleName}")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ------------------------------------------------------------------ historial

    private fun label(r: Request): String {
        val p = catalog.province(r.profile.provinceCode)
        return "${p?.name ?: r.profile.provinceCode} · ${p?.procedure(r.profile.procedureId)?.name ?: r.profile.procedureId}"
    }

    private fun historyList(): List<Request> =
        store.requests().filter { it.archivedAt > 0L }.sortedByDescending { it.archivedAt }

    private fun showHistory() {
        val now = System.currentTimeMillis()
        val items = historyList().take(200)

        if (items.isEmpty()) {
            toast("El historial está vacío.")
            return
        }

        val labels = items.map {
            "${HistoryReport.kind(it)} · ${it.displayName()} · se borra en ${Retention.daysLeft(it, now)} d"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Historial (${items.size}) · 30 días")
            .setItems(labels) { _, index -> showHistoryItem(items[index].id) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showHistoryItem(id: String) {
        val r = store.request(id) ?: return
        val p = r.profile
        val now = System.currentTimeMillis()

        val text = buildString {
            append("${p.docType} ${p.docNumber}\n${label(r)}\n")
            append("Registrada: ${fmt.format(Date(r.createdAt))} · Intentos: ${r.attempts}\n")
            append("Pasó al historial: ${fmt.format(Date(r.archivedAt))} · Se borra en ${Retention.daysLeft(r, now)} días\n")
            if (r.slot.isNotBlank()) append("Cita: ${r.slot}\n")
            if (r.reference.isNotBlank()) append("Justificante: ${r.reference}\n")
            if (r.failReason.isNotBlank()) append("Motivo: ${r.failReason}\n")
            if (r.result.isNotBlank()) append("\nConfirmación de la sede:\n${r.result.take(600)}")
        }

        val b = AlertDialog.Builder(this)
            .setTitle("${HistoryReport.kind(r)} · ${r.displayName()}")
            .setMessage(text)
            .setNegativeButton("Cerrar", null)

        if (r.status == Status.CANCELADA) {
            b.setPositiveButton("Reactivar") { _, _ ->
                store.updateRequest(id) {
                    it.copy(
                        status = Status.PENDIENTE,
                        failReason = "",
                        slot = "",
                        createdAt = System.currentTimeMillis()
                    )
                }
                toast("Solicitud reactivada: vuelve a la cola como pendiente.")
                refresh()
            }
        }

        b.setNeutralButton("Eliminar") { _, _ ->
            AlertDialog.Builder(this)
                .setTitle("¿Eliminar del historial?")
                .setMessage("Se borra definitivamente. Las estadísticas por mes NO cambian.")
                .setPositiveButton("Eliminar") { _, _ ->
                    store.removeRequest(id)
                    refresh()
                }
                .setNegativeButton("No", null)
                .show()
        }

        b.show()
    }

    private fun sendHistoryTelegram() {
        val s = store.settings
        if (!s.telegramReady()) {
            toast("Configura y activa Telegram en Ajustes.")
            return
        }

        val items = historyList()
        if (items.isEmpty()) {
            toast("El historial está vacío.")
            return
        }

        val now = System.currentTimeMillis()
        val body = HistoryReport.text(items, { label(it) }, now)
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(now))

        toast("Enviando historial…")
        Thread {
            val msg = try {
                val r = TelegramFile.sendDocument(
                    s,
                    body.toByteArray(Charsets.UTF_8),
                    "TIES-historial-$stamp.txt",
                    "📚 Historial TIES · ${items.size} registros"
                )
                if (r.first) "✔ Historial enviado por Telegram" else "✖ Telegram: ${r.second}"
            } catch (e: Exception) {
                "✖ Telegram: ${e.javaClass.simpleName}"
            }
            runOnUiThread { toast(msg) }
        }.start()
    }

    private fun sendHistoryMail() {
        val s = store.settings
        if (!s.mailReady()) {
            toast("Configura y activa el correo en Ajustes.")
            return
        }

        val items = historyList()
        if (items.isEmpty()) {
            toast("El historial está vacío.")
            return
        }

        val body = HistoryReport.text(items, { label(it) }, System.currentTimeMillis())

        toast("Enviando historial…")
        Mailer.send(s, "[CitaAsistente] Historial (${items.size} registros)", body) { ok, msg ->
            runOnUiThread { toast((if (ok) "✔ " else "✖ ") + msg) }
        }
    }

    // ------------------------------------------------------------------ utilidades de pantalla

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun text(t: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun spaced(v: View, topDp: Int): View {
        v.setPadding(v.paddingLeft, dp(topDp), v.paddingRight, v.paddingBottom)
        return v
    }

    private fun button(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            setOnClickListener { onClick() }
        }
}
