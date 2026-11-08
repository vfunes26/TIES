package es.citaasistente.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.regex.Pattern

/** Datos de UNA persona/solicitud: se introducen a mano y se usan para autorrellenar. */
data class Profile(
    val docType: String = "NIE",            // NIE | DNI | PASAPORTE
    val docNumber: String = "",
    val fullName: String = "",
    val nationalityCode: String = "",       // valor del desplegable del portal (p. ej. "242")
    val nationalityLabel: String = "",      // texto oficial (p. ej. "PERU")
    val phone: String = "",
    val email: String = "",
    val provinceCode: String = "",
    val procedureId: String = "",
    val offices: List<String> = emptyList(), // sedes por prioridad; vacío = cualquiera
    val minDate: String = "",               // yyyy-MM-dd: se acepta la primera cita >= esta fecha
) {
    fun step1Problems(nats: Nationalities): List<String> {
        val out = ArrayList<String>()
        val docOk = when (docType) {
            "NIE" -> Nif.isValidNie(docNumber)
            "DNI" -> Nif.isValidDni(docNumber)
            else -> Nif.isValidPassport(docNumber)
        }
        if (!docOk) out.add("El número de $docType no es válido (revisa formato y letra de control).")
        if (fullName.trim().length < 3) out.add("Falta el nombre y apellidos.")
        val nat = nats.byCode(nationalityCode)
        if (nat == null || nat.label != nationalityLabel) out.add("Selecciona la nacionalidad de la lista (no se puede escribir libre).")
        if (!Pattern.matches("^\\+?[\\d\\s]{9,15}$", phone)) out.add("El teléfono no parece válido.")
        if (!Pattern.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", email)) out.add("El correo no es válido.")
        return out
    }

    fun step2Problems(catalog: Catalog): List<String> {
        val out = ArrayList<String>()
        val prov = catalog.province(provinceCode)
        if (provinceCode.isBlank()) { if (AnyProvince.provincesWith(catalog, procedureId).isEmpty()) out.add("Elige un trámite válido.") }
        else if (prov == null) out.add("Elige una provincia.")
        else if (prov.procedure(procedureId) == null) out.add("Elige un trámite de ${prov.name}.")
        if (!Pattern.matches("^\\d{4}-\\d{2}-\\d{2}$", minDate)) out.add("Indica la fecha mínima aceptable de la cita.")
        return out
    }

    /** Lista de problemas en español; vacía = listo para procesar. */
    fun problems(catalog: Catalog, nats: Nationalities): List<String> = step1Problems(nats) + step2Problems(catalog)

    fun toJson(): JSONObject = JSONObject()
        .put("docType", docType).put("docNumber", docNumber).put("fullName", fullName)
        .put("nationalityCode", nationalityCode).put("nationalityLabel", nationalityLabel)
        .put("phone", phone).put("email", email)
        .put("provinceCode", provinceCode).put("procedureId", procedureId)
        .put("offices", JSONArray(offices)).put("minDate", minDate)

    companion object {
        fun fromJson(o: JSONObject): Profile {
            val offs = o.optJSONArray("offices")
            return Profile(
                docType = o.optString("docType", "NIE"),
                docNumber = o.optString("docNumber"),
                fullName = o.optString("fullName"),
                nationalityCode = o.optString("nationalityCode"),
                nationalityLabel = o.optString("nationalityLabel"),
                phone = o.optString("phone"),
                email = o.optString("email"),
                provinceCode = o.optString("provinceCode"),
                procedureId = o.optString("procedureId"),
                offices = (0 until (offs?.length() ?: 0)).map { offs!!.getString(it) },
                minDate = o.optString("minDate"),
            )
        }
    }
}
