package es.citaasistente.app.data

import android.content.Context
import org.json.JSONObject

data class Nationality(val value: String, val label: String)

/**
 * Lista OFICIAL de nacionalidades del portal (valor + texto de #txtPaisNac). Se elige de la lista,
 * nunca se escribe libre: si no coincide exactamente con el portal, la sede no procesa la solicitud.
 */
class Nationalities(val list: List<Nationality>) {
    fun byCode(code: String): Nationality? = list.firstOrNull { it.value == code }

    /** Coincidencia exacta (sin tildes/mayúsculas); null si no existe o es ambigua. */
    fun byLabelExact(label: String): Nationality? {
        val n = normalize(label)
        val hits = list.filter { normalize(it.label) == n }
        return if (hits.size == 1) hits[0] else null
    }

    companion object {
        fun parse(json: String): Nationalities {
            val arr = JSONObject(json).getJSONArray("options")
            val out = ArrayList<Nationality>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Nationality(o.getString("value"), o.getString("label")))
            }
            return Nationalities(out.sortedBy { normalize(it.label) })
        }

        @Volatile private var cached: Nationalities? = null

        fun load(context: Context): Nationalities = cached ?: synchronized(this) {
            cached ?: context.applicationContext.assets.open("nationalities.json")
                .bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
                .also { cached = it }
        }
    }
}
