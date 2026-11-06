package es.citaasistente.app.ui

import android.Manifest
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import es.citaasistente.app.R
import es.citaasistente.app.alerts.AlertParser
import es.citaasistente.app.alerts.HealthChecker
import es.citaasistente.app.alerts.HealthWorker
import es.citaasistente.app.alerts.Notifier
import es.citaasistente.app.data.Catalog
import es.citaasistente.app.data.Nationalities
import es.citaasistente.app.data.Nif
import es.citaasistente.app.data.Profile
import es.citaasistente.app.data.Request
import es.citaasistente.app.data.Settings
import es.citaasistente.app.data.Stats
import es.citaasistente.app.data.Status
import es.citaasistente.app.data.Store
import es.citaasistente.app.data.ProxyParser
import es.citaasistente.app.net.Ev
import com.vfunes26.ties.model.AlertModeManager
import com.vfunes26.ties.model.RequestSelector
import com.vfunes26.ties.model.SelectionMode
import es.citaasistente.app.net.Mailer
import es.citaasistente.app.net.Reporter
import es.citaasistente.app.net.Telegram
import es.citaasistente.app.net.TelegramIntakeService
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var catalog: Catalog
    private lateinit var nats: Nationalities
    private lateinit var store: Store
    private lateinit var alertModeManager: AlertModeManager
    private lateinit var adapter: RequestAdapter

    private val accs = HashMap<String, Acc>()
    private lateinit var panels: Map<Int, View>

    // Paso 1
    private lateinit var ddDocType: MaterialAutoCompleteTextView
    private lateinit var etDoc: TextInputEditText
    private lateinit var etName: TextInputEditText
    private lateinit var ddNationality: MaterialAutoCompleteTextView
    private lateinit var etPhone: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    // Paso 2
    private lateinit var ddProvince: MaterialAutoCompleteTextView
    private lateinit var ddProcedure: MaterialAutoCompleteTextView
    private lateinit var btnOffices: MaterialButton
    private lateinit var etMinDate: TextInputEditText

    private var provinceCode = ""
    private var procedureId = ""
    private var offices = mutableListOf<String>()
    private var step1Done = false
    private var step2Done = false

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshAll() }

    /** Sección desplegable (acordeón): se abre una y se cierran las demás de su pestaña. */
    private inner class Acc(val key: String) {
        val hdr: View = findViewById(rid("hdr_$key"))
        val body: View = findViewById(rid("body_$key"))
        val arrow: TextView = findViewById(rid("arr_$key"))
        val check: TextView = findViewById(rid("chk_$key"))
        var group: List<Acc> = emptyList()
        fun open() { group.filter { it !== this }.forEach { it.close() }; body.visibility = View.VISIBLE; arrow.text = "▴" }
        fun close() { body.visibility = View.GONE; arrow.text = "▾" }
        fun toggle() { if (body.visibility == View.VISIBLE) close() else open() }
        fun done(ok: Boolean) { check.visibility = if (ok) View.VISIBLE else View.GONE }
        init { hdr.setOnClickListener { toggle() } }
    }

    private fun rid(name: String): Int = resources.getIdentifier(name, "id", packageName)
    private fun <T : View> v(name: String): T = findViewById(rid(name))
    private fun acc(key: String): Acc = accs.getValue(key)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        catalog = Catalog.load(this)
        nats = Nationalities.load(this)
        store = Store.get(this)
        alertModeManager = AlertModeManager(this)

        setupAccordions()
        setupSolicitar()
        setupCola()
        setupAlertas()
        setupAjustes()
        setupNav()
        syncTelegramIntake(store.settings)

        Notifier.ensureChannels(this)
        HealthWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        acc("p1").open()
    }

    override fun onResume() { super.onResume(); refreshAll() }

    // ================= Navegación y acordeones =================
    private fun setupAccordions() {
        listOf("p1", "p2", "a1", "a2", "a3", "s1", "s2", "s3", "s4", "s5").forEach { accs[it] = Acc(it) }
        listOf(listOf("p1", "p2"), listOf("a1", "a2", "a3"), listOf("s1", "s2", "s3", "s4", "s5")).forEach { keys ->
            val list = keys.map { acc(it) }
            list.forEach { it.group = list }
        }
    }

    private fun setupNav() {
        panels = mapOf(
            R.id.nav_solicitar to v<View>("panelSolicitar"), R.id.nav_cola to v<View>("panelCola"),
            R.id.nav_alertas to v<View>("panelAlertas"), R.id.nav_ajustes to v<View>("panelAjustes"),
        )
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            panels.forEach { (id, view) -> view.visibility = if (id == item.itemId) View.VISIBLE else View.GONE }
            refreshAll(); true
        }
        nav.selectedItemId = R.id.nav_solicitar
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun problems(list: List<String>) {
        AlertDialog.Builder(this).setTitle("Revisa estos datos")
            .setMessage(list.joinToString("\n") { "• $it" }).setPositiveButton("Vale", null).show()
    }

    // ================= SOLICITAR (pasos 1 y 2) =================
    private fun setupSolicitar() {
        ddDocType = v("ddDocType"); etDoc = v("etDoc"); etName = v("etName"); ddNationality = v("ddNationality")
        etPhone = v("etPhone"); etEmail = v("etEmail")
        ddProvince = v("ddProvince"); ddProcedure = v("ddProcedure"); btnOffices = v("btnOffices"); etMinDate = v("etMinDate")

        ddDocType.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("NIE", "DNI", "PASAPORTE")))
        ddDocType.setText("NIE", false)
        ddNationality.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, nats.list.map { it.label }))
        ddNationality.threshold = 1
        ddProvince.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, catalog.provinces.map { it.name }))
        ddProvince.threshold = 1

        ddProvince.doAfterTextChanged { t ->
            val code = catalog.provinces.firstOrNull { it.name == t.toString().trim() }?.code ?: ""
            if (code != provinceCode) {
                provinceCode = code; procedureId = ""; offices.clear()
                ddProcedure.setText("", false); refreshProcedures(); refreshOfficesLabel()
            }
        }
        ddProcedure.setOnItemClickListener { _, _, pos, _ ->
            val name = ddProcedure.adapter.getItem(pos) as String
            val proc = catalog.province(provinceCode)?.procedures?.firstOrNull { it.name == name }
            if (proc != null && proc.id != procedureId) { procedureId = proc.id; offices.clear(); refreshOfficesLabel() }
        }
        btnOffices.setOnClickListener { chooseOffices() }
        etMinDate.setOnClickListener { pickDate() }
        v<MaterialButton>("btnSaveP1").setOnClickListener { saveStep1() }
        v<MaterialButton>("btnSaveP2").setOnClickListener { saveStep2() }
        findViewById<MaterialButton>(R.id.btnSolicitar).setOnClickListener { solicitar() }
    }

    private fun refreshProcedures() {
        val procs = catalog.province(provinceCode)?.procedures?.map { it.name }?.sorted() ?: emptyList()
        ddProcedure.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, procs))
    }

    private fun refreshOfficesLabel() {
        btnOffices.text = when (offices.size) {
            0 -> "Sedes: cualquier oficina de la provincia"
            1 -> "Sede: ${offices[0].take(48)}"
            else -> "Sedes: ${offices.size} elegidas (por orden de prioridad)"
        }
    }

    private fun chooseOffices() {
        val proc = catalog.province(provinceCode)?.procedure(procedureId)
        if (proc == null) { toast("Elige primero provincia y trámite"); return }
        val names = proc.offices.map { it.name }
        val checked = BooleanArray(names.size) { names[it] in offices }
        val order = offices.toMutableList()
        AlertDialog.Builder(this)
            .setTitle("Sedes por orden de prioridad")
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, i, on ->
                if (on) { if (names[i] !in order) order.add(names[i]) } else order.remove(names[i])
            }
            .setPositiveButton("Aceptar") { _, _ -> offices = order; refreshOfficesLabel() }
            .setNeutralButton("Cualquiera") { _, _ -> offices.clear(); refreshOfficesLabel() }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun pickDate() {
        val c = Calendar.getInstance()
        Regex("(\\d{4})-(\\d{2})-(\\d{2})").matchEntire(etMinDate.text.toString())?.let {
            c.set(it.groupValues[1].toInt(), it.groupValues[2].toInt() - 1, it.groupValues[3].toInt())
        }
        DatePickerDialog(this, { _, y, m, d -> etMinDate.setText("%04d-%02d-%02d".format(y, m + 1, d)) },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun draft(): Profile {
        val nat = nats.byLabelExact(ddNationality.text.toString())
        return Profile(
            docType = ddDocType.text.toString().ifBlank { "NIE" },
            docNumber = Nif.clean(etDoc.text.toString()),
            fullName = etName.text.toString().trim().replace(Regex("\\s+"), " "),
            nationalityCode = nat?.value ?: "", nationalityLabel = nat?.label ?: "",
            phone = etPhone.text.toString().trim(), email = etEmail.text.toString().trim(),
            provinceCode = provinceCode, procedureId = procedureId, offices = offices.toList(),
            minDate = etMinDate.text.toString().trim(),
        )
    }

    private fun saveStep1() {
        val d = draft()
        val probs = d.step1Problems(nats)
        step1Done = probs.isEmpty()
        acc("p1").done(step1Done)
        if (!step1Done) { problems(probs); return }
        ddNationality.setText(d.nationalityLabel, false)
        acc("p1").close(); acc("p2").open()
    }

    private fun saveStep2() {
        val probs = draft().step2Problems(catalog)
        step2Done = probs.isEmpty()
        acc("p2").done(step2Done)
        if (!step2Done) { problems(probs); return }
        acc("p2").close()
        toast("Paso guardado. Pulsa SOLICITAR para añadirla a la cola.")
    }

    private fun solicitar() {
        val p = draft()
        val probs = p.problems(catalog, nats)
        if (probs.isNotEmpty()) { problems(probs); return }
        val dup = store.requests().any {
            it.profile.docNumber == p.docNumber && it.profile.provinceCode == p.provinceCode && it.profile.procedureId == p.procedureId &&
                it.status in listOf(Status.PENDIENTE, Status.EN_PROCESO, Status.ESPERANDO_HUMANO)
        }
        if (dup) { problems(listOf("Ya hay una solicitud activa para ${p.docNumber} con este trámite y provincia.")); return }

        val req = Request(profile = p)
        store.addRequest(req)
        Reporter.log(this, req, "registrada", "Solicitud registrada en la cola")
        Reporter.notify(this, Ev.REGISTRADA, store.request(req.id), "Solicitud guardada en la cola.")

        // Se conserva el trámite (paso 2) para registrar rápido a la siguiente persona; se limpia el paso 1.
        etDoc.setText(""); etName.setText(""); ddNationality.setText("", false); etPhone.setText(""); etEmail.setText("")
        step1Done = false; acc("p1").done(false); acc("p1").open()
        toast("Solicitud registrada · en cola: ${store.requests().count { it.status == Status.PENDIENTE }}")
        refreshAll()
    }

    // ================= COLA =================
    private fun setupCola() {
        adapter = RequestAdapter(
            catalog = catalog,
            onClick = { showDetail(it.id) },
            onCheckChange = { _, _ -> } // Se maneja internamente en el adaptador con selectedIds
        )
        val rv = findViewById<RecyclerView>(R.id.rvRequests)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        findViewById<MaterialButton>(R.id.btnProcesar).setOnClickListener { startQueue(0) }
        findViewById<MaterialButton>(R.id.btnLote).setOnClickListener { startQueue(store.settings.batchSize) }
    }

    private fun startQueue(limit: Int) {
        if (store.requests().none { it.status == Status.PENDIENTE }) { toast("No hay solicitudes pendientes"); return }
        if (AssistantActivity.active) { toast("Ya hay un proceso en marcha"); return }
        startActivity(Intent(this, AssistantActivity::class.java)
            .putExtra(AssistantActivity.EXTRA_MODE, AssistantActivity.MODE_QUEUE).putExtra(AssistantActivity.EXTRA_LIMIT, limit))
    }

    private fun startSingle(id: String) {
        if (AssistantActivity.active) { toast("Ya hay un proceso en marcha"); return }
        startActivity(Intent(this, AssistantActivity::class.java)
            .putExtra(AssistantActivity.EXTRA_MODE, AssistantActivity.MODE_SINGLE).putExtra(AssistantActivity.EXTRA_REQUEST_ID, id))
    }

    private fun order(s: Status) = when (s) {
        Status.ESPERANDO_HUMANO -> 0; Status.EN_PROCESO -> 1; Status.PENDIENTE -> 2
        Status.FALLIDA -> 3; Status.CON_CITA -> 4; Status.CANCELADA -> 5
    }

    private fun refreshQueue() {
        val all = store.requests()
        adapter.submit(all.sortedWith(compareBy({ order(it.status) }, { it.createdAt })))
        findViewById<View>(R.id.tvEmpty).visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvStats).text = Stats.compute(all, catalog).toText().lineSequence().first()
    }

    private val timeFmt = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("es", "ES"))

    private fun showDetail(id: String) {
        val r = store.request(id) ?: return
        val p = r.profile
        val prov = catalog.province(p.provinceCode)
        val text = buildString {
            append("${p.docType} ${p.docNumber}\nNacionalidad: ${p.nationalityLabel}\nTel.: ${p.phone} · ${p.email}\n")
            append("${prov?.name} · ${prov?.procedure(p.procedureId)?.name}\n")
            append("Sedes: ${if (p.offices.isEmpty()) "cualquiera" else p.offices.joinToString(" | ")}\nDesde: ${p.minDate} · Intentos: ${r.attempts}\n")
            if (r.slot.isNotBlank()) append("Cita: ${r.slot}\n")
            if (r.reference.isNotBlank()) append("Justificante: ${r.reference}\n")
            if (r.failReason.isNotBlank()) append("Motivo del fallo: ${r.failReason}\n")
            append("\nPROCESO REALIZADO\n")
            if (r.history.isEmpty()) append("(sin actividad todavía)")
            r.history.reversed().forEach { append("${timeFmt.format(Date(it.t))}  [${it.kind}] ${it.text}\n") }
            if (r.result.isNotBlank()) append("\nCONFIRMACIÓN DE LA SEDE\n${r.result}")
        }
        val b = AlertDialog.Builder(this).setTitle("${r.displayName()} · ${r.status.label}").setMessage(text)
        when (r.status) {
            Status.PENDIENTE -> {
                b.setPositiveButton("Iniciar ahora") { _, _ -> startSingle(r.id) }
                b.setNeutralButton("Cancelar solicitud") { _, _ -> cancelRequest(r.id) }
            }
            Status.FALLIDA, Status.CANCELADA -> {
                b.setPositiveButton("Reactivar e iniciar") { _, _ ->
                    store.updateRequest(r.id) { it.copy(status = Status.PENDIENTE, failReason = "") }
                    Reporter.log(this, r, "manual", "Reactivada"); startSingle(r.id)
                }
                b.setNeutralButton("Cerrar", null)
            }
            Status.CON_CITA -> {
                b.setPositiveButton("Copiar resumen") { _, _ ->
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("cita", text)); toast("Copiado")
                }
                b.setNeutralButton("Reenviar aviso") { _, _ -> Reporter.notify(this, Ev.CON_CITA, r, r.result.take(500)); toast("Aviso reenviado") }
            }
            else -> b.setPositiveButton("Cerrar", null)
        }
        b.setNegativeButton("Eliminar") { _, _ ->
            AlertDialog.Builder(this).setTitle("¿Eliminar la solicitud?").setMessage("Se borra también su historial.")
                .setPositiveButton("Eliminar") { _, _ -> store.removeRequest(r.id); refreshAll() }
                .setNegativeButton("No", null).show()
        }
        b.show()
    }

    private fun cancelRequest(id: String) {
        val u = store.updateRequest(id) { it.copy(status = Status.CANCELADA) }
        Reporter.log(this, u, "manual", "Cancelada por el usuario")
        refreshAll()
    }

    // ================= ALERTAS =================
    private fun setupAlertas() {
        v<MaterialButton>("btnWatch").setOnClickListener { startQueue(0) }
        v<MaterialButton>("btnSaveA1").setOnClickListener { saveSettings("a1") }
        v<MaterialButton>("btnAccess").setOnClickListener { startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        v<MaterialButton>("btnOverlay").setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        v<MaterialButton>("btnBattery").setOnClickListener { startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        v<MaterialButton>("btnOpenAlerts").setOnClickListener {
            val i = packageManager.getLaunchIntentForPackage(AlertParser.ALERTS_PKG)
            if (i != null) startActivity(i) else toast("Extranjería Alerts no está instalada (no hace falta)")
        }
        v<MaterialButton>("btnTest").setOnClickListener { testInterpretation() }
        v<MaterialButton>("btnLog").setOnClickListener { showLog() }
    }

    private fun refreshAlertas() {
        val pending = store.requests().count { it.status == Status.PENDIENTE }
        v<TextView>("tvMonitor").text = "Vigilancia: ${if (AssistantActivity.active) "ACTIVA (procesando la cola)" else "inactiva"}\n" +
            "Solicitudes pendientes: $pending · Detección propia + alertas externas opcionales"
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale("es", "ES"))
        val ev = store.events().filter { it.second in listOf("alerta", "sin citas", "ignorada", "salud", "CITA CONFIRMADA") }.take(8)
        v<TextView>("tvAlertLog").text = if (ev.isEmpty()) "Sin alertas todavía." else ev.joinToString("\n") { "${fmt.format(Date(it.first))} [${it.second}] ${it.third.take(90)}" }
        refreshHealth()
    }

    private fun refreshHealth() {
        val box = v<LinearLayout>("healthContainer")
        box.removeAllViews()
        HealthChecker.check(this).forEach { item ->
            val tv = TextView(this)
            tv.text = (if (item.ok) "✔ " else if (item.critical) "✖ " else "⚠ ") + item.label + " — " + item.detail
            tv.setTextColor(ContextCompat.getColor(this, when { item.ok -> R.color.ok; item.critical -> R.color.rojo; else -> R.color.gualda_oscuro }))
            tv.textSize = 13f
            tv.setPadding(0, 3, 0, 3)
            box.addView(tv)
        }
    }

    private fun testInterpretation() {
        val first = store.requests().firstOrNull { it.status == Status.PENDIENTE }
        val prov = first?.let { catalog.province(it.profile.provinceCode) }
        val proc = prov?.procedure(first?.profile?.procedureId ?: "")
        val input = EditText(this).apply {
            setText("Cita disponible · ${prov?.name ?: "Madrid"} · ${proc?.name ?: "POLICÍA-TOMA DE HUELLAS"}")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this).setTitle("Pega el texto de una alerta").setView(input)
            .setPositiveButton("Interpretar") { _, _ ->
                val r = AlertParser.interpret(
                    AlertParser.ALERTS_PKG, null, input.text.toString(), prov?.name, first?.profile?.procedureId, proc?.name,
                    catalog.provinces.map { it.name }, prov?.procedures?.filter { it.id != first?.profile?.procedureId }?.map { it.name } ?: emptyList(),
                )
                val go = AlertParser.shouldStart(r, store.settings.strictMatch)
                fun t(b: Boolean?) = when (b) { true -> "sí"; false -> "NO (es de otra)"; null -> "no se sabe" }
                AlertDialog.Builder(this).setTitle(if (go) "✔ Lanzaría el autorrelleno" else "✖ No lo lanzaría")
                    .setMessage("Tipo: ${r.kind}\nMi provincia: ${t(r.province)}\nMi trámite: ${t(r.procedure)}\n\n(Se compara con la primera solicitud pendiente.)")
                    .setPositiveButton("Vale", null)
                    .setNeutralButton("Probar alarma") { _, _ -> Notifier.citaAvailable(this, "¡Cita disponible! (prueba)", r.summary) }
                    .show()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun showLog() {
        val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale("es", "ES"))
        val ev = store.events()
        val text = if (ev.isEmpty()) "Sin eventos todavía." else ev.joinToString("\n\n") { "${fmt.format(Date(it.first))}  [${it.second}]\n${it.third}" }
        AlertDialog.Builder(this).setTitle("Registro (últimos 100)").setMessage(text)
            .setPositiveButton("Cerrar", null).setNegativeButton("Borrar") { _, _ -> store.clearEvents(); refreshAll() }.show()
    }

    // ================= AJUSTES =================
    private fun setupAjustes() {
        loadSettings(store.settings)
        setupProxyUi()
        v<MaterialButton>("btnSaveS1").setOnClickListener { saveSettings("s1") }
        v<MaterialButton>("btnSaveS2").setOnClickListener { saveSettings("s2") }
        v<MaterialButton>("btnSaveS3").setOnClickListener { saveSettings("s3") }
        v<MaterialButton>("btnTgTest").setOnClickListener {
            val s = readSettings()
            if (s.telegramToken.isBlank() || s.telegramChatId.isBlank()) { toast("Rellena el token y el chat"); return@setOnClickListener }
            Telegram.send(s, "✅ Prueba de CitaAsistente: Telegram conectado.") { ok, msg -> toast(if (ok) "Telegram: $msg" else "Telegram falló: $msg") }
        }
        v<MaterialButton>("btnMailTest").setOnClickListener {
            val s = readSettings()
            if (!s.copy(mailEnabled = true).mailReady()) { toast("Completa servidor, usuario, contraseña y destinatario"); return@setOnClickListener }
            toast("Enviando…")
            Mailer.send(s, "[CitaAsistente] Prueba de correo", "Si lees esto, el envío de informes funciona.") { ok, msg -> toast(if (ok) "Correo: $msg" else msg) }
        }
        v<MaterialButton>("btnSendSummary").setOnClickListener { Reporter.sendSummary(this) { toast(it) } }
    }

    private fun sw(name: String) = v<MaterialSwitch>(name)
    private fun et(name: String) = v<TextInputEditText>(name)
    private fun num(name: String, def: Int) = et(name).text.toString().trim().toIntOrNull() ?: def

    private fun loadSettings(s: Settings) {
        sw("swAlertsApp").isChecked = s.sourceAlertsApp
        sw("swTelegramSrc").isChecked = s.sourceTelegram
        sw("swStrict").isChecked = s.strictMatch
        sw("swAutoLaunch").isChecked = s.autoLaunch
        sw("swScreenOn").isChecked = s.screenOnOnAlert
        et("etSilence").setText(s.silenceHours.toString())

        sw("swTg").isChecked = s.telegramEnabled
        et("etToken").setText(s.telegramToken)
        et("etChat").setText(s.telegramChatId)
        sw("swTgAlerts").isChecked = s.tgAlerts
        sw("swTgProcess").isChecked = s.tgProcess
        sw("swTgResults").isChecked = s.tgResults
        sw("swTgIntake").isChecked = s.tgIntake

        sw("swMail").isChecked = s.mailEnabled
        et("etSmtpHost").setText(s.smtpHost)
        et("etSmtpPort").setText(s.smtpPort.toString())
        et("etSmtpUser").setText(s.smtpUser)
        et("etSmtpPass").setText(s.smtpPass)
        et("etMailTo").setText(s.mailTo)
        sw("swMailReg").isChecked = s.mailOnRegistered
        sw("swMailResult").isChecked = s.mailOnResult

        et("etBatch").setText(s.batchSize.toString())
        et("etBatchPause").setText(s.batchPauseMinutes.toString())
        et("etRetry").setText(s.retryMinutes.toString())
        et("etGap").setText(s.minGapSeconds.toString())
        et("etCaptchaTimeout").setText(s.captchaTimeoutMinutes.toString())
        sw("swConsent").isChecked = s.autoConsent
        sw("swProxyEnabled").isChecked = s.proxyEnabled

        for (k in listOf("a1", "s1", "s2", "s3")) acc(k).done(false)
    }

    private fun readSettings(): Settings {
        val d = store.settings
        return d.copy(
            sourceAlertsApp = sw("swAlertsApp").isChecked,
            sourceTelegram = sw("swTelegramSrc").isChecked,
            strictMatch = sw("swStrict").isChecked,
            autoLaunch = sw("swAutoLaunch").isChecked,
            screenOnOnAlert = sw("swScreenOn").isChecked,
            silenceHours = num("etSilence", 0).coerceAtLeast(0),

            telegramEnabled = sw("swTg").isChecked,
            telegramToken = et("etToken").text.toString().trim(),
            telegramChatId = et("etChat").text.toString().trim(),
            tgAlerts = sw("swTgAlerts").isChecked,
            tgProcess = sw("swTgProcess").isChecked,
            tgResults = sw("swTgResults").isChecked,
            tgIntake = sw("swTgIntake").isChecked,

            mailEnabled = sw("swMail").isChecked,
            smtpHost = et("etSmtpHost").text.toString().trim(),
            smtpPort = num("etSmtpPort", 587),
            smtpUser = et("etSmtpUser").text.toString().trim(),
            smtpPass = et("etSmtpPass").text.toString(),
            mailTo = et("etMailTo").text.toString().trim(),
            mailOnRegistered = sw("swMailReg").isChecked,
            mailOnResult = sw("swMailResult").isChecked,

            batchSize = num("etBatch", 5).coerceIn(1, 50),
            batchPauseMinutes = num("etBatchPause", 10).coerceIn(0, 240),
            retryMinutes = num("etRetry", 5).coerceAtLeast(3),
            minGapSeconds = num("etGap", 45).coerceAtLeast(30),
            captchaTimeoutMinutes = num("etCaptchaTimeout", 5).coerceIn(2, 30),
            autoConsent = sw("swConsent").isChecked,
            proxyEnabled = sw("swProxyEnabled").isChecked,
        )
    }

    private fun syncTelegramIntake(s: Settings) {
        val service = Intent(this, TelegramIntakeService::class.java)

        val validChat = Regex("^-?\\d+$").matches(s.telegramChatId.trim())
        val shouldRun = s.tgIntake && s.telegramReady() && validChat

        if (shouldRun) {
            ContextCompat.startForegroundService(this, service)
        } else {
            stopService(service)
        }
    }

    private fun saveSettings(key: String) {
        val s = readSettings()
        val errs = ArrayList<String>()

        if (key == "s1" && s.telegramEnabled) {
            if (!Regex("^\\d{5,}:[A-Za-z0-9_-]{20,}$").matches(s.telegramToken)) {
                errs.add("El token de Telegram no tiene el formato de @BotFather (123456:ABC…).")
            }
            if (!Regex("^(-?\\d+|@\\w+)$").matches(s.telegramChatId)) {
                errs.add("El Chat ID debe ser un número (puede empezar por -) o @canal.")
            }
        }

        if (key == "s1" && s.tgIntake) {
            if (!s.telegramEnabled || !s.telegramReady()) {
                errs.add("Activa Telegram y configura el token y el Chat ID antes de recibir solicitudes.")
            }
            if (!Regex("^-?\\d+$").matches(s.telegramChatId.trim())) {
                errs.add("La recepción requiere un Chat ID numérico. No admite @canal.")
            }
        }

        if (key == "s2" && s.mailEnabled) {
            val mail = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
            if (s.smtpHost.isBlank()) errs.add("Falta el servidor SMTP.")
            if (s.smtpPort !in listOf(465, 587)) {
                errs.add("Usa el puerto 587 (STARTTLS) o 465 (SSL).")
            }
            if (!mail.matches(s.smtpUser)) errs.add("El usuario/remitente debe ser un correo válido.")
            if (s.smtpPass.isBlank()) errs.add("Falta la contraseña de aplicación.")
            if (!mail.matches(s.mailTo.split(",", ";").first().trim())) {
                errs.add("El correo destinatario no es válido.")
            }
        }

        if (errs.isNotEmpty()) {
            problems(errs)
            acc(key).done(false)
            return
        }

        store.settings = s
        syncTelegramIntake(s)
        loadSettings(s)
        acc(key).done(true)
        acc(key).close()
        refreshAll()
        toast("Guardado")
    }
    private fun setupProxyUi() {
        refreshProxyList()

        v<MaterialButton>("btnProxyImport").setOnClickListener {
            val raw = et("etProxyImport").text.toString()
            val (newProxies, invalid) = ProxyParser.parseMany(raw, store.proxies())

            if (newProxies.isEmpty()) {
                toast(
                    if (invalid > 0) "No se encontraron proxies válidos."
                    else "No hay proxies nuevos para importar."
                )
                return@setOnClickListener
            }

            store.saveProxies(store.proxies() + newProxies)
            et("etProxyImport").setText("")
            refreshProxyList()

            toast("${newProxies.size} proxy(s) añadidos. Líneas inválidas: $invalid")
        }

        v<MaterialButton>("btnProxyManage").setOnClickListener {
            manageProxies()
        }

        v<MaterialButton>("btnProxyTest").setOnClickListener {
            testProxyConnections()
        }

        v<MaterialButton>("btnSaveProxy").setOnClickListener {
            store.settings = store.settings.copy(
                proxyEnabled = sw("swProxyEnabled").isChecked
            )

            acc("s5").done(true)
            acc("s5").close()
            refreshProxyList()
            refreshAll()
            toast("Configuración de proxies guardada.")
        }
    }

    private fun refreshProxyList() {
        val proxies = store.proxies()

        v<TextView>("tvProxyList").text =
            if (proxies.isEmpty()) {
                "No hay proxies importados."
            } else {
                proxies.joinToString("\n") { proxy ->
                    val state = when (proxy.status) {
                        1 -> "TCP accesible"
                        2 -> "Error de conexión"
                        else -> "Sin probar"
                    }

                    val auth = if (
                        proxy.user.isNotBlank() || proxy.pass.isNotBlank()
                    ) {
                        " · requiere autenticación"
                    } else {
                        ""
                    }

                    "${proxy.label()} · ${proxy.type.uppercase()} · " +
                        "${if (proxy.enabled) "Activo" else "Desactivado"} · $state$auth"
                }
            }
    }

    private fun manageProxies() {
        val proxies = store.proxies()

        if (proxies.isEmpty()) {
            toast("No hay proxies que administrar.")
            return
        }

        val labels = proxies.map {
            "${it.label()} (${if (it.enabled) "Activo" else "Inactivo"})"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Administrar proxies")
            .setItems(labels) { _, index ->
                val proxy = proxies[index]

                AlertDialog.Builder(this)
                    .setTitle(proxy.label())
                    .setMessage(
                        "Tipo: ${proxy.type}\n" +
                            "Autenticación: ${if (proxy.user.isNotBlank()) "Sí" else "No"}\n" +
                            "Estado: ${proxy.detail.ifBlank { "Sin probar" }}"
                    )
                    .setPositiveButton(
                        if (proxy.enabled) "Desactivar" else "Activar"
                    ) { _, _ ->
                        store.updateProxy(proxy.id) {
                            it.copy(enabled = !it.enabled)
                        }
                        refreshProxyList()
                    }
                    .setNegativeButton("Eliminar") { _, _ ->
                        store.saveProxies(
                            store.proxies().filterNot { it.id == proxy.id }
                        )
                        refreshProxyList()
                    }
                    .setNeutralButton("Cerrar", null)
                    .show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun testProxyConnections() {
        val proxies = store.proxies()

        if (proxies.isEmpty()) {
            toast("Importa primero algún proxy.")
            return
        }

        val button = v<MaterialButton>("btnProxyTest")
        button.isEnabled = false
        button.text = "Probando conexiones…"

        Thread {
            var reachable = 0

            proxies.forEach { proxy ->
                val result = runCatching {
                    Socket().use { socket ->
                        socket.connect(
                            InetSocketAddress(proxy.host, proxy.port),
                            4000
                        )
                    }
                }

                val ok = result.isSuccess
                if (ok) reachable++

                store.updateProxy(proxy.id) {
                    it.copy(
                        status = if (ok) 1 else 2,
                        detail = if (ok) {
                            "Conexión TCP accesible"
                        } else {
                            "No conecta: ${
                                result.exceptionOrNull()?.javaClass?.simpleName ?: "Error"
                            }"
                        },
                        testedAt = System.currentTimeMillis()
                    )
                }
            }

            runOnUiThread {
                button.isEnabled = true
                button.text = "Probar conexiones"
                refreshProxyList()
                toast("Conexiones TCP accesibles: $reachable de ${proxies.size}")
            }
        }.start()
    }

    private fun refreshAll() {
        if (!::adapter.isInitialized) return
        refreshQueue()
        refreshAlertas()
        v<TextView>("tvStatsFull").text = Stats.compute(store.requests(), catalog).toText()
    }
}
