package es.citaasistente.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import es.citaasistente.app.R
import es.citaasistente.app.alerts.Notifier
import es.citaasistente.app.data.Catalog
import es.citaasistente.app.data.Nationalities
import es.citaasistente.app.data.Request
import es.citaasistente.app.data.Status
import es.citaasistente.app.data.Store
import es.citaasistente.app.net.Ev
import es.citaasistente.app.net.Reporter
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature

/**
 * Ejecutor de la cola de solicitudes. Procesa de una en una dentro de la sede electrónica oficial:
 * rellena cada paso y SE DETIENE en el captcha y en el código SMS (los resuelves tú). Cada solicitud
 * deja su historial (qué se hizo y cuándo) y avisa por Telegram/correo según tus ajustes.
 *
 * Respeta a la sede: una sola sesión a la vez, separación mínima entre solicitudes, pausas entre
 * lotes y esperas crecientes si la sede limita o rechaza peticiones. No usa proxies ni oculta nada.
 */
class AssistantActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var tvStatus: TextView
    private lateinit var tvDetail: TextView
    private lateinit var tvQueue: TextView
    private lateinit var btnPause: MaterialButton
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: Store
    private lateinit var catalog: Catalog
    private lateinit var nats: Nationalities

    private var engine = ""
    private var cfgJson = ""
    private var mode = MODE_QUEUE
    private var singleId: String? = null
    private var limit = 0
    private var attemptsInRun = 0
    private var batchCount = 0
    private val alertIds = ArrayDeque<String>()
    private val passSkip = HashSet<String>()
    private var currentId: String? = null
    private var paused = false
    private var finished = false
    private var lastKey = ""
    private var repeats = 0
    private var blocks = 0
    private var sysErrors = 0
    private var lastStartAt = 0L
    private var timer: Runnable? = null
    private var humanDeadline: Runnable? = null

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_assistant)
        active = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        store = Store.get(this)
        catalog = Catalog.load(this)
        nats = Nationalities.load(this)
        engine = assets.open("autofill.js").bufferedReader(Charsets.UTF_8).use { it.readText() }

        tvStatus = findViewById(R.id.tvStatus); tvDetail = findViewById(R.id.tvDetail); tvQueue = findViewById(R.id.tvQueue)
        btnPause = findViewById(R.id.btnPause); web = findViewById(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setSupportMultipleWindows(false)
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        CookieManager.getInstance().setAcceptCookie(true)
        web.addJavascriptInterface(Bridge(), "CitaBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isAllowed(request.url.scheme, request.url.host)) return false
                Toast.makeText(this@AssistantActivity, "Enlace externo bloqueado", Toast.LENGTH_SHORT).show()
                return true
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { if (currentId != null) tvDetail.text = "Cargando…" }
            override fun onPageFinished(view: WebView, url: String?) {
                // Los datos personales solo se entregan a páginas de la sede oficial.
                val uri = android.net.Uri.parse(url ?: return)
                if (currentId == null || paused || finished || !isAllowed(uri.scheme, uri.host)) return
                view.evaluateJavascript("$engine;window.__citaRun($cfgJson);", null)
            }
        }

        findViewById<MaterialButton>(R.id.btnSkip).setOnClickListener { skipCurrent() }
        btnPause.setOnClickListener { togglePause() }
        findViewById<MaterialButton>(R.id.btnClose).setOnClickListener { confirmExit() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmExit()
        })

        // Solicitudes que quedaron a medias en una ejecución anterior vuelven a la cola.
        store.requests().filter { it.status == Status.EN_PROCESO || it.status == Status.ESPERANDO_HUMANO }.forEach {
            store.updateRequest(it.id) { r -> r.copy(status = Status.PENDIENTE) }
            Reporter.log(this, it, "sistema", "Reanudada tras una interrupción")
        }
        readIntent(intent, first = true)
        if (screenOnForAlert()) wakeScreen()
        nextRequest()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(EXTRA_MODE) == MODE_ALERT) {
            readIntent(intent, first = false)
            if (currentId == null && !finished) { cancelTimer(); nextRequest() }
        } else if (currentId != null) {
            Toast.makeText(this, "Ya hay un proceso en marcha", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readIntent(i: Intent, first: Boolean) {
        val m = i.getStringExtra(EXTRA_MODE) ?: MODE_QUEUE
        if (first) {
            mode = m
            if (m == MODE_SINGLE) singleId = i.getStringExtra(EXTRA_REQUEST_ID)
            limit = i.getIntExtra(EXTRA_LIMIT, 0)
        }
        if (m == MODE_ALERT) i.getStringArrayListExtra(EXTRA_IDS)?.forEach { if (it !in alertIds) alertIds.addLast(it) }
    }

    private fun screenOnForAlert(): Boolean = intent.getStringExtra(EXTRA_MODE) == MODE_ALERT && store.settings.screenOnOnAlert

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }

    private fun isAllowed(scheme: String?, host: String?): Boolean =
        scheme == "https" && host != null &&
            (host == "administracionelectronica.gob.es" || host.endsWith(".administracionelectronica.gob.es") ||
                host == "sede.administracionespublicas.gob.es")

    // ---------- Selección de la siguiente solicitud ----------
    private fun nextRequest() {
        cancelTimer()
        if (finished || paused) return
        val settings = store.settings
        val pending = store.requests().filter { it.status == Status.PENDIENTE }

        var next: Request? = null
        if (mode == MODE_SINGLE) {
            if (attemptsInRun == 0) next = pending.firstOrNull { it.id == singleId }
        } else {
            while (alertIds.isNotEmpty() && next == null) {
                val id = alertIds.removeFirst()
                next = pending.firstOrNull { it.id == id }
            }
            if (next == null && mode == MODE_QUEUE) next = pending.firstOrNull { it.groupKey() !in passSkip }
        }

        if (next == null) {
            when {
                mode != MODE_QUEUE || pending.isEmpty() ->
                    finishRun(if (pending.isEmpty() && mode == MODE_QUEUE) "Cola vacía" else "Terminado",
                        if (pending.isEmpty()) "No quedan solicitudes pendientes." else "Fin del proceso.")
                else -> { // todas las pendientes ya se probaron en esta pasada: esperar y repetir
                    passSkip.clear()
                    countdown("Pasada completa sin citas", settings.retryMinutes.coerceAtLeast(3) * 60_000L) { nextRequest() }
                }
            }
            return
        }
        if (limit > 0 && attemptsInRun >= limit) { finishRun("Lote completado", "Se procesaron $attemptsInRun solicitud(es)."); return }
        if (mode == MODE_QUEUE && alertIds.isEmpty() && batchCount >= settings.batchSize) {
            batchCount = 0
            if (settings.batchPauseMinutes > 0) {
                countdown("Lote de ${settings.batchSize} completado", settings.batchPauseMinutes * 60_000L) { nextRequest() }
                return
            }
        }
        val gap = settings.minGapSeconds.coerceAtLeast(30) * 1000L - (System.currentTimeMillis() - lastStartAt)
        if (lastStartAt != 0L && gap > 0) {
            val r: Request = next
            countdown("Preparando la siguiente solicitud", gap) { start(r) }
            return
        }
        start(next)
    }

    private fun start(req: Request) {
        val p = req.profile
        val problems = p.problems(catalog, nats)
        val prov = catalog.province(p.provinceCode)
        val proc = prov?.procedure(p.procedureId)
        if (problems.isNotEmpty() || prov == null || proc == null) {
            val failed = store.updateRequest(req.id) { it.copy(status = Status.FALLIDA, failReason = problems.firstOrNull() ?: "Datos incompletos") }
            Reporter.log(this, failed, "fallo", failed?.failReason.orEmpty())
            Reporter.notify(this, Ev.FALLIDA, failed, failed?.failReason.orEmpty())
            outcome(500); return
        }
        currentId = req.id; lastStartAt = System.currentTimeMillis(); attemptsInRun++; batchCount++
        sysErrors = 0; lastKey = ""; repeats = 0
        val u = store.updateRequest(req.id) { it.copy(status = Status.EN_PROCESO, attempts = it.attempts + 1, failReason = "", slot = "") }
        Reporter.log(this, u, "inicio", "Intento ${u?.attempts}")
        if (u != null && (u.attempts == 1 || mode != MODE_QUEUE)) Reporter.notify(this, Ev.INICIO, u, "Autorrelleno en marcha.")
        cfgJson = JSONObject()
            .put("docType", p.docType).put("docNumber", p.docNumber).put("fullName", p.fullName)
            .put("countryCode", p.nationalityCode).put("countryLabel", p.nationalityLabel)
            .put("phone", p.phone).put("email", p.email)
            .put("province", JSONObject().put("code", prov.code).put("name", prov.name))
            .put("procedureId", proc.id).put("procedureName", proc.name)
            .put("offices", JSONArray(p.offices)).put("minDate", p.minDate)
            .put("autoConsent", store.settings.autoConsent).toString()
        updateQueueLine()
        applyProxyForRequest()
    }

    private fun applyProxyForRequest() {
        val settings = store.settings

        if (!settings.proxyEnabled) {
            clearProxyOverride {
                if (!isFinishing && currentId != null) loadStart()
            }
            return
        }

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            stopForProxyError(
                "La versión instalada de WebView no admite la configuración de proxies."
            )
            return
        }

        val proxy = store.nextUsableProxy()

        if (proxy == null) {
            stopForProxyError(
                "No hay proxies habilitados y compatibles. " +
                    "Se requieren proxies sin usuario ni contraseña."
            )
            return
        }


        val scheme = when (proxy.type.lowercase()) {
            "http" -> "http"
            "https" -> "https"
            else -> {
                stopForProxyError("Tipo de proxy no compatible: ${proxy.type}")
                return
            }
        }


        val config = ProxyConfig.Builder()
            .addProxyRule("$scheme://${proxy.host}:${proxy.port}")
            .build()

        runCatching {
            ProxyController.getInstance().setProxyOverride(
                config,
                ContextCompat.getMainExecutor(this)
            ) {
                if (!isFinishing && currentId != null) {
                    loadStart()
                }
            }
        }.onFailure {
            stopForProxyError(
                "No se pudo aplicar el proxy: ${it.message ?: "Error desconocido"}"
            )
        }
    }

    private fun clearProxyOverride(after: (() -> Unit)? = null) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            after?.invoke()
            return
        }

        runCatching {
            ProxyController.getInstance().clearProxyOverride(
                ContextCompat.getMainExecutor(this)
            ) {
                after?.invoke()
            }
        }.onFailure {
            after?.invoke()
        }
    }

    private fun stopForProxyError(reason: String) {
        val id = currentId

        if (id != null) {
            val updated = store.updateRequest(id) {
                it.copy(status = Status.PENDIENTE)
            }

            Reporter.log(this, updated, "proxy", reason)
        }

        currentId = null
        finishRun("Proxy no disponible", reason)
    }

    private fun loadStart() {
        cancelTimer()
        lastKey = ""; repeats = 0
        val cm = CookieManager.getInstance()
        cm.removeAllCookies { web.loadUrl(START_URL) } // sesión limpia en cada intento
        cm.flush()
        val r = currentId?.let { store.request(it) }
        setStatus("Abriendo la sede electrónica…", r?.displayName().orEmpty())
    }

    // ---------- Resultado de cada paso (llega desde autofill.js) ----------
    private fun handle(json: String) {
        val id = currentId ?: return
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val step = o.optString("step"); val status = o.optString("status"); val detail = o.optString("detail")
        val key = "$step/$status"
        repeats = if (key == lastKey) repeats + 1 else 0
        lastKey = key
        val r = store.request(id) ?: return
        val group = r.groupKey()

        if (status == "filled" && repeats >= 3) {
            fail(r, "Paso repetido en ${stepName(step)}: la sede devolvió la misma página (¿dato incorrecto?).")
            return
        }
        when (status) {
            "filled" -> {
                setStatus("Rellenando: ${stepName(step)}", detail)
                Reporter.log(this, r, "paso", "${stepName(step)} ✔${if (detail.isNotBlank()) " ($detail)" else ""}")
            }
            "captcha_needed" -> {
                val slot = detail.removePrefix("Elegida: ").substringBefore(". Escribe")
                val u = store.updateRequest(id) { it.copy(status = Status.ESPERANDO_HUMANO, slot = slot) }
                Reporter.log(this, u, "cita", "Cita encontrada: $slot")
                Reporter.log(this, u, "humano", "Captcha pendiente")
                Reporter.notify(this, Ev.HUMANO, u, "Resuelve el captcha. Cita encontrada: $slot")
                humanNeeded("¡Resuelve el captcha!", "${r.displayName()} · $slot")
                armHumanDeadline()
            }
            "sms_needed" -> {
                val u = store.updateRequest(id) { it.copy(status = Status.ESPERANDO_HUMANO) }
                Reporter.log(this, u, "humano", "Captcha superado. Código SMS pendiente")
                Reporter.notify(this, Ev.HUMANO, u, "Captcha superado. Introduce el código SMS y confirma.")
                humanNeeded("¡Introduce el código SMS!", detail)
                armHumanDeadline()
            }
            "manual_calendar" -> {
                val u = store.updateRequest(id) { it.copy(status = Status.ESPERANDO_HUMANO) }
                Reporter.log(this, u, "humano", "Elegir día y hora en el calendario")
                Reporter.notify(this, Ev.HUMANO, u, detail)
                humanNeeded("Elige día y hora", detail)
                armHumanDeadline()
            }
            "confirmed" -> {
                cancelHuman(); blocks = 0
                val ref = Regex("(?i)(?:justificante|localizador|c[oó]digo)\\D{0,25}([A-Z0-9]{6,12})").find(detail)?.groupValues?.get(1).orEmpty()
                val u = store.updateRequest(id) { it.copy(status = Status.CON_CITA, result = detail, reference = ref) }
                Reporter.log(this, u, "confirmada", "CITA CONFIRMADA${if (ref.isNotBlank()) " · $ref" else ""}${if (!u?.slot.isNullOrBlank()) " · ${u?.slot}" else ""}")
                Reporter.notify(this, Ev.CON_CITA, u, detail.take(500))
                humanNeeded("¡Cita confirmada!", "${r.displayName()} · ${u?.slot.orEmpty()}")
                outcome(5000)
            }
            "no_slots" -> {
                blocks = 0
                val u = store.updateRequest(id) { it.copy(status = Status.PENDIENTE) }
                Reporter.log(this, u, "sin citas", "Sin citas (intento ${u?.attempts})")
                passSkip.add(group)
                outcome()
            }
            "no_slot_in_range" -> {
                val u = store.updateRequest(id) { it.copy(status = Status.PENDIENTE) }
                Reporter.log(this, u, "fuera de rango", detail)
                Reporter.notify(this, Ev.INFO, u, detail)
                passSkip.add(group)
                outcome()
            }
            "no_office_match" -> {
                val u = store.updateRequest(id) { it.copy(status = Status.PENDIENTE) }
                Reporter.log(this, u, "sedes", detail)
                passSkip.add(group)
                outcome()
            }
            "session_expired" -> {
                Reporter.log(this, r, "sistema", "Sesión caducada; reinicio del intento")
                if (++sysErrors > 3) { store.updateRequest(id) { it.copy(status = Status.PENDIENTE) }; outcome() }
                else schedule(2000) { loadStart() }
            }
            "system_error" -> {
                Reporter.log(this, r, "sistema", "Error del sistema de la sede")
                if (++sysErrors > 3) { store.updateRequest(id) { it.copy(status = Status.PENDIENTE) }; passSkip.add(group); outcome(30_000) }
                else { setStatus("Error de la sede", "Reintento en 30 s"); schedule(30_000) { loadStart() } }
            }
            "blocked" -> {
                // La sede limita o rechaza peticiones: se respeta con esperas crecientes (no se evade).
                blocks++
                val wait = BLOCK_WAITS[minOf(blocks - 1, BLOCK_WAITS.size - 1)]
                val u = store.updateRequest(id) { it.copy(status = Status.PENDIENTE) }
                Reporter.log(this, u, "bloqueo", "La sede rechazó la petición; pausa de ${wait / 60} min")
                Reporter.notify(this, Ev.INFO, u, "La sede limitó las peticiones. Pausa de ${wait / 60} min antes de continuar.")
                currentId = null
                countdown("Pausa por limitación de la sede", wait * 1000L) { nextRequest() }
            }
            "province_not_found", "procedure_not_found", "country_not_found", "fill_failed" -> fail(r, detail)
            else -> { // unknown, error...
                val u = store.updateRequest(id) { it.copy(status = Status.ESPERANDO_HUMANO) }
                Reporter.log(this, u, "humano", "Necesita ayuda: $detail")
                humanNeeded("Necesito tu ayuda: ${stepName(step)}", detail, urgent = false)
                armHumanDeadline()
            }
        }
    }

    private fun fail(r: Request, reason: String) {
        cancelHuman()
        val u = store.updateRequest(r.id) { it.copy(status = Status.FALLIDA, failReason = reason) }
        Reporter.log(this, u, "fallo", reason)
        Reporter.notify(this, Ev.FALLIDA, u, reason)
        humanNeeded("Solicitud fallida", "${r.displayName()}: $reason", urgent = false)
        outcome(3000)
    }

    /** Tras cada resultado: pasar a la siguiente (o terminar si era una sola solicitud). */
    private fun outcome(delayMs: Long = 2000) {
        currentId = null
        cancelHuman()
        updateQueueLine()
        if (mode == MODE_SINGLE && alertIds.isEmpty()) {
            val r = singleId?.let { store.request(it) }
            finishRun("Terminado: ${r?.status?.label ?: ""}", r?.displayName().orEmpty())
        } else schedule(delayMs) { nextRequest() }
    }

    private fun finishRun(title: String, detail: String) {
        finished = true
        cancelTimer(); cancelHuman()
        clearProxyOverride()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setStatus(title, detail)
        updateQueueLine()
    }

    // ---------- Temporizadores ----------
    private fun schedule(ms: Long, f: () -> Unit) {
        cancelTimer()
        val r = Runnable { timer = null; f() }
        timer = r
        handler.postDelayed(r, ms)
    }

    private fun countdown(title: String, ms: Long, then: () -> Unit) {
        cancelTimer()
        val end = System.currentTimeMillis() + ms
        val r = object : Runnable {
            override fun run() {
                val left = (end - System.currentTimeMillis()) / 1000
                if (left <= 0) { timer = null; then(); return }
                setStatus(title, "Continúa en ${left / 60}:${"%02d".format(left % 60)}")
                updateQueueLine()
                handler.postDelayed(this, 1000)
            }
        }
        timer = r
        handler.post(r)
    }

    private fun cancelTimer() { timer?.let { handler.removeCallbacks(it) }; timer = null }

    /** Si no resuelves el captcha/SMS a tiempo, la solicitud vuelve a la cola y se sigue con la siguiente. */
    private fun armHumanDeadline() {
        cancelHuman()
        val minutes = store.settings.captchaTimeoutMinutes
        val r = Runnable {
            val id = currentId ?: return@Runnable
            val u = store.updateRequest(id) { it.copy(status = Status.PENDIENTE, slot = "") }
            Reporter.log(this, u, "tiempo", "Sin respuesta en $minutes min; vuelve a la cola")
            Reporter.notify(this, Ev.INFO, u, "No se completó el captcha/SMS en $minutes min; vuelve a la cola.")
            outcome()
        }
        humanDeadline = r
        handler.postDelayed(r, minutes * 60_000L)
    }

    private fun cancelHuman() { humanDeadline?.let { handler.removeCallbacks(it) }; humanDeadline = null }

    // ---------- Controles ----------
    private fun skipCurrent() {
        val id = currentId
        if (id == null) { cancelTimer(); nextRequest(); return }
        val u = store.updateRequest(id) { it.copy(status = Status.PENDIENTE, slot = "") }
        Reporter.log(this, u, "manual", "Saltada manualmente")
        u?.let { passSkip.add(it.groupKey()) }
        outcome(500)
    }

    private fun togglePause() {
        paused = !paused
        btnPause.text = if (paused) "Reanudar" else "Pausar"
        if (paused) { cancelTimer(); setStatus("En pausa", "Puedes seguir a mano en esta misma pantalla.") }
        else if (currentId != null) { setStatus("Reanudando…", ""); web.reload() } else nextRequest()
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("¿Salir del asistente?")
            .setMessage("Se detiene el procesamiento. La solicitud en curso vuelve a la cola.")
            .setPositiveButton("Salir") { _, _ -> finish() }
            .setNegativeButton("Seguir", null).show()
    }

    // ---------- UI ----------
    private fun setStatus(title: String, detail: String) { tvStatus.text = title; tvDetail.text = detail }

    private fun updateQueueLine() {
        val all = store.requests()
        val cur = currentId?.let { store.request(it) }
        tvQueue.text = (cur?.let { "Solicitud: ${it.displayName()} · intento ${it.attempts} · " } ?: "") +
            "Pendientes: ${all.count { it.status == Status.PENDIENTE }} · Con cita: ${all.count { it.status == Status.CON_CITA }}" +
            if (mode == MODE_QUEUE) " · Lote ${batchCount}/${store.settings.batchSize}" else ""
    }

    private fun buzz() {
        val v = getSystemService(Vibrator::class.java)
        v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 250, 500, 250, 800), -1))
    }

    private fun humanNeeded(title: String, detail: String, urgent: Boolean = true) {
        setStatus(title, detail)
        updateQueueLine()
        Notifier.assistant(this, title, detail, urgent)
        if (urgent) buzz()
    }

    private fun stepName(step: String) = when (step) {
        "province" -> "provincia"; "tramite" -> "trámite"; "entrar" -> "entrar"
        "identity" -> "datos personales"; "identity_confirm" -> "confirmar datos"
        "office" -> "sede"; "contact" -> "contacto"; "slots" -> "cita"; else -> step
    }

    inner class Bridge {
        @JavascriptInterface
        fun report(json: String) { runOnUiThread { handle(json) } }
    }

    override fun onDestroy() {
        cancelTimer(); cancelHuman()
        currentId?.let {
            val u = store.updateRequest(it) { r -> r.copy(status = Status.PENDIENTE) }
            Reporter.log(this, u, "sistema", "Interrumpida al cerrar el asistente; vuelve a la cola")
        }
        active = false
        clearProxyOverride()
        if (::web.isInitialized) { web.removeJavascriptInterface("CitaBridge"); web.destroy() }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_IDS = "ids"
        const val EXTRA_LIMIT = "limit"
        const val MODE_SINGLE = "SINGLE"   // una solicitud concreta (manual)
        const val MODE_QUEUE = "QUEUE"     // toda la cola, de una en una, en lotes
        const val MODE_ALERT = "ALERT"     // solicitudes que coinciden con una alerta
        const val START_URL = "https://icp.administracionelectronica.gob.es/icpco/citar"
        private val BLOCK_WAITS = longArrayOf(60, 120, 300, 600, 900)
        @Volatile var active = false
    }
}
