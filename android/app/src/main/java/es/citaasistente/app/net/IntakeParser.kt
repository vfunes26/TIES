package es.citaasistente.app.net

import es.citaasistente.app.data.Catalog
import es.citaasistente.app.data.Nationalities
import es.citaasistente.app.data.Nif
import es.citaasistente.app.data.Office
import es.citaasistente.app.data.Procedure
import es.citaasistente.app.data.Profile
import es.citaasistente.app.data.Province
import es.citaasistente.app.data.normalize
import java.time.LocalDate

/**
 * Convierte mensajes de Telegram (/NUEVA …) en solicitudes.
 * Provincia, trámite, oficina y nacionalidad se validan contra el catálogo de la aplicación.
 */
object IntakeParser {

    data class Item(
        val profile: Profile?,
        val label: String,
        val errors: List<String>,
        val notes: List<String> = emptyList()
    )

    val HELP: String = """
📋 FORMATO DE SOLICITUD

/NUEVA
NIE: Y1234567X
NOMBRE: JUAN PEREZ
NACIONALIDAD: NICARAGUA
TELEFONO: +50588888888
CORREO: ejemplo@correo.com
PROVINCIA: NAVARRA
OFICINA: TUDELA
TRAMITE: HUELLAS_INICIAL
FECHA_MINIMA: 2026-12-01

Obligatorios: NIE (o DOCUMENTO), NOMBRE, NACIONALIDAD, TELEFONO y CORREO.
• PROVINCIA vacía: se prueba cualquier provincia que ofrezca el trámite.
• OFICINA: nombre completo o parte (TUDELA). Varias: sepáralas con |. Solo con provincia.
• TRAMITE: HUELLAS_INICIAL = Toma de huellas inicial (también código o nombre). Vacío = huellas.
• FECHA_MINIMA vacía: se toma la primera cita disponible desde hoy (hora de España).
• Mayúsculas o minúsculas, da igual.
• Varias solicitudes: separa con una línea --- (o empieza cada una con /NUEVA).

Comandos: /ayuda /estado /cola /cancelar ID
    """.trimIndent()

    val HELP_CLIENT: String = HELP.replace(
        "Comandos: /ayuda /estado /cola /cancelar ID",
        "Comandos: /ayuda /estado (tus solicitudes) /cola /cancelar ID\nRecibirás aquí la confirmación y el resultado de cada solicitud."
    )

    private val DOC_LINE = Regex("(?im)^\\s*(documento|doc|nie|dni|pasaporte)\\s*[:=]")
    private val NEW_CMD = Regex("(?im)^[ \\t]*/nueva\\w*[ \\t]*")
    private val SEPARATOR = Regex("(?m)^\\s*-{3,}\\s*$")

    private val HUELLAS_ALIASES = setOf(
        "huellas", "huellas inicial", "toma de huellas", "toma de huellas inicial",
        "toma huellas inicial", "toma de huellas - inicial"
    )

    fun looksLikeRequest(text: String): Boolean =
        DOC_LINE.containsMatchIn(text) || NEW_CMD.containsMatchIn(text)

    /** Separa varias solicitudes: por líneas --- o por cada /NUEVA. */
    fun splitBlocks(text: String): List<String> =
        text.replace(NEW_CMD, "---\n")
            .split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun keyOf(nk: String): String? = when (nk) {
        "nie" -> "nie"
        "dni" -> "dni"
        "pasaporte" -> "pas"
        "documento", "doc", "numero de documento" -> "docum"
        "tipo", "tipo de documento" -> "tipo"
        "nombre y apellidos", "nombre y apellido", "nombre completo" -> "nombre"
        "nombre" -> "nombre1"
        "apellido", "apellidos" -> "apellidos"
        "nacionalidad" -> "nac"
        "telefono", "tel", "movil", "celular" -> "tel"
        "correo", "email", "e-mail", "mail", "correo electronico" -> "mail"
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
    ): List<Item> =
        splitBlocks(text).take(50).map { parseChunk(it, catalog, nats, today) }

    private fun parseChunk(
        chunk: String,
        catalog: Catalog,
        nats: Nationalities,
        today: String
    ): Item {
        val fields = HashMap<String, String>()

        for (line in chunk.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue

            var idx = t.indexOf(':')
            if (idx < 0) idx = t.indexOf('=')
            if (idx <= 0) continue

            val nk = normalize(t.substring(0, idx).replace('_', ' '))
            val value = t.substring(idx + 1).trim()
            val key = keyOf(nk) ?: continue
            fields[key] = value
        }

        val errors = ArrayList<String>()
        val notes = ArrayList<String>()

        val name = (
            fields["nombre"]
                ?: listOfNotNull(fields["nombre1"], fields["apellidos"]).joinToString(" ")
            ).trim().replace(Regex("\\s+"), " ")

        // Documento: NIE > DNI > PASAPORTE > DOCUMENTO.
        val nie = fields["nie"].orEmpty().trim()
        val dni = fields["dni"].orEmpty().trim()
        val pas = fields["pas"].orEmpty().trim()
        val docum = fields["docum"].orEmpty().trim()

        val docRaw: String
        val typeHint: String?
        when {
            nie.isNotEmpty() -> { docRaw = nie; typeHint = "NIE" }
            dni.isNotEmpty() -> { docRaw = dni; typeHint = "DNI" }
            pas.isNotEmpty() -> { docRaw = pas; typeHint = "PASAPORTE" }
            else -> { docRaw = docum; typeHint = null }
        }

        val doc = Nif.clean(docRaw)
        if (docum.isNotEmpty() && Nif.clean(docum) != doc) {
            notes.add("DOCUMENTO «${Nif.mask(Nif.clean(docum))}» no se usa: la solicitud va con el ${typeHint ?: "documento"} indicado.")
        }

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

        // Provincia: vacía = cualquier provincia que ofrezca el trámite.
        val provRaw = fields["prov"].orEmpty().trim()
        val anyProvince = provRaw.isEmpty()
        val province: Province? = if (anyProvince) null else catalog.findProvince(provRaw)
        if (!anyProvince && province == null) {
            errors.add("Provincia no reconocida: \"$provRaw\".")
        }

        var procedure: Procedure? = null
        val offices = ArrayList<String>()
        val officeRequests = fields["sede"].orEmpty()
            .split('|', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (anyProvince) {
            if (officeRequests.isNotEmpty()) {
                errors.add("Con la provincia vacía no se puede indicar oficina: indica también la provincia.")
            }

            val hits = catalog.provinces.mapNotNull { pv ->
                resolveProcedure(pv, fields["tram"]).first
            }
            val ids = hits.map { it.id }.distinct()

            when {
                ids.size == 1 -> procedure = hits.first()
                ids.isEmpty() -> errors.add("Trámite no reconocido: \"${fields["tram"].orEmpty()}\".")
                else -> errors.add("El trámite tiene códigos distintos según la provincia: indica la provincia.")
            }
        } else if (province != null) {
            val result = resolveProcedure(province, fields["tram"])
            procedure = result.first
            result.second?.let { errors.add(it) }

            val selected = procedure
            if (selected != null) {
                for (requested in officeRequests) {
                    val m = matchOffice(requested, selected.offices, province.name)
                    if (m.first != null) offices.add(m.first!!) else errors.add(m.second.orEmpty())
                }
            }
        }

        val minDate = parseDate(fields["desde"], today)
        if (minDate == null) {
            errors.add("Fecha mínima no válida. Usa AAAA-MM-DD o DD/MM/AAAA.")
        }

        val proc = procedure
        if (errors.isNotEmpty() || nat == null || proc == null || minDate == null ||
            (!anyProvince && province == null)
        ) {
            return Item(null, label, errors, notes)
        }

        val profile = Profile(
            docType = docType,
            docNumber = doc,
            fullName = name,
            nationalityCode = nat.value,
            nationalityLabel = nat.label,
            phone = fields["tel"].orEmpty().replace(Regex("[\\s.-]"), ""),
            email = fields["mail"].orEmpty().trim().lowercase(),
            provinceCode = province?.code.orEmpty(),
            procedureId = proc.id,
            offices = offices,
            minDate = minDate
        )

        val problems = profile.problems(catalog, nats)
        return if (problems.isEmpty()) {
            Item(profile, label, emptyList(), notes)
        } else {
            Item(null, label, problems, notes)
        }
    }

    /**
     * Identifica una oficina del catálogo con el nombre completo o parcial (sin tildes ni mayúsculas).
     * Devuelve (nombre exacto, null) o (null, mensaje de error con las opciones).
     */
    fun matchOffice(
        requested: String,
        offices: List<Office>,
        provinceName: String = ""
    ): Pair<String?, String?> {
        val q = normalize(requested)
        val tokens = q.split(' ').filter { it.isNotEmpty() }

        val exact = offices.firstOrNull { normalize(it.name) == q }
        if (exact != null) return Pair(exact.name, null)

        val hits = offices.filter { office ->
            val n = normalize(office.name)
            tokens.isNotEmpty() && tokens.all { n.contains(it) }
        }

        fun short(o: Office) = o.name.take(70)

        return when {
            hits.size == 1 -> Pair(hits[0].name, null)
            hits.isEmpty() -> {
                val where = if (provinceName.isNotBlank()) " en $provinceName" else ""
                Pair(
                    null,
                    "Oficina no reconocida: \"$requested\". Disponibles$where: " +
                        offices.take(10).joinToString(" | ") { short(it) }
                )
            }
            else -> Pair(
                null,
                "Oficina ambigua: \"$requested\" coincide con: " +
                    hits.take(6).joinToString(" | ") { short(it) } +
                    ". Escribe más del nombre."
            )
        }
    }

    fun resolveProcedure(
        province: Province,
        value: String?
    ): Pair<Procedure?, String?> {
        val raw = value.orEmpty().trim()
        val alias = normalize(raw.replace('_', ' '))

        if (raw.isEmpty() || alias in HUELLAS_ALIASES) {
            val hits = province.procedures.filter {
                normalize(it.name).startsWith("policia-toma de huellas")
            }
            return if (hits.size == 1) {
                Pair(hits[0], null)
            } else {
                Pair(null, "Indica el trámite (código) para ${province.name}.")
            }
        }

        if (raw.all { it.isDigit() }) {
            val procedure = province.procedure(raw)
            return if (procedure != null) {
                Pair(procedure, null)
            } else {
                Pair(null, "El trámite $raw no existe en ${province.name}.")
            }
        }

        province.procedures.firstOrNull { normalize(it.name) == alias }
            ?.let { return Pair(it, null) }

        val words = alias.split(' ').filter { it.length >= 3 }
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
            iso != null -> Triple(iso.groupValues[1], iso.groupValues[2], iso.groupValues[3])
            dmy != null -> Triple(dmy.groupValues[3], dmy.groupValues[2], dmy.groupValues[1])
            else -> return null
        }

        return try {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt()).toString()
        } catch (_: Exception) {
            null
        }
    }
}
