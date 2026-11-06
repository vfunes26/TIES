package es.citaasistente.app

import es.citaasistente.app.data.Catalog
import es.citaasistente.app.data.Nationalities
import es.citaasistente.app.data.Nif
import es.citaasistente.app.data.Profile
import es.citaasistente.app.data.Request
import es.citaasistente.app.data.Settings
import es.citaasistente.app.data.Stats
import es.citaasistente.app.data.Status
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class QueueModelTest {
    // Los tests se ejecutan con el directorio de trabajo = android/app
    private val catalog: Catalog by lazy { Catalog.parse(File("../../shared/catalog.json").readText(Charsets.UTF_8)) }
    private val nats: Nationalities by lazy { Nationalities.parse(File("../../shared/nationalities.json").readText(Charsets.UTF_8)) }

    private fun okProfile() = Profile(
        docType = "NIE", docNumber = "X1234567L", fullName = "Ana Prueba",
        nationalityCode = "242", nationalityLabel = "PERU", phone = "600123123", email = "ana@example.com",
        provinceCode = "08", procedureId = "4010", offices = emptyList(), minDate = "2026-11-06",
    )

    @Test fun catalogoCompleto() {
        assertEquals(52, catalog.provinces.size)
        assertEquals("Barcelona", catalog.findProvince("08")?.name)
        assertNull(catalog.findProvince("Barcelon"))   // estricto: no adivina
        assertNotNull(catalog.province("08")?.procedure("4010"))
    }

    @Test fun nacionalidadesSoloExactas() {
        assertEquals(204, nats.list.size)
        assertEquals("242", nats.byLabelExact("Perú")?.value)
        assertNull(nats.byLabelExact("GUINEA"))       // ambigua: GUINEA ECUATORIAL / REPUBLICA / -BISSAU
        assertNull(nats.byLabelExact("Peruu"))
    }

    @Test fun perfilValidoYPerfilIncompleto() {
        assertTrue(okProfile().problems(catalog, nats).isEmpty())
        assertTrue(Profile().problems(catalog, nats).size >= 5)
    }

    @Test fun nacionalidadLibreEsRechazada() {
        val p = okProfile().copy(nationalityLabel = "Peru")  // no coincide con el texto oficial guardado
        assertTrue(p.step1Problems(nats).any { it.contains("nacionalidad") })
    }

    @Test fun solicitudSeSerializaIdaYVuelta() {
        val r = Request(profile = okProfile(), status = Status.CON_CITA, attempts = 7, slot = "17/12/2026 09:50", reference = "ABC123XY")
        val back = Request.fromJson(org.json.JSONObject(r.toJson().toString()))
        assertEquals(r.id, back.id); assertEquals(Status.CON_CITA, back.status)
        assertEquals("PERU", back.profile.nationalityLabel); assertEquals("17/12/2026 09:50", back.slot)
        assertEquals("08|4010", back.groupKey())
    }

    @Test fun estadisticas() {
        val list = listOf(
            Request(profile = okProfile(), status = Status.CON_CITA, attempts = 4),
            Request(profile = okProfile(), status = Status.PENDIENTE, attempts = 2),
            Request(profile = okProfile(), status = Status.FALLIDA, attempts = 1),
            Request(profile = okProfile(), status = Status.CANCELADA),
        )
        val s = Stats.compute(list, catalog)
        assertEquals(4, s.total); assertEquals(1, s.conCita); assertEquals(1, s.pendientes)
        assertEquals(7, s.intentos); assertEquals(33, s.tasaExito)   // 1 de 3 (la cancelada no cuenta)
        assertEquals(4.0, s.intentosPorExito, 0.001)
    }

    @Test fun ajustesRespetanLimitesDeCortesia() {
        val s = Settings.fromJson(org.json.JSONObject("""{"retryMinutes":1,"minGapSeconds":5,"batchSize":999}"""))
        assertEquals(3, s.retryMinutes); assertEquals(30, s.minGapSeconds); assertEquals(50, s.batchSize)
    }

    @Test fun telegramYCorreoSoloListosConTodosLosDatos() {
        assertFalse(Settings(telegramEnabled = true).telegramReady())
        assertTrue(Settings(telegramEnabled = true, telegramToken = "1:x", telegramChatId = "1").telegramReady())
        assertFalse(Settings(mailEnabled = true, smtpUser = "a@b.c").mailReady())
    }

    @Test fun documentoEnmascarado() { assertEquals("***67L", Nif.mask("X1234567L")) }
}
