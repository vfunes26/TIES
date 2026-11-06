package es.citaasistente.app.data

import java.text.Normalizer

/** Normaliza texto para comparar nombres del sitio oficial (tildes, mayúsculas, apóstrofos, espacios). */
fun normalize(input: String?): String {
    if (input == null) return ""
    return Normalizer.normalize(input, Normalizer.Form.NFD)
        .replace(Regex("[\\u0300-\\u036f]"), "")
        .lowercase()
        .replace(Regex("[`´'’‘\"“”]"), "")
        .replace(Regex("[^a-z0-9/.\\-\\s,@_|]"), " ")
        .replace(Regex("\\s*,\\s*"), ", ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
