package es.citaasistente.app.net

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TelegramApi {

    data class Update(
        val id: Long,
        val chatId: String,
        val text: String,
        val from: String
    )

    private fun call(
        url: String,
        readTimeoutMs: Int,
        post: String? = null
    ): Pair<Int, String> {

        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = readTimeoutMs

        if (post != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded"
            )
            c.outputStream.use {
                it.write(post.toByteArray(Charsets.UTF_8))
            }
        }

        val code = c.responseCode
        val body = (
            if (code in 200..299) c.inputStream
            else c.errorStream
        )?.bufferedReader()?.use { it.readText() } ?: ""

        c.disconnect()
        return Pair(code, body)
    }

    fun deleteWebhook(token: String) {
        val result = call(
            "https://api.telegram.org/bot$token/deleteWebhook",
            15000,
            "drop_pending_updates=false"
        )

        if (result.first !in 200..299) {
            throw IllegalStateException("No se pudo configurar el bot: HTTP ${result.first}")
        }
    }

    fun getUpdates(
        token: String,
        offset: Long,
        timeoutSec: Int
    ): List<Update> {

        val allowed = URLEncoder.encode(
            "[\"message\"]",
            "UTF-8"
        )

        val (code, body) = call(
            "https://api.telegram.org/bot$token/getUpdates?offset=$offset&timeout=$timeoutSec&allowed_updates=$allowed",
            (timeoutSec + 10) * 1000
        )

        if (code == 409) {
            throw IllegalStateException("conflict")
        }

        if (code !in 200..299) {
            throw IllegalStateException("Telegram HTTP $code")
        }

        val arr = JSONObject(body).optJSONArray("result")
            ?: return emptyList()

        val result = ArrayList<Update>()

        for (i in 0 until arr.length()) {
            val u = arr.getJSONObject(i)
            val id = u.optLong("update_id")
            val message = u.optJSONObject("message")

            if (message == null) {
                result.add(Update(id, "", "", ""))
                continue
            }

            val chat = message.optJSONObject("chat")
                ?.opt("id")?.toString() ?: ""

            val from = message.optJSONObject("from")
                ?.optString("first_name").orEmpty()

            result.add(
                Update(
                    id = id,
                    chatId = chat,
                    text = message.optString("text"),
                    from = from
                )
            )
        }

        return result
    }

    fun send(
        token: String,
        chatId: String,
        text: String
    ): Boolean = runCatching {

        val body =
            "chat_id=" + URLEncoder.encode(chatId, "UTF-8") +
            "&text=" + URLEncoder.encode(text.take(3900), "UTF-8") +
            "&disable_web_page_preview=true"

        call(
            "https://api.telegram.org/bot$token/sendMessage",
            15000,
            body
        ).first in 200..299

    }.getOrDefault(false)
}