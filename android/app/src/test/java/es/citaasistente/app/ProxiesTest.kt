package es.citaasistente.app

import es.citaasistente.app.data.ProxyParser
import org.junit.Assert.*
import org.junit.Test

class ProxiesTest {

    @Test
    fun proxyHostPuertoValido() {
        val proxy = ProxyParser.parseLine("192.168.1.10:8080")

        assertNotNull(proxy)
        assertEquals("192.168.1.10", proxy?.host)
        assertEquals(8080, proxy?.port)
        assertEquals("http", proxy?.type)
        assertEquals("", proxy?.user)
        assertEquals("", proxy?.pass)
    }

    @Test
    fun proxyConCredencialesValido() {
        val proxy = ProxyParser.parseLine(
            "192.168.1.10:8080:usuario:clave"
        )

        assertNotNull(proxy)
        assertEquals("usuario", proxy?.user)
        assertEquals("clave", proxy?.pass)
    }

    @Test
    fun proxyConEsquemaYCredencialesValido() {
        val proxy = ProxyParser.parseLine(
            "socks5h://usuario:clave@proxy.example.com:1080"
        )

        assertNotNull(proxy)
        assertEquals("proxy.example.com", proxy?.host)
        assertEquals(1080, proxy?.port)
        assertEquals("usuario", proxy?.user)
        assertEquals("clave", proxy?.pass)
        assertEquals("socks5", proxy?.type)
    }

    @Test
    fun aceptaDosPuntosDentroDeLaClave() {
        val proxy = ProxyParser.parseLine(
            "http://usuario:clave:adicional@proxy.example.com:8080"
        )

        assertNotNull(proxy)
        assertEquals("usuario", proxy?.user)
        assertEquals("clave:adicional", proxy?.pass)
    }

    @Test
    fun esquemaHttpsSeConserva() {
        val proxy = ProxyParser.parseLine(
            "https://proxy.example.com:443"
        )

        assertNotNull(proxy)
        assertEquals("https", proxy?.type)
        assertEquals(443, proxy?.port)
    }

    @Test
    fun rechazaCredencialesIncompletas() {
        assertNull(
            ProxyParser.parseLine(
                "http://usuario@proxy.example.com:8080"
            )
        )
        assertNull(
            ProxyParser.parseLine(
                "http://:clave@proxy.example.com:8080"
            )
        )
        assertNull(
            ProxyParser.parseLine(
                "http://usuario:@proxy.example.com:8080"
            )
        )
        assertNull(
            ProxyParser.parseLine(
                "proxy.example.com:8080:usuario:"
            )
        )
        assertNull(
            ProxyParser.parseLine(
                "proxy.example.com:8080::clave"
            )
        )
    }

    @Test
    fun rechazaSeparadoresDeCredencialesMalformados() {
        assertNull(
            ProxyParser.parseLine(
                "usuario:clave@@proxy.example.com:8080"
            )
        )
        assertNull(
            ProxyParser.parseLine(
                "usuario:clave@proxy@example.com:8080"
            )
        )
    }

    @Test
    fun rechazaHostsMalformados() {
        assertNull(
            ProxyParser.parseLine("proxy.example.com/ruta:8080")
        )
        assertNull(
            ProxyParser.parseLine("proxy.example.com\\ruta:8080")
        )
        assertNull(
            ProxyParser.parseLine("proxy example.com:8080")
        )
    }

    @Test
    fun rechazaEsquemasNoSoportados() {
        assertNull(
            ProxyParser.parseLine("ftp://proxy.example.com:21")
        )
    }

    @Test
    fun rechazaProxyConPuertoFueraDeRango() {
        assertNull(ProxyParser.parseLine("proxy.example.com:0"))
        assertNull(ProxyParser.parseLine("proxy.example.com:65536"))
    }

    @Test
    fun rechazaProxyConFormatoInvalido() {
        assertNull(ProxyParser.parseLine("proxy.example.com"))
        assertNull(ProxyParser.parseLine("proxy.example.com:abc"))
        assertNull(ProxyParser.parseLine(""))
        assertNull(ProxyParser.parseLine("# comentario"))
    }

    @Test
    fun parseManyEliminaDuplicadosYCuentaInvalidos() {
        val input = """
            proxy.example.com:8080
            proxy.example.com:8080
            proxy2.example.com:3128
            formato-invalido
            # comentario
        """.trimIndent()

        val (proxies, invalid) = ProxyParser.parseMany(input)

        assertEquals(2, proxies.size)
        assertEquals(1, invalid)
    }

    @Test
    fun parseManyNoDuplicaProxiesExistentes() {
        val existing = listOf(
            ProxyParser.parseLine("proxy.example.com:8080")!!
        )

        val (proxies, invalid) = ProxyParser.parseMany(
            "proxy.example.com:8080\nproxy2.example.com:3128",
            existing
        )

        assertEquals(1, proxies.size)
        assertEquals("proxy2.example.com", proxies[0].host)
        assertEquals(0, invalid)
    }
}