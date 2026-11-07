package es.citaasistente.app.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import es.citaasistente.app.data.ProxyEntry
import es.citaasistente.app.data.ProxyRules
import es.citaasistente.app.data.Store
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Proxy activo mientras dura una ejecución de la cola.
 *
 *  - acquire(): elige un proxy PROBÁNDOLO de verdad (usuario, contraseña e IP) antes de abrir la sede.
 *  - begin()/end(): marca el inicio y el fin del uso. Mientras está activo, Telegram y correo
 *    también salen por el proxy; al terminar la cola vuelve la conexión normal (datos o WiFi).
 */
object ProxySession {

    private const val MAX_PREFLIGHT = 6

    private val main by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var current: ProxyEntry? = null

    data class Outcome(
        val entry: ProxyEntry?,
        val result: ProxyTester.Result?,
        val report: String
    )

    fun active(): ProxyEntry? = current

    fun begin(p: ProxyEntry) {
        ProxyAuth.register(p)
        current = p
    }

    fun end() {
        current = null
    }

    /** Proxy HTTP de Java. Resuelve el host, por eso se usa fuera del hilo principal. */
    fun javaProxy(p: ProxyEntry): Proxy =
        Proxy(Proxy.Type.HTTP, InetSocketAddress(p.host, p.port))

    /** Busca un proxy que funcione. El resultado llega en el hilo principal. */
    fun acquire(ctx: Context, exclude: Set<String>, onDone: (Outcome) -> Unit) {
        val app = ctx.applicationContext

        Thread {
            val out = try {
                pick(Store.get(app), exclude)
            } catch (e: Exception) {
                Outcome(null, null, "Error interno al elegir proxy: ${e.javaClass.simpleName}")
            }

            main.post { onDone(out) }
        }.start()
    }

    private fun pick(store: Store, exclude: Set<String>): Outcome {
        val all = store.proxies()
        val candidates = ProxyRules.candidates(all, store.proxyCursor, exclude)

        if (candidates.isEmpty()) {
            return Outcome(
                null,
                null,
                if (exclude.isEmpty()) {
                    "No hay proxies activos. Agrega o activa al menos uno en Ajustes → Proxy."
                } else {
                    "Ya se probaron todos los proxies activos y ninguno funcionó."
                }
            )
        }

        val notes = ArrayList<String>()
        var fallback: Pair<ProxyEntry, ProxyTester.Result>? = null

        for (p in candidates.take(MAX_PREFLIGHT)) {
            val r = ProxyTester.test(p)

            store.updateProxy(p.id) {
                it.copy(
                    status = r.status,
                    detail = r.summary(),
                    testedAt = System.currentTimeMillis()
                )
            }

            if (!r.ok) {
                notes.add("${p.label()}: ${r.detail}")
                continue
            }

            // Se prefiere un proxy con IP de España; si el país no se pudo saber, se acepta.
            if (r.isSpain || r.country.isBlank()) {
                store.proxyCursor = ProxyRules.nextCursor(all, p)
                return Outcome(p, r, "")
            }

            if (fallback == null) fallback = Pair(p, r)
        }

        val fb = fallback

        if (fb != null) {
            store.proxyCursor = ProxyRules.nextCursor(all, fb.first)
            return Outcome(fb.first, fb.second, "La IP del proxy no es de España.")
        }

        return Outcome(
            null,
            null,
            "Ningún proxy respondió. " + notes.joinToString(" | ")
        )
    }
}
