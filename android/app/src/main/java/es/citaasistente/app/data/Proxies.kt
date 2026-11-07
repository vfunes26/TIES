
package es.citaasistente.app.data

import org.json.JSONObject
import java.util.UUID

data class ProxyEntry(
    val id: String = UUID.randomUUID().toString(),
    val host: String,
    val port: Int,
    val user: String = "",
    val pass: String = "",
    val type: String = "http",
    val enabled: Boolean = true,
    val status: Int = 0,
    val detail: String = "",
    val testedAt: Long = 0L,
) {
    fun label(): String = "$host:$port"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("host", host)
        .put("port", port)
        .put("user", user)
        .put("pass", pass)
        .put("type", type)
        .put("enabled", enabled)
        .put("status", status)
        .put("detail", detail)
        .put("testedAt", testedAt)

    companion object {
        fun fromJson(o: JSONObject): ProxyEntry = ProxyEntry(
            id = o.optString("id").ifBlank {
                UUID.randomUUID().toString()
            },
            host = o.getString("host"),
            port = o.getInt("port"),
            user = o.optString("user"),
            pass = o.optString("pass"),
            type = o.optString("type", "http")
                .lowercase()
                .takeIf { it == "http" || it == "https" }
                ?: "http",
            enabled = o.optBoolean("enabled", true),
            status = o.optInt("status"),
            detail = o.optString("detail"),
            testedAt = o.optLong("testedAt"),
        )
    }
}

object ProxyParser {

    fun parseLine(raw: String): ProxyEntry? {
        var s = raw.trim()
        if (s.isEmpty() || s.startsWith("#")) return null

        var type = "http"

        val scheme = Regex(
            "^(https?)://",
            RegexOption.IGNORE_CASE
        ).find(s)

        if (scheme != null) {
            type = scheme.groupValues[1].lowercase()
            s = s.substring(scheme.value.length)
        } else if ("://" in s) {
            return null
        }

        var user = ""
        var pass = ""
        val host: String
        val port: Int

        if (s.contains("@")) {
            if (s.count { it == '@' } != 1) return null

            val creds = s.substringBefore("@")
            val hp = s.substringAfter("@").split(":")

            if (hp.size != 2 || !creds.contains(":")) return null

            user = creds.substringBefore(":")
            pass = creds.substringAfter(":", "")

            if (user.isBlank() || pass.isBlank()) return null

            host = hp[0]
            port = hp[1].toIntOrNull() ?: return null
        } else {
            val p = s.split(":", limit = 4)

            when (p.size) {
                2 -> {
                    host = p[0]
                    port = p[1].toIntOrNull() ?: return null
                }
                4 -> {
                    host = p[0]
                    port = p[1].toIntOrNull() ?: return null
                    user = p[2]
                    pass = p[3]

                    if (user.isBlank() || pass.isBlank()) return null
                }
                else -> return null
            }
        }

        if (host.isBlank() ||
            host.any {
                it.isWhitespace() || it == '/' || it == '\\' || it == '@'
            } ||
            port !in 1..65535
        ) return null

        return ProxyEntry(
            host = host.trim(),
            port = port,
            user = user,
            pass = pass,
            type = type
        )
    }

    fun parseMany(
        text: String,
        existing: List<ProxyEntry> = emptyList()
    ): Pair<List<ProxyEntry>, Int> {

        val seen = HashSet<String>(
            existing.map { "${it.host}:${it.port}:${it.user}" }
        )

        val result = ArrayList<ProxyEntry>()
        var invalid = 0

        for (line in text.lines()) {
            if (line.isBlank() || line.trim().startsWith("#")) continue

            val proxy = parseLine(line)

            if (proxy == null) {
                invalid++
                continue
            }

            if (seen.add("${proxy.host}:${proxy.port}:${proxy.user}")) {
                result.add(proxy)
            }
        }

        return result to invalid
    }
}
