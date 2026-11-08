package es.citaasistente.app

import es.citaasistente.app.data.AnyProvince
import es.citaasistente.app.data.Catalog
import es.citaasistente.app.data.ClientRegistry
import es.citaasistente.app.data.Ids
import es.citaasistente.app.data.Office
import es.citaasistente.app.data.Procedure
import es.citaasistente.app.data.Province
import es.citaasistente.app.net.IntakeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntakeAndClientsTest {

    private val offices = listOf(
        Office("1", "CNP TUDELA, C/ Mayor 1"),
        Office("2", "CNP PAMPLONA, C/ Yanguas 5"),
        Office("3", "COMISARIA PROVINCIAL")
    )

    private val huellas = Procedure(
        "4010",
        "POLICÍA-TOMA DE HUELLAS (EXPEDICIÓN DE TARJETA) INICIAL, RENOVACIÓN, DUPLICADO Y LEY 14/2013",
        offices
    )
    private val otro = Procedure("4020", "POLICÍA-CERTIFICADOS Y ASIGNACIÓN DE NIE", emptyList())

    private val navarra = Province("31", "Navarra", listOf(huellas, otro))
    private val madrid = Province("28", "Madrid", listOf(huellas))
    private val catalog = Catalog(listOf(navarra, madrid), "test")

    // ---------------- Formato /NUEVA ----------------

    @Test
    fun variasSolicitudesConNuevaSeSeparan() {
        val text = "/NUEVA\nNIE: Y1234567X\nNOMBRE: A\n\n/NUEVA\nNIE: X7654321L\nNOMBRE: B"
        assertEquals(2, IntakeParser.splitBlocks(text).size)
    }

    @Test
    fun variasSolicitudesConLineaDeGuionesSeSeparan() {
        val text = "NIE: Y1234567X\nNOMBRE: A\n---\nNIE: X7654321L\nNOMBRE: B"
        assertEquals(2, IntakeParser.splitBlocks(text).size)
    }

    @Test
    fun reconoceQueUnMensajeEsUnaSolicitud() {
        assertTrue(IntakeParser.looksLikeRequest("/nueva"))
        assertTrue(IntakeParser.looksLikeRequest("NIE: Y1234567X"))
        assertFalse(IntakeParser.looksLikeRequest("hola, ¿cómo va?"))
    }

    // ---------------- Trámite ----------------

    @Test
    fun huellasInicialEquivaleATomaDeHuellas() {
        assertEquals("4010", IntakeParser.resolveProcedure(navarra, "HUELLAS_INICIAL").first?.id)
        assertEquals("4010", IntakeParser.resolveProcedure(navarra, "huellas inicial").first?.id)
        assertEquals("4010", IntakeParser.resolveProcedure(navarra, "").first?.id)
        assertEquals("4010", IntakeParser.resolveProcedure(navarra, null).first?.id)
    }

    @Test
    fun tramitePorCodigoYNoExistente() {
        assertEquals("4020", IntakeParser.resolveProcedure(navarra, "4020").first?.id)
        assertNull(IntakeParser.resolveProcedure(madrid, "4020").first)
        assertNull(IntakeParser.resolveProcedure(navarra, "inventado").first)
    }

    // ---------------- Oficina ----------------

    @Test
    fun oficinaPorNombreParcialSinTildesNiMayusculas() {
        assertEquals("CNP TUDELA, C/ Mayor 1", IntakeParser.matchOffice("TUDELA", offices).first)
        assertEquals("CNP TUDELA, C/ Mayor 1", IntakeParser.matchOffice("tudela", offices).first)
        assertEquals("COMISARIA PROVINCIAL", IntakeParser.matchOffice("Comisaría provincial", offices).first)
    }

    @Test
    fun oficinaAmbiguaDevuelveLasOpciones() {
        val r = IntakeParser.matchOffice("cnp", offices, "Navarra")
        assertNull(r.first)
        assertTrue(r.second.orEmpty().contains("ambigua"))
        assertTrue(r.second.orEmpty().contains("TUDELA"))
        assertTrue(r.second.orEmpty().contains("PAMPLONA"))
    }

    @Test
    fun oficinaInexistenteMuestraLasDisponibles() {
        val r = IntakeParser.matchOffice("zzz", offices, "Navarra")
        assertNull(r.first)
        assertTrue(r.second.orEmpty().contains("no reconocida"))
        assertTrue(r.second.orEmpty().contains("Navarra"))
    }

    // ---------------- Provincia vacía = cualquiera ----------------

    @Test
    fun provinciasCompatiblesConElTramite() {
        assertEquals(2, AnyProvince.provincesWith(catalog, "4010").size)
        assertEquals(1, AnyProvince.provincesWith(catalog, "4020").size)
        assertEquals(0, AnyProvince.provincesWith(catalog, "9999").size)
    }

    @Test
    fun laProvinciaRotaEnCadaIntento() {
        assertEquals("31", AnyProvince.pick(catalog, "4010", 0))
        assertEquals("28", AnyProvince.pick(catalog, "4010", 1))
        assertEquals("31", AnyProvince.pick(catalog, "4010", 2))
        assertEquals("", AnyProvince.pick(catalog, "9999", 0))
    }

    // ---------------- Identificador ----------------

    @Test
    fun identificadorCortoDeSeisCaracteres() {
        assertEquals("123E45", Ids.short("123e4567-e89b-12d3-a456-426614174000"))
    }

    // ---------------- Clientes autorizados ----------------

    @Test
    fun clientesSeAgreganYSeQuitan() {
        var raw = "[]"
        raw = ClientRegistry.add(raw, ClientRegistry.Client("111222333", "Ana", 1L))
        raw = ClientRegistry.add(raw, ClientRegistry.Client("444555666", "Luis", 2L))
        assertTrue(ClientRegistry.contains(raw, "111222333"))
        assertEquals(2, ClientRegistry.parse(raw).size)

        raw = ClientRegistry.remove(raw, "111222333")
        assertFalse(ClientRegistry.contains(raw, "111222333"))
        assertEquals(1, ClientRegistry.parse(raw).size)
    }

    @Test
    fun agregarDosVecesElMismoClienteNoLoDuplica() {
        var raw = ClientRegistry.add("[]", ClientRegistry.Client("111222333", "Ana", 1L))
        raw = ClientRegistry.add(raw, ClientRegistry.Client("111222333", "Ana María", 2L))
        val list = ClientRegistry.parse(raw)
        assertEquals(1, list.size)
        assertEquals("Ana María", list[0].name)
    }

    @Test
    fun jsonDanadoDeClientesNoRompe() {
        assertEquals(0, ClientRegistry.parse("esto no es json").size)
        assertNotNull(ClientRegistry.toRaw(emptyList()))
    }

    @Test
    fun validaIdsDeChatDeTelegram() {
        assertTrue(ClientRegistry.isChatId("123456789"))
        assertFalse(ClientRegistry.isChatId("abc"))
        assertFalse(ClientRegistry.isChatId("12"))
        assertFalse(ClientRegistry.isChatId("-100123456"))
    }
}
