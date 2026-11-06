package es.citaasistente.app.alerts

import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import es.citaasistente.app.data.Store
import es.citaasistente.app.ui.AssistantActivity

/**
 * Arranque automático del autorrelleno al detectar una alerta que coincide con solicitudes pendientes.
 * Con el permiso «Mostrar sobre otras apps» Android deja abrir la pantalla desde segundo plano;
 * sin él, la alarma (notificación de pantalla completa) lo abre al tocarla.
 */
object AutoLauncher {

    fun intent(ctx: Context, ids: List<String>): Intent =
        Intent(ctx, AssistantActivity::class.java)
            .putExtra(AssistantActivity.EXTRA_MODE, AssistantActivity.MODE_ALERT)
            .putStringArrayListExtra(AssistantActivity.EXTRA_IDS, ArrayList(ids))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun canAutoLaunch(ctx: Context): Boolean = AndroidSettings.canDrawOverlays(ctx)

    fun launch(ctx: Context, ids: List<String>, title: String, text: String) {
        val settings = Store.get(ctx).settings
        Notifier.citaAvailable(ctx, title, text, ids)          // alarma siempre (aviso + respaldo)
        if (settings.autoLaunch && canAutoLaunch(ctx)) {
            try { ctx.startActivity(intent(ctx, ids)) } catch (_: Exception) { /* la notificación queda como respaldo */ }
        }
    }
}
