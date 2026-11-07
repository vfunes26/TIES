package es.citaasistente.app

import es.citaasistente.app.data.ProxyEntry
import es.citaasistente.app.data.ProxyParser
import es.citaasistente.app.data.ProxyRules
import es.citaasistente.app.net.ProxyTester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRulesTest {

    private fun p(
        id: String,
        enabled: Boolean = true,
        type: String = "http"
    ) = ProxyEntry(
        id = id,
        host = "host-$id.example.com",
        port = 8080,
        user = "usuario",
        pass = "clave",
        type = type,
        enabled = enabled
    )

    @Test
    fun elLimiteEsCincuenta() {
        assertEquals(50, ProxyRules.MAX_PROXIES)
        assertEquals(50, ProxyRules.room(0))
        assertEquals(1, ProxyRules.room(49))
        assertEquals(0, ProxyRules.room(50))
        assertEquals(0, ProxyRules.room(80))
    }

    @Test
    fun unProxyConUsuarioYClaveEsUtilizable() {
        assertTrue(ProxyRules.isUsable(p("a")))
    }

    @Test
    fun noUsaDesactivadosNiTiposNoSoportados() {
        val all = listOf(p("a"), p("b", enabled = false), p("c", type = "socks5"))
        assertEquals(listOf("a"), ProxyRules.candidates(all, 0).map { it.id })
    }

    @Test
    fun candidatosEnOrdenCircularDesdeElCursor() {
        val all = listOf(p("a"), p("b"), p("c"))
        assertEquals(listOf("b", "c", "a"), ProxyRules.candidates(all, 1).map { it.id })
    }

    @Test
    fun cursorMayorQueLaListaDaLaVuelta() {
        val all = listOf(p("a"), p("b"), p("c"))
        assertEquals(listOf("b", "c", "a"), ProxyRules.candidates(all, 4).map { it.id })
    }

    @Test
    fun excluyeLosYaProbados() {
        val all = listOf(p("a"), p("b"), p("c"))
        val result = ProxyRules.candidates(all, 0, setOf("a", "c"))
        assertEquals(listOf("b"), result.map { it.id })
    }

    @Test
    fun sinProxiesNoHayCandidatos() {
        assertTrue(ProxyRules.candidates(emptyList(), 0).isEmpty())
    }

    @Test
    fun siguienteCursorAvanzaYDaLaVuelta() {
        val all = listOf(p("a"), p("b"), p("c"))
        assertEquals(1, ProxyRules.nextCursor(all, all[0]))
        assertEquals(2, ProxyRules.nextCursor(all, all[1]))
        assertEquals(0, ProxyRules.nextCursor(all, all[2]))
    }

    @Test
    fun fromFieldsAceptaArrobaYDosPuntosEnLaClave() {
        val proxy = ProxyParser.fromFields("https", "203.0.113.5", "8080", "usuario", "cl@ve:rara")
        assertNotNull(proxy)
        assertEquals("203.0.113.5", proxy?.host)
        assertEquals(8080, proxy?.port)
        assertEquals("usuario", proxy?.user)
        assertEquals("cl@ve:rara", proxy?.pass)
        assertEquals("https", proxy?.type)
    }

    @Test
    fun fromFieldsSinCredencialesEsValido() {
        val proxy = ProxyParser.fromFields("http", "203.0.113.5", "3128", "", "")
        assertNotNull(proxy)
        assertEquals("", proxy?.user)
    }

    @Test
    fun fromFieldsRechazaDatosInvalidos() {
        assertNull(ProxyParser.fromFields("http", "203.0.113.5", "8080", "usuario", ""))
        assertNull(ProxyParser.fromFields("http", "203.0.113.5:8080", "8080", "", ""))
        assertNull(ProxyParser.fromFields("http", "http://203.0.113.5", "8080", "", ""))
        assertNull(ProxyParser.fromFields("http", "203.0.113.5", "99999", "", ""))
        assertNull(ProxyParser.fromFields("http", "203.0.113.5", "abc", "", ""))
        assertNull(ProxyParser.fromFields("socks5", "203.0.113.5", "1080", "", ""))
        assertNull(ProxyParser.fromFields("http", "", "8080", "", ""))
    }

    @Test
    fun interpretaIpwhoIs() {
        val r = ProxyTester.parse("""{"ip":"88.1.2.3","success":true,"country_code":"ES"}""")
        assertEquals(Pair("88.1.2.3", "ES"), r)
    }

    @Test
    fun interpretaIpapiEnMinusculas() {
        val r = ProxyTester.parse("""{"ip":"88.1.2.3","country_code":"es"}""")
        assertEquals(Pair("88.1.2.3", "ES"), r)
    }

    @Test
    fun interpretaIpifySinPais() {
        val r = ProxyTester.parse("""{"ip":"5.6.7.8"}""")
        assertEquals(Pair("5.6.7.8", ""), r)
    }

    @Test
    fun rechazaRespuestasInvalidas() {
        assertNull(ProxyTester.parse("""{"success":false,"message":"limit"}"""))
        assertNull(ProxyTester.parse("""{"error":true,"reason":"RateLimited"}"""))
        assertNull(ProxyTester.parse("<html>proxy error</html>"))
        assertNull(ProxyTester.parse("""{"otra":"cosa"}"""))
    }

    @Test
    fun estadoSegunPais() {
        assertEquals(1, ProxyTester.Result(true, "1.1.1.1", "ES").status)
        assertEquals(1, ProxyTester.Result(true, "1.1.1.1", "").status)
        assertEquals(3, ProxyTester.Result(true, "1.1.1.1", "PE").status)
        assertEquals(2, ProxyTester.Result(false, detail = "x").status)
        assertTrue(ProxyTester.Result(true, "1.1.1.1", "es").isSpain)
        assertFalse(ProxyTester.Result(true, "1.1.1.1", "PE").isSpain)
    }
}
