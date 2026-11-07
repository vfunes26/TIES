package es.citaasistente.app

import es.citaasistente.app.data.BackupCodec
import es.citaasistente.app.data.HistoryReport
import es.citaasistente.app.data.Ledger
import es.citaasistente.app.data.Profile
import es.citaasistente.app.data.Request
import es.citaasistente.app.data.Retention
import es.citaasistente.app.data.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupAndRetentionTest {

    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_800_000_000_000L

    private fun req(
        id: String,
        status: Status = Status.PENDIENTE,
        createdDaysAgo: Int = 0,
        archivedDaysAgo: Int = -1,
        failReason: String = ""
    ) = Request(
        id = id,
        createdAt = now - createdDaysAgo * day,
        profile = Profile(fullName = "Persona $id", docNumber = "X1234567L"),
        status = status,
        updatedAt = now - createdDaysAgo * day,
        failReason = failReason,
        archivedAt = if (archivedDaysAgo >= 0) now - archivedDaysAgo * day else 0L
    )

    // ---------------- Retención ----------------

    @Test
    fun pendienteDeMenosDe30DiasSeMantieneActiva() {
        val r = Retention.apply(listOf(req("a", createdDaysAgo = 29)), now)
        assertEquals(1, r.list.size)
        assertEquals(Status.PENDIENTE, r.list[0].status)
        assertEquals(0, r.expired)
    }

    @Test
    fun pendienteDe30DiasPasaAlHistorialComoVencida() {
        val r = Retention.apply(listOf(req("a", createdDaysAgo = 30)), now)
        assertEquals(1, r.expired)
        val x = r.list[0]
        assertEquals(Status.CANCELADA, x.status)
        assertTrue(x.archivedAt > 0L)
        assertTrue(Retention.isExpired(x))
    }

    @Test
    fun fallidaTambienVence() {
        val r = Retention.apply(listOf(req("a", Status.FALLIDA, createdDaysAgo = 45)), now)
        assertEquals(1, r.expired)
    }

    @Test
    fun enProcesoNuncaVence() {
        val r = Retention.apply(listOf(req("a", Status.EN_PROCESO, createdDaysAgo = 90)), now)
        assertEquals(0, r.expired)
        assertEquals(Status.EN_PROCESO, r.list[0].status)
    }

    @Test
    fun historialSeBorraALos30Dias() {
        val list = listOf(
            req("viejo", Status.CON_CITA, createdDaysAgo = 50, archivedDaysAgo = 30),
            req("reciente", Status.CON_CITA, createdDaysAgo = 50, archivedDaysAgo = 29)
        )
        val r = Retention.apply(list, now)
        assertEquals(1, r.purged)
        assertEquals(listOf("reciente"), r.list.map { it.id })
    }

    @Test
    fun cerradasDeVersionesAnterioresEntranAlHistorialConFechaDeHoy() {
        val r = Retention.apply(listOf(req("a", Status.CON_CITA, createdDaysAgo = 200)), now)
        assertEquals(1, r.migrated)
        assertEquals(0, r.purged)
        assertEquals(now, r.list[0].archivedAt)
    }

    @Test
    fun diasRestantesDelHistorial() {
        assertEquals(30, Retention.daysLeft(req("a", Status.CON_CITA, archivedDaysAgo = 0), now))
        assertEquals(20, Retention.daysLeft(req("a", Status.CON_CITA, archivedDaysAgo = 10), now))
        assertEquals(0, Retention.daysLeft(req("a", Status.CON_CITA, archivedDaysAgo = 40), now))
    }

    @Test
    fun sinCambiosNoMarcaCambio() {
        val r = Retention.apply(listOf(req("a", createdDaysAgo = 2)), now)
        assertFalse(r.changed)
    }

    // ---------------- Contadores por mes ----------------

    @Test
    fun contadoresSumanPorMes() {
        var raw = "{}"
        raw = Ledger.bump(raw, "2026-11", Ledger.ADDED)
        raw = Ledger.bump(raw, "2026-11", Ledger.ADDED)
        raw = Ledger.bump(raw, "2026-11", Ledger.OK)
        raw = Ledger.bump(raw, "2026-10", Ledger.CANCELLED, 3)

        val rows = Ledger.rows(raw)
        assertEquals(listOf("2026-11", "2026-10"), rows.map { it.month })
        assertEquals(2, rows[0].added)
        assertEquals(1, rows[0].ok)
        assertEquals(3, rows[1].cancelled)
    }

    @Test
    fun mergeSeQuedaConElMayorValor() {
        val a = Ledger.bump(Ledger.bump("{}", "2026-11", Ledger.ADDED, 5), "2026-11", Ledger.OK, 1)
        val b = Ledger.bump("{}", "2026-11", Ledger.ADDED, 3)
        val merged = Ledger.rows(Ledger.merge(a, b))[0]
        assertEquals(5, merged.added)
        assertEquals(1, merged.ok)
    }

    @Test
    fun mergeIncluyeMesesDeAmbos() {
        val a = Ledger.bump("{}", "2026-09", Ledger.ADDED)
        val b = Ledger.bump("{}", "2026-10", Ledger.ADDED)
        assertEquals(2, Ledger.rows(Ledger.merge(a, b)).size)
    }

    @Test
    fun seedCuentaLoQueYaExiste() {
        val list = listOf(
            req("a", Status.CON_CITA, createdDaysAgo = 1, archivedDaysAgo = 0),
            req("b", Status.CANCELADA, createdDaysAgo = 1, archivedDaysAgo = 0),
            req("c", Status.CANCELADA, createdDaysAgo = 1, archivedDaysAgo = 0, failReason = "Vencida: 30 días sin procesarse"),
            req("d", Status.FALLIDA, createdDaysAgo = 1)
        )
        val total = Ledger.rows(Ledger.seed(list))
        assertEquals(4, total.sumOf { it.added })
        assertEquals(1, total.sumOf { it.ok })
        assertEquals(1, total.sumOf { it.cancelled })
        assertEquals(1, total.sumOf { it.expired })
        assertEquals(1, total.sumOf { it.failed })
    }

    @Test
    fun textoSinDatos() {
        assertEquals("Sin datos todavía.", Ledger.toText("{}"))
    }

    @Test
    fun jsonDanadoNoRompe() {
        assertEquals(0, Ledger.rows("esto no es json").size)
        assertEquals(1, Ledger.rows(Ledger.bump("esto no es json", "2026-11", Ledger.OK)).size)
    }

    // ---------------- Cifrado del respaldo ----------------

    @Test
    fun cifradoIdaYVuelta() {
        val plain = """{"requests":[],"x":"ñandú ✔"}""".toByteArray(Charsets.UTF_8)
        val enc = BackupCodec.encrypt(plain, "mi frase segura".toCharArray())
        assertFalse(String(enc, Charsets.ISO_8859_1).contains("ñandú"))
        val dec = BackupCodec.decrypt(enc, "mi frase segura".toCharArray())
        assertEquals(String(plain, Charsets.UTF_8), String(dec, Charsets.UTF_8))
    }

    @Test
    fun fraseIncorrectaSeRechaza() {
        val enc = BackupCodec.encrypt("hola".toByteArray(), "frase-correcta".toCharArray())
        try {
            BackupCodec.decrypt(enc, "frase-equivocada".toCharArray())
            fail("Debía rechazar la frase")
        } catch (e: BackupCodec.WrongPassphraseException) {
            // correcto
        }
    }

    @Test
    fun archivoQueNoEsRespaldoSeRechaza() {
        try {
            BackupCodec.decrypt("esto no es un respaldo de TIES para nada".toByteArray(), "abcdef".toCharArray())
            fail("Debía rechazar el archivo")
        } catch (e: BackupCodec.NotABackupException) {
            // correcto
        }
    }

    @Test
    fun archivoAlteradoSeRechaza() {
        val enc = BackupCodec.encrypt("datos importantes".toByteArray(), "frase-correcta".toCharArray())
        enc[enc.size - 3] = (enc[enc.size - 3].toInt() xor 0x55).toByte()
        try {
            BackupCodec.decrypt(enc, "frase-correcta".toCharArray())
            fail("Debía detectar la alteración")
        } catch (e: BackupCodec.WrongPassphraseException) {
            // correcto
        }
    }

    // ---------------- Informe de historial ----------------

    @Test
    fun informeDeHistorialCuentaPorTipo() {
        val list = listOf(
            req("a", Status.CON_CITA, archivedDaysAgo = 1),
            req("b", Status.CANCELADA, archivedDaysAgo = 2),
            req("c", Status.CANCELADA, archivedDaysAgo = 3, failReason = "Vencida: 30 días sin procesarse")
        )
        val text = HistoryReport.text(list, { "Madrid · Huellas" }, now)
        assertTrue(text.contains("Registros: 3 · Con cita: 1 · Canceladas: 1 · Vencidas: 1"))
        assertTrue(text.contains("VENCIDA"))
        assertTrue(text.contains("CON CITA"))
    }
}
