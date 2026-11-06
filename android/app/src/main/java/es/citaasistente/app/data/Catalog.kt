package es.citaasistente.app.data

import android.content.Context
import org.json.JSONObject

data class Office(val id: String, val name: String)
data class Procedure(val id: String, val name: String, val offices: List<Office>)
data class Province(val code: String, val name: String, val procedures: List<Procedure>) {
    fun procedure(id: String): Procedure? = procedures.firstOrNull { it.id == id }
}

/**
 * Catálogo de provincias → trámites → sedes. Fuente única: /shared/catalog.json
 * (la misma que valida el bot). Se empaqueta en assets/catalog.json.
 */
class Catalog(val provinces: List<Province>, val updatedAt: String) {

    fun province(code: String): Province? = provinces.firstOrNull { it.code == code }

    /** Provincia por código ("08", "8") o por nombre exacto normalizado. Estricto a propósito. */
    fun findProvince(query: String): Province? {
        val q = query.trim()
        if (q.isEmpty()) return null
        if (q.all { it.isDigit() }) return province(q.padStart(2, '0'))
        val n = normalize(q)
        return provinces.firstOrNull { normalize(it.name) == n }
    }

    companion object {
        fun parse(json: String): Catalog {
            val root = JSONObject(json)
            val provs = root.getJSONArray("provinces")
            val list = ArrayList<Province>(provs.length())
            for (i in 0 until provs.length()) {
                val p = provs.getJSONObject(i)
                val procs = p.getJSONArray("procedures")
                val procList = ArrayList<Procedure>(procs.length())
                for (j in 0 until procs.length()) {
                    val pr = procs.getJSONObject(j)
                    val offs = pr.getJSONArray("offices")
                    val offList = ArrayList<Office>(offs.length())
                    for (k in 0 until offs.length()) {
                        val o = offs.getJSONObject(k)
                        offList.add(Office(o.getString("id"), o.getString("name")))
                    }
                    procList.add(Procedure(pr.getString("id"), pr.getString("name"), offList))
                }
                list.add(Province(p.getString("code"), p.getString("name"), procList))
            }
            return Catalog(list.sortedBy { normalize(it.name) }, root.optString("updatedAt"))
        }

        @Volatile private var cached: Catalog? = null

        fun load(context: Context): Catalog = cached ?: synchronized(this) {
            cached ?: context.applicationContext.assets.open("catalog.json")
                .bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
                .also { cached = it }
        }
    }
}
