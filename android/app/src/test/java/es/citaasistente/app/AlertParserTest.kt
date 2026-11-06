package es.citaasistente.app

import es.citaasistente.app.alerts.AlertParser
import es.citaasistente.app.alerts.Kind
import org.junit.Assert.*
import org.junit.Test

class AlertParserTest {
    private val provinces = listOf("Barcelona", "Madrid", "Valencia", "Alicante", "S.Cruz Tenerife")
    private val procName = "POLICÍA-TOMA DE HUELLAS (EXPEDICIÓN DE TARJETA) INICIAL, RENOVACIÓN, DUPLICADO Y LEY 14/2013"
    private val others = listOf("ASIGNACIÓN DE NIE", "RECOGIDA DE TARJETA DE IDENTIDAD DE EXTRANJERO (TIE)")

    private fun run(pkg: String, title: String?, text: String?, prov: String = "Barcelona") =
        AlertParser.interpret(pkg, title, text, prov, "4010", procName, provinces, others)

    @Test fun citaDisponibleConProvinciaYTramite() {
        val r = run(AlertParser.ALERTS_PKG, "Cita disponible", "Barcelona · Toma de huellas (expedición de tarjeta)")
        assertEquals(Kind.AVAILABLE, r.kind); assertEquals(true, r.province); assertEquals(true, r.procedure)
        assertTrue(AlertParser.shouldStart(r, strict = true))
    }

    @Test fun otraProvinciaNoLanzaAlarma() {
        val r = run(AlertParser.ALERTS_PKG, "Cita disponible", "Madrid · Toma de huellas")
        assertEquals(false, r.province)
        assertFalse(AlertParser.shouldStart(r, strict = false))
    }

    @Test fun sinProvinciaEnElTextoSoloArrancaEnModoNoEstricto() {
        val r = run(AlertParser.ALERTS_PKG, "Cita disponible", null)
        assertNull(r.province)
        assertTrue(AlertParser.shouldStart(r, strict = false))
        assertFalse(AlertParser.shouldStart(r, strict = true))
    }

    @Test fun otroTramiteDeLaMismaProvinciaSeDescarta() {
        val r = run(AlertParser.ALERTS_PKG, "Cita disponible", "Barcelona · Recogida de tarjeta de identidad de extranjero (TIE)")
        assertEquals(false, r.procedure)
        assertFalse(AlertParser.shouldStart(r, strict = false))
    }

    @Test fun sinCitasNoArranca() {
        val r = run(AlertParser.ALERTS_PKG, "Ya no hay citas", "Barcelona")
        assertEquals(Kind.NONE, r.kind)
        assertFalse(AlertParser.shouldStart(r, false))
    }

    @Test fun telegramSoloConEtiquetaDelBot() {
        val chat = run("org.telegram.messenger", "Amigo", "Tengo cita disponible mañana en el dentista")
        assertEquals(Kind.IGNORE, chat.kind)
        val bot = run("org.telegram.messenger", "Mi bot", "CITA_DISPONIBLE | Barcelona | $procName\n\nSeleccione cita")
        assertEquals(Kind.AVAILABLE, bot.kind); assertEquals(true, bot.province); assertEquals(true, bot.procedure)
    }

    @Test fun etiquetaSinCitasDelBot() {
        val bot = run("org.telegram.messenger", "Mi bot", "SIN_CITAS | Barcelona | $procName")
        assertEquals(Kind.NONE, bot.kind)
    }

    @Test fun nombreDeProvinciaNoCoincideDentroDeOtraPalabra() {
        assertTrue(AlertParser.containsWord("cita en madrid, centro", "madrid"))
        assertFalse(AlertParser.containsWord("cita en madridejos", "madrid"))
    }
}
