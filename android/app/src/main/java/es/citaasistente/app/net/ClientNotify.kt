package es.citaasistente.app.net

import android.content.Context
import es.citaasistente.app.data.Catalog
import es.citaasistente.app.data.Ids
import es.citaasistente.app.data.Nif
import es.citaasistente.app.data.Request
import es.citaasistente.app.data.Store
import java.io.File

/**
 * Avisos al CLIENTE que envió la solicitud por Telegram (en su chat privado). El master recibe lo suyo por Reporter.
 * Solo se avisa a quien creó la solicitud: nunca se mezcla con el chat del master ni con otros clientes.
 */
object ClientNotify {

    private fun clientChat(app: Context, req: Request): String? {
        val chat = req.sourceChat.trim()
        if (chat.isEmpty()) return null
        if (chat == Store.get(app).settings.telegramChatId.trim()) return null
        return chat
    }

    /** Lo llama Reporter.notify: cita conseguida y fallida. */
    fun onEvent(ctx: Context, ev: Ev, req: Request, text: String) {
        val app = ctx.applicationContext
        when (ev) {
            Ev.CON_CITA -> status(app, req, citaText(app, req))
            Ev.FALLIDA -> status(app, req, "⚠️ No se pudo completar tu solicitud.\n${text.take(300)}")
            else -> {}
        }
    }

    /** Mensaje de estado al cliente (cancelada, vencida, fallida…). */
    fun status(ctx: Context, req: Request, text: String) {
        val app = ctx.applicationContext
        val chat = clientChat(app, req) ?: return
        val s = Store.get(app).settings
        if (!s.telegramReady()) return

        Telegram.send(s.copy(telegramChatId = chat), "ID ${Ids.short(req.id)}\n$text")
    }

    /** Captura de la confirmación: al master y, si la envió un cliente, también a su chat. */
    fun photo(ctx: Context, req: Request, shot: File?) {
        if (shot == null) return
        val app = ctx.applicationContext
        val s = Store.get(app).settings
        val client = clientChat(app, req)

        Thread {
            try {
                if (s.telegramReady()) {
                    val caption = "📸 Confirmación · ID ${Ids.short(req.id)} · ${req.displayName()}"
                    TelegramPhoto.send(s, s.telegramChatId, shot, caption)
                    if (client != null) TelegramPhoto.send(s, client, shot, caption)
                }
            } catch (e: Exception) {
                // la captura es un extra: si falla, el aviso de texto ya salió
            } finally {
                shot.delete()
            }
        }.start()
    }

    private fun citaText(app: Context, req: Request): String {
        val catalog = try {
            Catalog.load(app)
        } catch (e: Exception) {
            null
        }
        val prov = catalog?.province(req.profile.provinceCode)
        val proc = prov?.procedure(req.profile.procedureId)

        return buildString {
            append("🎉 ¡CITA CONSEGUIDA!\n")
            append("${req.displayName()} (${Nif.mask(req.profile.docNumber)})\n")
            append("${prov?.name ?: "—"} · ${proc?.name ?: req.profile.procedureId}\n")
            if (req.slot.isNotBlank()) append("Cita: ${req.slot}\n")
            if (req.reference.isNotBlank()) append("Justificante: ${req.reference}\n")
            if (req.result.isNotBlank()) append("\n${req.result.take(700)}")
        }
    }
}
