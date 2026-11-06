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

/**
 * Recibe solicitudes exclusivamente del Chat ID autorizado.
 * Mantiene una sola instancia de polling y confirma cada actualización
 * después de procesarla.
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
        // Nunca aceptar mensajes de otros chats.
        if (update.chatId != settings.telegramChatId.trim()) return

        val text = update.text.trim()
        if (text.isEmpty()) return

        val token = settings.telegramToken.trim()
        val store = Store.get(this)
        val catalog = Catalog.load(this)
        val nationalities = Nationalities.load(this)
        val normalized = normalize(text)

        fun reply(message: String) {
            TelegramApi.send(token, update.chatId, message)
        }

        try {
            when {
                normalized.startsWith("/ayuda") ||
                    normalized.startsWith("/start") ||
                    normalized.startsWith("/formato") -> {
                    reply(IntakeParser.HELP)
                }

                normalized.startsWith("/estado") -> {
                    reply("📊 " + Stats.compute(store.requests(), catalog).toText())
                }

                normalized.startsWith("/cola") -> {
                    reply(queueText(store, catalog))
                }

                normalized.startsWith("/cancelar") -> {
                    reply(
                        cancel(
                            text.substringAfter(' ', "").trim(),
                            store
                        )
                    )
                }

                IntakeParser.looksLikeRequest(text) -> {
                    reply(register(text, store, catalog, nationalities))
                }

                else -> {
                    reply("No entendí el mensaje. Envía /ayuda para ver el formato.")
                }
            }
        } catch (e: Exception) {
            reply("⚠️ No se pudo procesar el mensaje: ${e.javaClass.simpleName}")
        }
    }

    private fun register(
        text: String,
        store: Store,
        catalog: Catalog,
        nationalities: Nationalities
    ): String {
        val items = IntakeParser.parse(
            text,
            catalog,
            nationalities,
            LocalDate.now().toString()
        )

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
                lines.add(
                    "⚠️ ${item.label} (${Nif.mask(profile.docNumber)}): ya existe una solicitud activa."
                )
                continue
            }

            val request = Request(profile = profile)
            store.addRequest(request)

            Reporter.log(
                this,
                request,
                "registrada",
                "Registrada por Telegram"
            )

            Reporter.notify(
                this,
                Ev.REGISTRADA,
                store.request(request.id),
                "Solicitud recibida por Telegram.",
                skipTelegram = true
            )

            val province = catalog.province(profile.provinceCode)?.name
                ?: profile.provinceCode

            lines.add(
                "✅ ${item.label} (${Nif.mask(profile.docNumber)}) · $province · desde ${profile.minDate}"
            )
        }

        lines.add("")
        lines.add(
            "En cola: ${store.requests().count { it.status == Status.PENDIENTE }} pendiente(s)"
        )

        return lines.joinToString("\n")
    }

    private fun queueText(
        store: Store,
        catalog: Catalog
    ): String {
        val pending = store.requests().filter {
            it.status == Status.PENDIENTE
        }

        if (pending.isEmpty()) return "La cola está vacía."

        val body = pending.take(20).joinToString("\n") {
            "• ${it.displayName()} (${Nif.mask(it.profile.docNumber)}) · " +
                "${catalog.province(it.profile.provinceCode)?.name} · " +
                "${it.attempts} intento(s)"
        }

        return "📋 Pendientes (${pending.size}):\n$body" +
            if (pending.size > 20) "\n…" else ""
    }

    private fun cancel(
        argument: String,
        store: Store
    ): String {
        if (argument.isBlank()) {
            return "Indica el documento: /cancelar X1234567L"
        }

        val document = Nif.clean(argument)

        val targets = store.requests().filter {
            it.profile.docNumber == document && it.status in ACTIVE
        }

        if (targets.isEmpty()) {
            return "No hay solicitudes activas con ese documento."
        }

        targets.forEach { request ->
            val updated = store.updateRequest(request.id) {
                it.copy(status = Status.CANCELADA)
            }

            Reporter.log(
                this,
                updated,
                "manual",
                "Cancelada desde Telegram"
            )
        }

        return "🛑 Solicitudes canceladas: ${targets.size}"
    }

    companion object {
        private const val CHANNEL = "intake"
        private const val NOTIF_ID = 2001

        private val ACTIVE = listOf(
            Status.PENDIENTE,
            Status.EN_PROCESO,
            Status.ESPERANDO_HUMANO
        )
    }
}