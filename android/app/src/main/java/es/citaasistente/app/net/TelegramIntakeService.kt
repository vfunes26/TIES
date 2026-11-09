package es.citaasistente.app.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import es.citaasistente.app.R
import es.citaasistente.app.data.Catalog
import es.citaasistente.app.data.ClientRegistry
import es.citaasistente.app.data.Ids
import es.citaasistente.app.data.Nationalities
import es.citaasistente.app.data.Nif
import es.citaasistente.app.data.Request
import es.citaasistente.app.data.Settings
import es.citaasistente.app.data.Stats
import es.citaasistente.app.data.Status
import es.citaasistente.app.data.Store
import es.citaasistente.app.data.normalize
import es.citaasistente.app.ui.MainActivity
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Recibe solicitudes por el bot de Telegram (una sola instancia de polling, solo en el teléfono del master).
 *  - MASTER (Chat ID de Ajustes): todos los comandos, ve todas las solicitudes y autoriza clientes.
 *  - CLIENTES autorizados (cada uno en su chat privado con el bot): solo crean y consultan SUS solicitudes.
 *    Nunca ven el chat del master ni las solicitudes de otros.
 *  - Cualquier otra persona: no se acepta nada; se le da su ID y se avisa al master.
 */
class TelegramIntakeService : Service() {

    @Volatile
    private var running = false

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val store = Store.get(this)
        val settings = store.settings

        if (!settings.tgIntake || !settings.telegramReady() ||
            !Regex("^-?\\d+$").matches(settings.telegramChatId.trim())
        ) {
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "Recepción por Telegram",
                NotificationManager.IMPORTANCE_LOW
            )
        )

        val open = PendingIntent.getActivity(
            this,
            9,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification =
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("CitaAsistente")
                .setContentText("Recepción de solicitudes por Telegram activa")
                .setOngoing(true)
                .setContentIntent(open)
                .build()

        startForeground(NOTIF_ID, notification)

        if (!running) {
            running = true
            worker = Thread(
                { loop() },
                "ties-telegram-intake"
            ).also {
                it.isDaemon = true
                it.start()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }

    private fun loop() {
        val store = Store.get(this)
        var backoff = 3000L
        var webhookCleared = false

        while (running) {
            val settings = store.settings

            if (!settings.tgIntake || !settings.telegramReady() ||
                !Regex("^-?\\d+$").matches(settings.telegramChatId.trim())
            ) {
                stopSelf()
                return
            }

            try {
                val token = settings.telegramToken.trim()

                if (!webhookCleared) {
                    TelegramApi.deleteWebhook(token)
                    webhookCleared = true
                }

                val updates = TelegramApi.getUpdates(
                    token,
                    store.intakeOffset,
                    25
                )

                for (update in updates) {
                    if (!running) return

                    // Procesar primero. El offset solo avanza después.
                    if (update.chatId.isNotEmpty()) {
                        handle(settings, update)
                    }

                    store.intakeOffset = update.id + 1
                }

                backoff = 3000L

            } catch (_: InterruptedException) {
                return

            } catch (e: Exception) {
                if (e.message == "conflict") {
                    webhookCleared = false
                }

                try {
                    Thread.sleep(backoff)
                } catch (_: InterruptedException) {
                    return
                }

                backoff = minOf(backoff * 2, 60_000L)
            }
        }
    }

    private fun handle(
        settings: Settings,
        update: TelegramApi.Update
    ) {
        val text = update.text.trim()
        if (text.isEmpty()) return

        val store = Store.get(this)
        val token = settings.telegramToken.trim()
        val chat = update.chatId
        val isMaster = chat == settings.telegramChatId.trim()
        val isClient = !isMaster && ClientRegistry.contains(store.clientsRaw(), chat)

        fun reply(message: String) {
            TelegramApi.send(token, chat, message)
        }

        // Ni el master ni un cliente autorizado: no se acepta nada (y los grupos se ignoran).
        if (!isMaster && !isClient) {
            if (!chat.startsWith("-")) handleStranger(settings, update)
            return
        }

        val catalog = Catalog.load(this)
        val nationalities = Nationalities.load(this)
        val normalized = normalize(text)

        // El master ve todo; un cliente, solo lo que él mismo envió.
        fun mine(): List<Request> =
            store.requests().filter { isMaster || it.sourceChat == chat }

        try {
            when {
                normalized.startsWith("/ayuda") ||
                    normalized.startsWith("/start") ||
                    normalized.startsWith("/formato") -> {
                    reply(if (isMaster) IntakeParser.HELP else IntakeParser.HELP_CLIENT)
                }

                normalized.startsWith("/estado") -> {
                    reply("📊 " + Stats.compute(mine(), catalog).toText())
                }

                normalized.startsWith("/cola") -> {
                    reply(queueText(mine(), catalog))
                }

                normalized.startsWith("/cancelar") -> {
                    reply(cancel(text.substringAfter(' ', "").trim(), mine(), store))
                }

                isMaster && normalized.startsWith("/clientes") -> {
                    reply(clientsText(store))
                }

                isMaster && normalized.startsWith("/autorizar") -> {
                    reply(authorize(text.substringAfter(' ', "").trim(), store))
                }

                isMaster && normalized.startsWith("/bloquear") -> {
                    reply(block(text.substringAfter(' ', "").trim(), store))
                }

                isMaster && normalized.startsWith("/intentos") -> {
                    reply(es.citaasistente.app.data.AttemptStore.command(this, text.substringAfter(' ', "").trim()))
                }

                IntakeParser.looksLikeRequest(text) -> {
                    val sender = if (isMaster) "" else chat
                    val who = if (isMaster) "" else clientName(store, chat, update.from)
                    reply(register(text, store, catalog, nationalities, sender, who, isMaster))
                }

                else -> {
                    reply("No entendí el mensaje. Envía /ayuda para ver el formato.")
                }
            }
        } catch (e: Exception) {
            reply("⚠️ No se pudo procesar el mensaje: ${e.javaClass.simpleName}")
        }
    }

    /** Alguien sin autorización escribió al bot: se le da su ID y se avisa al master para que decida. */
    private fun handleStranger(settings: Settings, update: TelegramApi.Update) {
        val token = settings.telegramToken.trim()
        val chat = update.chatId

        TelegramApi.send(
            token,
            chat,
            "🔒 Este canal es privado.\nTu ID de Telegram es $chat.\nEnvíaselo al administrador para que te autorice."
        )

        val now = System.currentTimeMillis()
        val last = asked[chat] ?: 0L
        if (now - last < 60L * 60L * 1000L) return
        asked[chat] = now
        names[chat] = update.from.ifBlank { "Cliente" }

        TelegramApi.send(
            token,
            settings.telegramChatId.trim(),
            "🆕 SOLICITUD DE ACCESO\n${update.from.ifBlank { "(sin nombre)" }}\nID: $chat\nPara autorizarlo: /autorizar $chat"
        )
    }

    private fun clientName(store: Store, chat: String, fallback: String): String =
        ClientRegistry.parse(store.clientsRaw()).firstOrNull { it.chatId == chat }?.name
            ?.ifBlank { fallback } ?: fallback

    private fun clientsText(store: Store): String {
        val list = ClientRegistry.parse(store.clientsRaw())
        if (list.isEmpty()) return "No hay clientes autorizados.\nUn cliente escribe al bot, recibes su ID y lo autorizas con /autorizar ID."

        return "👥 Clientes autorizados (${list.size}):\n" +
            list.joinToString("\n") { "• ${it.name.ifBlank { "Cliente" }} · ID ${it.chatId}" } +
            "\n\nPara quitar a uno: /bloquear ID"
    }

    private fun authorize(argument: String, store: Store): String {
        val parts = argument.split(Regex("\\s+"), limit = 2)
        val id = parts.firstOrNull().orEmpty().trim()

        if (!ClientRegistry.isChatId(id)) return "Indica el ID del cliente: /autorizar 123456789"

        val name = parts.getOrNull(1)?.trim().orEmpty().ifBlank { names[id] ?: "Cliente" }
        store.setClientsRaw(
            ClientRegistry.add(store.clientsRaw(), ClientRegistry.Client(id, name, System.currentTimeMillis()))
        )

        val settings = store.settings
        TelegramApi.send(
            settings.telegramToken.trim(),
            id,
            "✅ Ya estás autorizado. Envía /ayuda para ver cómo registrar una solicitud."
        )

        return "✅ Cliente autorizado: $name (ID $id)"
    }

    private fun block(argument: String, store: Store): String {
        val id = argument.trim()
        if (!ClientRegistry.isChatId(id)) return "Indica el ID del cliente: /bloquear 123456789"

        if (!ClientRegistry.contains(store.clientsRaw(), id)) return "Ese ID no está entre los clientes autorizados."

        store.setClientsRaw(ClientRegistry.remove(store.clientsRaw(), id))
        return "🚫 Cliente bloqueado (ID $id). Sus solicitudes ya creadas siguen en la cola."
    }

    private fun register(
        text: String,
        store: Store,
        catalog: Catalog,
        nationalities: Nationalities,
        sourceChat: String,
        clientName: String,
        isMaster: Boolean
    ): String {
        val today = LocalDate.now(ZoneId.of("Europe/Madrid")).toString()
        val parsed = IntakeParser.parse(text, catalog, nationalities, today)

        if (parsed.isEmpty()) return IntakeParser.HELP

        // Un cliente puede enviar como máximo 10 solicitudes por mensaje.
        val items = if (isMaster) parsed else parsed.take(10)

        val lines = ArrayList<String>()

        for (item in items) {
            val profile = item.profile

            if (profile == null) {
                lines.add("❌ ${item.label}: ${item.errors.joinToString(" · ")}")
                continue
            }

            val duplicate = store.requests().any { request ->
                request.profile.docNumber == profile.docNumber &&
                    request.profile.provinceCode == profile.provinceCode &&
                    request.profile.procedureId == profile.procedureId &&
                    request.status in ACTIVE
            }

            if (duplicate) {
                lines.add("⚠️ ${item.label} (${Nif.mask(profile.docNumber)}): ya existe una solicitud activa.")
                continue
            }

            val request = Request(profile = profile, sourceChat = sourceChat)
            store.addRequest(request)

            Reporter.log(this, request, "registrada", if (isMaster) "Registrada por Telegram" else "Registrada por Telegram (cliente $clientName)")

            Reporter.notify(
                this,
                Ev.REGISTRADA,
                store.request(request.id),
                if (isMaster) "Solicitud recibida por Telegram." else "📨 Solicitud recibida por Telegram del cliente $clientName (ID $sourceChat).",
                skipTelegram = isMaster
            )

            val province = catalog.province(profile.provinceCode)?.name ?: "cualquier provincia"
            val office = if (profile.offices.isEmpty()) "" else " · ${profile.offices.joinToString(" | ") { it.take(40) }}"

            lines.add("✅ ID ${Ids.short(request.id)} · ${item.label} (${Nif.mask(profile.docNumber)}) · $province$office · desde ${profile.minDate}")
            item.notes.forEach { lines.add("   ℹ️ $it") }
        }

        if (!isMaster && parsed.size > items.size) {
            lines.add("⚠️ Solo se aceptan 10 solicitudes por mensaje: ${parsed.size - items.size} no se procesaron.")
        }

        lines.add("")
        lines.add("Te iré avisando aquí del resultado de cada solicitud.")

        return lines.joinToString("\n")
    }

    private fun queueText(
        requests: List<Request>,
        catalog: Catalog
    ): String {
        val pending = requests.filter { it.status == Status.PENDIENTE }

        if (pending.isEmpty()) return "La cola está vacía."

        val body = pending.take(20).joinToString("\n") {
            "• ID ${Ids.short(it.id)} · ${it.displayName()} (${Nif.mask(it.profile.docNumber)}) · " +
                "${catalog.province(it.profile.provinceCode)?.name ?: "cualquier provincia"} · " +
                "${it.attempts} intento(s)"
        }

        return "📋 Pendientes (${pending.size}):\n$body" +
            if (pending.size > 20) "\n…" else ""
    }

    private fun cancel(
        argument: String,
        visible: List<Request>,
        store: Store
    ): String {
        if (argument.isBlank()) {
            return "Indica el ID o el documento: /cancelar ABC123"
        }

        val id = argument.trim().uppercase()
        val document = Nif.clean(argument)

        val targets = visible.filter {
            (Ids.short(it.id) == id || it.profile.docNumber == document) && it.status in ACTIVE
        }

        if (targets.isEmpty()) {
            return "No hay solicitudes activas con ese ID o documento."
        }

        targets.forEach { request ->
            val updated = store.updateRequest(request.id) {
                it.copy(status = Status.CANCELADA)
            }

            Reporter.log(this, updated, "manual", "Cancelada desde Telegram")

            if (updated != null) ActivityReport.cancelled(this, updated)
        }

        return "🛑 Solicitudes canceladas: ${targets.size}"
    }

    companion object {
        private const val CHANNEL = "intake"
        private const val NOTIF_ID = 2001

        /** Última vez que se avisó al master de cada persona sin autorización (para no repetir el aviso). */
        private val asked = ConcurrentHashMap<String, Long>()
        private val names = ConcurrentHashMap<String, String>()

        private val ACTIVE = listOf(
            Status.PENDIENTE,
            Status.EN_PROCESO,
            Status.ESPERANDO_HUMANO
        )
    }
}