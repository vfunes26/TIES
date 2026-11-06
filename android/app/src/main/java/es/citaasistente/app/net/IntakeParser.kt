package es.citaasistente.app.net

import es.citaasistente.app.data.Catalog
import es.citaasistente.app.data.Nationalities
import es.citaasistente.app.data.Nif
import es.citaasistente.app.data.Procedure
import es.citaasistente.app.data.Profile
import es.citaasistente.app.data.Province
import es.citaasistente.app.data.normalize
import java.time.LocalDate

/**
 * Convierte mensajes del chat autorizado en solicitudes.
 * Los valores de provincia, trámite, sede y nacionalidad se validan
 * contra el catálogo de la aplicación.
 */
object IntakeParser {

    data class Item(
        val profile: Profile?,
        val label: String,
        val errors: List<String>
    )

    val HELP: String = """
📋 FORMATO DE SOLICITUD

Documento: X1234567L
Nombre y apellido: Ana María Pérez
Nacionalidad: PERU
Telefono: 600123123
Correo: ana@correo.com
Provincia: Barcelona
Tramite: 4010
Sede: CNP RAMBLA GUIPUSCOA 74
Desde: 2026-12-01

Obligatorios:
Documento, Nombre y apellido, Nacionalidad,
Telefono, Correo y Provincia.

Nacionalidad: nombre del portal o código.
Tramite: código o nombre. Si se omite, se intenta usar Toma de huellas.
Sede: opcional. Varias sedes separadas por |.
Desde: AAAA-MM-DD o DD/MM/AAAA. Por defecto, hoy.
Varias personas: separar con una línea ---

Comandos:
/ayuda
/estado
/cola
/cancelar DOCUMENTO
    """.trimIndent()

    private val DOC_LINE =
        Regex("(?im)^\\s*(documento|doc|nie|dni|pasaporte)\\s*[:=]")

    fun looksLikeRequest(text: String): Boolean =
        DOC_LINE.containsMatchIn(text)

    private fun keyOf(nk: String): String? = when (nk) {
        "documento", "doc", "nie", "dni", "pasaporte",
        "numero de documento" -> "doc"

        "tipo", "tipo de documento" -> "tipo"
        "nombre y apellidos", "nombre y apellido",
        "nombre completo" -> "nombre"

        "nombre" -> "nombre1"
        "apellido", "apellidos" -> "apellidos"
        "nacionalidad" -> "nac"
        "telefono", "tel", "movil", "celular" -> "tel"
        "correo", "email", "e-mail", "mail",
        "correo electronico" -> "mail"

        "provincia" -> "prov"
        "tramite" -> "tram"
        "sede", "sedes", "oficina", "oficinas" -> "sede"
        "desde", "fecha", "fecha minima", "a partir de" -> "desde"
        else -> null
    }

    fun parse(
        text: String,
        catalog: Catalog,
        nats: Nationalities,
        today: String = LocalDate.now().toString()
    ): List<Item> {
        val chunks = text
            .split(Regex("(?m)^\\s*-{3,}\\s*$"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return chunks.take(50).map {
            parseChunk(it, catalog, nats, today)
        }
    }

    private fun parseChunk(
        chunk: String,
        catalog: Catalog,
        nats: Nationalities,
        today: String
    ): Item {
        val fields = HashMap<String, String>()
        var typeHint: String? = null

        for (line in chunk.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue

            var idx = t.indexOf(':')
            if (idx < 0) idx = t.indexOf('=')
            if (idx <= 0) continue

            val nk = normalize(t.substring(0, idx))
            val value = t.substring(idx + 1).trim()

            when (nk) {
                "nie" -> typeHint = "NIE"
                "dni" -> typeHint = "DNI"
                "pasaporte" -> typeHint = "PASAPORTE"
            }

            val key = keyOf(nk) ?: continue
            fields[key] = value
        }

        val errors = ArrayList<String>()

        val name = (
            fields["nombre"]
                ?: listOfNotNull(fields["nombre1"], fields["apellidos"])
                    .joinToString(" ")
            ).trim().replace(Regex("\\s+"), " ")

        val doc = Nif.clean(fields["doc"].orEmpty())
        val label = name.ifBlank { doc.ifBlank { "(sin nombre)" } }

        val docType = fields["tipo"]
            ?.uppercase()
            ?.takeIf { it in listOf("NIE", "DNI", "PASAPORTE") }
            ?: typeHint
            ?: Nif.guessType(doc)

        val natRaw = fields["nac"].orEmpty().trim()
        val nat = when {
            natRaw.isEmpty() -> null
            natRaw.all { it.isDigit() } -> nats.byCode(natRaw)
            else -> nats.byLabelExact(natRaw)
        }

        if (nat == null) {
            errors.add("Nacionalidad no válida: usa el nombre exacto o código del portal.")
        }

        val province: Province? =
            catalog.findProvince(fields["prov"].orEmpty())

        if (province == null) {
            errors.add("Provincia no reconocida: \"${fields["prov"].orEmpty()}\".")
        }

        var procedure: Procedure? = null
        val offices = ArrayList<String>()

        if (province != null) {
            val result = resolveProcedure(province, fields["tram"])
            procedure = result.first
            result.second?.let { errors.add(it) }

            val selectedProcedure = procedure
            if (selectedProcedure != null) {
                fields["sede"]
                    ?.split('|', ';')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.forEach { requested ->
                        val normalized = normalize(requested)
                        val hits = selectedProcedure.offices.filter {
                            normalize(it.name).contains(normalized)
                        }
                        val exact = hits.firstOrNull {
                            normalize(it.name) == normalized
                        }

                        when {
                            exact != null -> offices.add(exact.name)
                            hits.size == 1 -> offices.add(hits[0].name)
                            hits.isEmpty() -> errors.add("Sede no reconocida: \"$requested\".")
                            else -> errors.add("Sede ambigua: \"$requested\" (${hits.size} coincidencias).")
                        }
                    }
            }
        }

        val minDate = parseDate(fields["desde"], today)
        if (minDate == null) {
            errors.add("Fecha «Desde» no válida. Usa AAAA-MM-DD o DD/MM/AAAA.")
        }

        val proc = procedure
        if (errors.isNotEmpty() || nat == null || province == null ||
            proc == null || minDate == null
        ) {
            return Item(null, label, errors)
        }

        val profile = Profile(
            docType = docType,
            docNumber = doc,
            fullName = name,
            nationalityCode = nat.value,
            nationalityLabel = nat.label,
            phone = fields["tel"].orEmpty().replace(Regex("[\\s.-]"), ""),
            email = fields["mail"].orEmpty().trim().lowercase(),
            provinceCode = province.code,
            procedureId = proc.id,
            offices = offices,
            minDate = minDate
        )

        val problems = profile.problems(catalog, nats)
        return if (problems.isEmpty()) {
            Item(profile, label, emptyList())
        } else {
            Item(null, label, problems)
        }
    }

    private fun resolveProcedure(
        province: Province,
        value: String?
    ): Pair<Procedure?, String?> {
        if (value.isNullOrBlank()) {
            val hits = province.procedures.filter {
                normalize(it.name).startsWith("policia-toma de huellas")
            }
            return if (hits.size == 1) {
                Pair(hits[0], null)
            } else {
                Pair(null, "Indica el trámite (código) para ${province.name}.")
            }
        }

        val raw = value.trim()

        if (raw.all { it.isDigit() }) {
            val procedure = province.procedure(raw)
            return if (procedure != null) {
                Pair(procedure, null)
            } else {
                Pair(null, "El trámite $raw no existe en ${province.name}.")
            }
        }

        val normalized = normalize(raw)
        province.procedures.firstOrNull {
            normalize(it.name) == normalized
        }?.let { return Pair(it, null) }

        val words = normalized.split(' ').filter { it.length >= 3 }
        val hits = province.procedures.filter { procedure ->
            val name = normalize(procedure.name)
            words.isNotEmpty() && words.all { name.contains(it) }
        }

        return when {
            hits.size == 1 -> Pair(hits[0], null)
            hits.isEmpty() -> Pair(null, "Trámite no reconocido: \"$raw\".")
            else -> Pair(null, "Trámite ambiguo (${hits.size} coincidencias): usa el código.")
        }
    }

    private fun parseDate(value: String?, today: String): String? {
        if (value.isNullOrBlank()) return today

        val s = value.trim()
        val iso = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$").matchEntire(s)
        val dmy = Regex("^(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})$").matchEntire(s)

        val (year, month, day) = when {
            iso != null -> Triple(
                iso.groupValues[1],
                iso.groupValues[2],
                iso.groupValues[3]
            )
            dmy != null -> Triple(
                dmy.groupValues[3],
                dmy.groupValues[2],
                dmy.groupValues[1]
            )
            else -> return null
        }

        return try {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt()).toString()
        } catch (_: Exception) {
            null
        }
    }
}