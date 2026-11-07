package es.citaasistente.app.net

import es.citaasistente.app.data.ProxyEntry
import java.net.Authenticator
import java.net.Authenticator.RequestorType
import java.net.PasswordAuthentication
import java.util.concurrent.ConcurrentHashMap

/**
 * Credenciales de proxy para las conexiones de Java (Telegram y pruebas de proxy).
 * Solo responde cuando quien pide la clave es un PROXY registrado aquí; nunca a una web.
 * (El WebView autentica por su cuenta en AssistantActivity.)
 */
object ProxyAuth {

    private val creds = ConcurrentHashMap<String, PasswordAuthentication>()
    private val lock = Any()

    @Volatile
    private var installed = false

    private fun key(host: String, port: Int) = "${host.trim().lowercase()}:$port"

    fun register(p: ProxyEntry) {
        install()

        if (p.user.isBlank()) {
            creds.remove(key(p.host, p.port))
        } else {
            creds[key(p.host, p.port)] = PasswordAuthentication(p.user, p.pass.toCharArray())
        }
    }

    private fun install() {
        if (installed) return

        synchronized(lock) {
            if (installed) return

            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    if (requestorType != RequestorType.PROXY) return null

                    val port = requestingPort
                    val host = requestingHost.orEmpty().lowercase()

                    creds["$host:$port"]?.let { return it }

                    val ip = requestingSite?.hostAddress?.lowercase()
                    if (ip != null) {
                        creds["$ip:$port"]?.let { return it }
                    }

                    val samePort = creds.entries.filter { it.key.endsWith(":$port") }
                    return if (samePort.size == 1) samePort[0].value else null
                }
            })

            installed = true
        }
    }
}
