package es.citaasistente.app

import es.citaasistente.app.data.AttemptBudget
import es.citaasistente.app.data.PortalNotice
import es.citaasistente.app.data.PortalNotice.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PortalAndAttemptsTest {

    // Textos tal como salen en las figuras de la sede.
    private val fig1 = "Información En este momento no hay citas disponibles para la reserva sin cl@ve. " +
        "SÍ TIENEN A SU DISPOSICIÓN MEDIANTE EL USO DE CL@VE, CITAS DISPONIBLES PARA SU RESERVA."
    private val fig2 = "INFORMACIÓN En este momento no hay citas disponibles. En breve, la Oficina pondrá a su disposición " +
        "nuevas citas. Si desea consultar o anular una cita, por favor rellene todos los campos obligatorios y pulse aceptar."
    private val fig3 = "Información En este momento no hay citas disponibles. En breve, la Oficina pondrá a su disposición nuevas citas."
    private val fig4 = "Información En este momento no hay citas disponibles. En breve, la Oficina pondrá a su disposición " +
        "nuevas citas. Cod. Oper.: 3967F050C6C042E5D10E09DC681B03E5mol3 27/10/2026 11:25:27"

    // ---------------- Interpretación de las figuras ----------------

    @Test
    fun figura1SinClave() {
        assertEquals(Kind.FIG1_SIN_CLAVE, PortalNotice.classify(fig1).kind)
    }

    @Test
    fun figura2AvisoInformativo() {
        assertEquals(Kind.FIG2_AVISO, PortalNotice.classify(fig2).kind)
    }

    @Test
    fun figura3SinCitasTrasSolicitar() {
        assertEquals(Kind.FIG3_SIN_CITAS, PortalNotice.classify(fig3).kind)
    }

    @Test
    fun figura4BloqueoConCodigoYHora() {
        val n = PortalNotice.classify(fig4)
        assertEquals(Kind.FIG4_BLOQUEO, n.kind)
        assertEquals("3967F050C6C042E5D10E09DC681B03E5mol3", n.code)
        assertEquals("27/10/2026 11:25:27", n.stamp)
    }

    @Test
    fun otroTextoNoSeInterpreta() {
        assertEquals(Kind.NONE, PortalNotice.classify("Seleccione la provincia y pulse aceptar").kind)
        assertEquals(Kind.NONE, PortalNotice.classify("").kind)
    }

    // ---------------- Intentos por NIE y día ----------------

    private val noche = Instant.parse("2026-11-08T22:30:00Z").toEpochMilli()   // 23:30 en España
    private val pasadaMedianoche = Instant.parse("2026-11-08T23:30:00Z").toEpochMilli() // 00:30 del día siguiente

    @Test
    fun elDiaSeCuentaConLaHoraDeEspana() {
        assertEquals("2026-11-08", AttemptBudget.today(noche))
        assertEquals("2026-11-09", AttemptBudget.today(pasadaMedianoche))
    }

    @Test
    fun faltanTreintaMinutosParaLaMedianoche() {
        assertEquals(30L * 60_000L, AttemptBudget.msToMidnight(noche))
    }

    @Test
    fun alLlegarACincoNoSePermiteMas() {
        var s = AttemptBudget.empty(noche)
        repeat(4) { s = AttemptBudget.add(s, "Y1234567X") }
        assertNull(AttemptBudget.reason(s, "Y1234567X", noche, false))

        s = AttemptBudget.add(s, "Y1234567X")
        assertEquals(5, AttemptBudget.used(s, "Y1234567X"))
        assertEquals("limit", AttemptBudget.reason(s, "Y1234567X", noche, false))
        assertEquals("limit", AttemptBudget.reason(s, "Y1234567X", noche, true))
    }

    @Test
    fun otroNieNoSeAfecta() {
        var s = AttemptBudget.empty(noche)
        s = AttemptBudget.block(s, "Y1234567X")
        assertNull(AttemptBudget.reason(s, "X7654321L", noche, false))
    }

    @Test
    fun laEsperaDeSeguridadSeIgnoraEnUnInicioManual() {
        var s = AttemptBudget.empty(noche)
        s = AttemptBudget.coolUntil(s, "Y1234567X", noche + 10 * 60_000L)
        assertEquals("cooldown", AttemptBudget.reason(s, "Y1234567X", noche, false))
        assertNull(AttemptBudget.reason(s, "Y1234567X", noche, true))
        assertNull(AttemptBudget.reason(s, "Y1234567X", noche + 11 * 60_000L, false))
    }

    @Test
    fun alCambiarElDiaTodoVuelveACero() {
        var s = AttemptBudget.empty(noche)
        s = AttemptBudget.block(s, "Y1234567X")
        val raw = AttemptBudget.toRaw(s)

        assertEquals(5, AttemptBudget.used(AttemptBudget.parse(raw, noche), "Y1234567X"))
        assertEquals(0, AttemptBudget.used(AttemptBudget.parse(raw, pasadaMedianoche), "Y1234567X"))
    }

    @Test
    fun jsonDanadoNoRompe() {
        assertEquals(0, AttemptBudget.used(AttemptBudget.parse("esto no es json", noche), "Y1234567X"))
    }

    @Test
    fun corregirManualmenteLosIntentosUsados() {
        var s = AttemptBudget.setUsed(AttemptBudget.empty(noche), "Y1234567X", 3)
        assertEquals(3, AttemptBudget.used(s, "Y1234567X"))
        s = AttemptBudget.setUsed(s, "Y1234567X", 9)
        assertEquals(5, AttemptBudget.used(s, "Y1234567X"))
    }

    @Test
    fun esperaHastaElProximoIntentoPosible() {
        var s = AttemptBudget.empty(noche)
        s = AttemptBudget.coolUntil(s, "A", noche + 20 * 60_000L)
        s = AttemptBudget.block(s, "B")

        assertEquals(20L * 60_000L, AttemptBudget.waitMillis(s, listOf("A", "B"), noche))
        assertEquals(30L * 60_000L, AttemptBudget.waitMillis(s, listOf("B"), noche))
        assertEquals(0L, AttemptBudget.waitMillis(s, listOf("C"), noche))
    }

    @Test
    fun tiempoLegible() {
        assertEquals("0 min", AttemptBudget.describe(0))
        assertEquals("1 min", AttemptBudget.describe(59_000))
        assertEquals("1 h 30 min", AttemptBudget.describe(90L * 60_000L))
        assertTrue(AttemptBudget.describe(25L * 60_000L).startsWith("25"))
    }
}
