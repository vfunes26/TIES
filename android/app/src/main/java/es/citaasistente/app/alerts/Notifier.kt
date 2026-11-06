package es.citaasistente.app.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import es.citaasistente.app.R
import es.citaasistente.app.ui.AssistantActivity
import es.citaasistente.app.ui.MainActivity

/** Avisos propios de la app: alarma de cita disponible, estado del asistente y salud del sistema. */
object Notifier {
    const val CH_ALERT = "cita_alarma"
    const val CH_STATUS = "estado"
    private const val ID_CITA = 1001
    private const val ID_STATUS = 1002
    private const val ID_ASSISTANT = 1003

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val alarm = NotificationChannel(CH_ALERT, ctx.getString(R.string.canal_alarma), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.canal_alarma_desc)
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 900)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val status = NotificationChannel(CH_STATUS, ctx.getString(R.string.canal_estado), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = ctx.getString(R.string.canal_estado_desc)
        }
        nm.createNotificationChannel(alarm)
        nm.createNotificationChannel(status)
    }

    private fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    private fun post(ctx: Context, id: Int, n: Notification) {
        if (!canPost(ctx)) return
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) { }
    }

    /** ¡Cita disponible! Al tocar abre el asistente y arranca el autorrelleno. */
    fun citaAvailable(ctx: Context, title: String, text: String, ids: List<String> = emptyList()) {
        ensureChannels(ctx)
        val open = AutoLauncher.intent(ctx, ids)
        val pi = PendingIntent.getActivity(ctx, 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(ContextCompat.getColor(ctx, R.color.rojo))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .addAction(0, ctx.getString(R.string.accion_iniciar), pi)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val canFull = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
        if (canFull) b.setFullScreenIntent(pi, true)
        post(ctx, ID_CITA, b.build())
    }

    /** Estado del asistente (captcha, SMS, confirmada, error...). Toca para volver a la app. */
    fun assistant(ctx: Context, title: String, text: String, urgent: Boolean) {
        ensureChannels(ctx)
        val open = Intent(ctx, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, 2, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, if (urgent) CH_ALERT else CH_STATUS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(ContextCompat.getColor(ctx, R.color.rojo))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
        post(ctx, ID_ASSISTANT, b.build())
    }

    /** Aviso sobre el funcionamiento del sistema de alertas (permisos, app de alertas, silencio...). */
    fun health(ctx: Context, title: String, text: String) {
        ensureChannels(ctx)
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, 3, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(ContextCompat.getColor(ctx, R.color.gualda_oscuro))
            .setAutoCancel(true)
            .setContentIntent(pi)
        post(ctx, ID_STATUS, b.build())
    }
}
