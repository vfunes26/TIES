package es.citaasistente.app.net

import es.citaasistente.app.data.ProxyEntry
import es.citaasistente.app.data.ProxyRules
import es.citaasistente.app.data.Store

/** Salud de los proxies: tras 3 fallos seguidos un proxy se desactiva solo y se te avisa por Telegram. */
object ProxyHealth {

    private const val MAX_FAILS = 3

    fun onOk(store: Store, p: ProxyEntry) {
        store.proxyRecord(p.id, true)
    }

    fun onFail(store: Store, p: ProxyEntry, reason: String) {
        val fails = store.proxyRecord(p.id, false)
        if (fails < MAX_FAILS) return

        store.updateProxy(p.id) {
            it.copy(enabled = false, detail = "Desactivado tras $fails fallos seguidos: ${reason.take(80)}")
        }
        store.proxyRecord(p.id, true) // reinicia el contador por si se vuelve a activar

        val s = store.settings
        if (s.telegramReady()) {
            val left = store.proxies().count { ProxyRules.isUsable(it) }
            Telegram.send(
                s,
                "🔌 PROXY DESACTIVADO\n${p.label()}\nMotivo: $fails fallos seguidos (${reason.take(120)})\nProxies activos que quedan: $left"
            )
        }
    }
}
