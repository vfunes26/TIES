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
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceError
import es.citaasistente.app.data.ProxyEntry
import es.citaasistente.app.net.ProxySession

/**
 * Ejecutor de la cola de solicitudes. Procesa de una en una dentro de la sede electrónica oficial:
 * rellena cada paso y SE DETIENE en el captcha y en el código SMS (los resuelves tú). Cada solicitud
 * deja su historial (qué se hizo y cuándo) y avisa por Telegram/correo según tus ajustes.
 *
 * Respeta a la sede: una sola sesión a la vez, separación mínima entre solicitudes, pausas entre
 * lotes y esperas crecientes si la sede limita o rechaza peticiones. Si activas proxies, usa un proxy por lote: al completar cada lote (Ajustes → Procesamiento → solicitudes por lote) cambia al siguiente proxy de la lista.
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
// --- Proxy: el WebView pide usuario y contraseña del proxy (HTTP 407) ---
            override fun onReceivedHttpAuthRequest(view: WebView, auth: HttpAuthHandler, host: String?, realm: String?) {
                val px = proxyInUse
                val h = host.orEmpty().substringBefore(":")
                // Solo se entrega la clave al PROXY, nunca a la web de la sede.
                val isProxy = px != null && (h.equals(px.host, ignoreCase = true) || !isAllowed("https", h))
                if (px == null || !isProxy || px.user.isBlank()) {
                    auth.cancel()
                    return
                }
                if (++proxyAuthTries > 2) {
                    auth.cancel()
                    proxyFailed("El proxy rechazó el usuario o la contraseña")
                    return
                }
                auth.proceed(px.user, px.pass)
            }

            // --- Proxy: si la página principal no carga con el proxy, se prueba el siguiente ---
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame || proxyInUse == null || currentId == null || finished) return
                pageError = true
                proxyFailed("${error.description} (código ${error.errorCode})")
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { if (currentId != null) tvDetail.text = "Cargando…" }
            override fun onPageFinished(view: WebView, url: String?) {
                cancelLoadWatchdog()
                proxyAuthTries = 0
                if (proxyBusy) return
                if (pageError) { pageError = false; return }
                if (proxyInUse != null) { proxyRotations = 0; proxyTried.clear() }
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
        // Si el acceso se revocó o se cerró la sesión, la cola se detiene aquí (entre una solicitud y la siguiente).
        if (!finished && !es.citaasistente.app.data.Session.isLoggedIn(this)) {
            finishRun("Sesión cerrada", "El acceso fue revocado o cerrado. Inicia sesión de nuevo para continuar.")
            return
        }
        cancelTimer()
        if (finished || paused) return
        val settings = store.settings
        val allPending = store.requests().filter { it.status == Status.PENDIENTE }
        // Límites del portal: se salta a quien ya gastó sus intentos de hoy o está en espera de seguridad.
        val pending = allPending.filter {
            es.citaasistente.app.data.AttemptStore.allows(this, it.profile.docNumber, ignoreCooldown = mode == MODE_SINGLE)
        }
        val gated = allPending.size - pending.size

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
                gated > 0 && pending.isEmpty() -> waitForPortalLimits(allPending)
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
            // Lote completo: se libera el proxy. El siguiente lote arranca con el proxy siguiente de la lista.
            endProxySession()
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

    /** Todas las pendientes están limitadas por el portal: espera de seguridad corta, o fin hasta las 00:00. */
    private fun waitForPortalLimits(blocked: List<Request>) {
        val wait = es.citaasistente.app.data.AttemptStore.waitMillis(this, blocked.map { it.profile.docNumber })

        if (mode == MODE_QUEUE && wait in 1L..(60L * 60_000L)) {
            countdown("Espera de seguridad del portal", wait + 5_000L) { nextRequest() }
        } else {
            finishRun(
                "Intentos del portal agotados",
                "Se alcanzó el límite diario de intentos del portal. Se puede reanudar después de las 00:00 (hora de España)."
            )
        }
    }

    private fun start(req: Request) {
        val p = req.profile
        val problems = p.problems(catalog, nats)
        val prov = catalog.province(p.provinceCode.ifBlank { es.citaasistente.app.data.AnyProvince.pick(catalog, p.procedureId, req.attempts) })
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

    // =====================================================================
    //  PROXY HTTP/HTTPS (IP + PUERTO + USUARIO + CONTRASEÑA)
    //  Se usa UN solo proxy durante toda la cola y solo cambia si falla.
    //  Al terminar la cola se libera y la app vuelve a la conexión normal.
    // =====================================================================
    private var proxyInUse: ProxyEntry? = null
    private var proxyBusy = false
    private var proxyAuthTries = 0
    private var proxyRotations = 0
    private var pageError = false
    private val proxyTried = HashSet<String>()
    private var loadWatchdog: Runnable? = null

    private fun applyProxyForRequest() {
        if (!store.settings.proxyEnabled) {
            endProxySession()
            clearProxyOverride {
                if (!isFinishing && currentId != null) loadStart()
            }
            return
        }

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            proxyAbort("Tu WebView no admite proxies. Actualiza «Android System WebView» en Google Play.")
            return
        }

        if (proxyInUse != null) {
            loadStart()
            return
        }

        chooseProxy()
    }

    /** Prueba los proxies (usuario, clave e IP) y aplica el primero que funcione. */
    private fun chooseProxy() {
        if (proxyBusy) return
        proxyBusy = true
        setStatus("Comprobando el proxy…", "Se verifica usuario, contraseña e IP antes de abrir la sede.")

        ProxySession.acquire(this, HashSet(proxyTried)) { out ->
            proxyBusy = false
            if (isFinishing || isDestroyed || finished || currentId == null) return@acquire

            val px = out.entry
            if (px == null) {
                proxyAbort(out.report)
                return@acquire
            }

            proxyInUse = px
            proxyAuthTries = 0
            ProxySession.begin(px)

            val r = out.result
            val ipInfo = if (r != null) "IP ${r.ip} · ${r.country.ifBlank { "país ?" }}" else ""
            val req = currentId?.let { store.request(it) }
            Reporter.log(this, req, "proxy", "Proxy activo ${px.label()} ($ipInfo)")
            Reporter.notify(
                this, Ev.INFO, req,
                "🌐 Proxy activo: ${px.label()} · $ipInfo" +
                    if (out.report.isNotBlank()) "\n⚠️ ${out.report}" else ""
            )

            try {
                val cfg = ProxyConfig.Builder()
                    .addProxyRule("http://${px.host}:${px.port}")
                    .build()
                ProxyController.getInstance().setProxyOverride(cfg, ContextCompat.getMainExecutor(this)) {
                    if (!isFinishing && currentId != null) loadStart()
                }
            } catch (e: Exception) {
                proxyAbort("No se pudo aplicar el proxy: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** El proxy en uso falló (conexión, tiempo o clave): se descarta y se prueba el siguiente. */
    private fun proxyFailed(reason: String) {
        val px = proxyInUse ?: return
        if (finished || currentId == null) return

        cancelLoadWatchdog()
        web.stopLoading()
        proxyTried.add(px.id)
        es.citaasistente.app.net.ProxyHealth.onFail(store, px, reason)
        store.updateProxy(px.id) {
            it.copy(status = 2, detail = reason.take(120), testedAt = System.currentTimeMillis())
        }
        Reporter.log(this, currentId?.let { store.request(it) }, "proxy", "Proxy ${px.label()} falló: $reason")

        proxyInUse = null
        ProxySession.end()
        proxyRotations++

        if (proxyRotations > 5) {
            proxyAbort("Se agotaron los proxies disponibles. Último error: $reason")
            return
        }

        setStatus("Cambiando de proxy…", reason.take(100))
        chooseProxy()
    }

    /** No se puede continuar con proxy: avisa por Telegram y detiene la cola (la solicitud vuelve a pendiente). */
    private fun proxyAbort(reason: String) {
        Reporter.notify(this, Ev.INFO, currentId?.let { store.request(it) }, "🌐 $reason")
        stopForProxyError(reason)
    }

    private fun armLoadWatchdog() {
        cancelLoadWatchdog()
        if (proxyInUse == null) return
        val r = Runnable {
            loadWatchdog = null
            proxyFailed("Tiempo de espera agotado abriendo la sede")
        }
        loadWatchdog = r
        handler.postDelayed(r, 60_000L)
    }

    private fun cancelLoadWatchdog() {
        loadWatchdog?.let { handler.removeCallbacks(it) }
        loadWatchdog = null
    }

    /** Fin de la cola: se libera el proxy y la app vuelve a la conexión normal (datos o WiFi). */
    private fun endProxySession() {
        cancelLoadWatchdog()
        val px = proxyInUse ?: return
        proxyInUse = null
        proxyTried.clear()
        proxyRotations = 0
        ProxySession.end()
        Reporter.log(this, null, "proxy", "Proxy ${px.label()} liberado: vuelve la conexión normal")
    }

    @Suppress("unused")
    private fun applyProxyForRequestLegacy() {
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
        cm.removeAllCookies { pageError = false; proxyAuthTries = 0; web.loadUrl(START_URL); armLoadWatchdog() } // sesión limpia en cada intento
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
        es.citaasistente.app.net.PortalGuard.onReport(this, r, step, status, detail)

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
                if (u != null) {
                    // Captura de la confirmación: se envía al master y, si la mandó un cliente, también a su chat.
                    val shot = es.citaasistente.app.net.ScreenShot.capture(web, java.io.File(cacheDir, "shots"), "cita-${u.id.take(8)}")
                    es.citaasistente.app.net.ClientNotify.photo(this, u, shot)
                }
                humanNeeded("¡Cita confirmada!", "${r.displayName()} · ${u?.slot.orEmpty()}")
                outcome(5000)
            }
            "nie_blocked" -> {
                blocks = 0
                val u = store.updateRequest(id) { it.copy(status = Status.PENDIENTE) }
                Reporter.log(this, u, "bloqueo", "NIE bloqueado por el portal hasta las 00:00 (hora de España)")
                passSkip.add(group)
                outcome()
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
                es.citaasistente.app.net.Diagnostics.unknownPage(this, r, step, status, detail, web.url)
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
        endProxySession()
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
        endProxySession()
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
