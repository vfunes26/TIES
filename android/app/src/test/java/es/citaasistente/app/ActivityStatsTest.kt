package es.citaasistente.app

import es.citaasistente.app.data.ActivityStats
import es.citaasistente.app.data.Profile
import es.citaasistente.app.data.Request
import es.citaasistente.app.data.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityStatsTest {

    private val hour = 60L * 60L * 1000L
    private val now = 1_800_000_000_000L
    private val since = now - 24 * hour

    private fun req(
        id: String,
        status: Status = Status.PENDIENTE,
        createdHoursAgo: Int = 100,
        updatedHoursAgo: Int = 100,
        archived: Boolean = false,
        failReason: String = ""
    ) = Request(
        id = id,
        createdAt = now - createdHoursAgo * hour,
        profile = Profile(fullName = "Persona $id"),
        status = status,
        updatedAt = now - updatedHoursAgo * hour,
        failReason = failReason,
        archivedAt = if (archived) now - updatedHoursAgo * hour else 0L
    )

    @Test
    fun cuentaSoloLoOcurridoDesdeElUltimoInforme() {
        val list = listOf(
            req("nueva", createdHoursAgo = 2, updatedHoursAgo = 2),
            req("vieja", createdHoursAgo = 100, updatedHoursAgo = 100),
            req("cita-hoy", Status.CON_CITA, updatedHoursAgo = 3, archived = true),
            req("cita-antigua", Status.CON_CITA, updatedHoursAgo = 90, archived = true),
            req("cancelada-hoy", Status.CANCELADA, updatedHoursAgo = 5, archived = true),
            req("vencida-hoy", Status.CANCELADA, updatedHoursAgo = 1, archived = true, failReason = "Vencida: 30 días sin procesarse")
        )

        val d = ActivityStats.compute(list, since)

        assertEquals(1, d.added)
        assertEquals(1, d.ok)
        assertEquals(1, d.cancelled)
        assertEquals(1, d.expired)
    }

    @Test
    fun activasSeCuentanPorEstadoYNoIncluyenElHistorial() {
        val list = listOf(
            req("p1", Status.PENDIENTE),
            req("p2", Status.PENDIENTE),
            req("e1", Status.EN_PROCESO),
            req("h1", Status.ESPERANDO_HUMANO),
            req("f1", Status.FALLIDA),
            req("c1", Status.CON_CITA, archived = true)
        )

        val d = ActivityStats.compute(list, since)

        assertEquals(5, d.active)
        assertEquals(2, d.pending)
        assertEquals(1, d.processing)
        assertEquals(1, d.waitingHuman)
        assertEquals(1, d.failedActive)
    }

    @Test
    fun ultimaActividadEsLaMasReciente() {
        val list = listOf(req("a", updatedHoursAgo = 50), req("b", updatedHoursAgo = 4), req("c", updatedHoursAgo = 20))
        assertEquals(now - 4 * hour, ActivityStats.compute(list, since).lastActivity)
    }

    @Test
    fun sinSolicitudesTodoEnCero() {
        val d = ActivityStats.compute(emptyList(), since)
        assertEquals(0, d.added)
        assertEquals(0, d.active)
        assertEquals(0L, d.lastActivity)
    }
}
