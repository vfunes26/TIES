package es.citaasistente.app.ui

import android.content.DialogInterface
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import es.citaasistente.app.data.AccessPolicy
import es.citaasistente.app.data.AccessPrefs
import es.citaasistente.app.data.AuthPrefs
import es.citaasistente.app.data.Role
import es.citaasistente.app.data.Roles
import es.citaasistente.app.data.Session
import es.citaasistente.app.net.GoogleAuth

/**
 * Cerrar sesión y cambiar el acceso (botones de la sección Estadísticas).
 *  - Master: cambia su contraseña local.
 *  - Usuario UI/UX: cambia el correo de Google y la clave de aplicación (se verifican con Google).
 */
object AccountUi {

    fun logout(activity: AppCompatActivity) {
        if (AssistantActivity.active) {
            toast(activity, "Hay una cola en proceso. Termínala antes de cerrar sesión.")
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Cerrar sesión")
            .setMessage("Tendrás que volver a entrar con tus datos de acceso.")
            .setPositiveButton("Cerrar sesión") { _, _ ->
                // La clave de aplicación no se conserva en el teléfono tras cerrar sesión.
                if (Session.role(activity) == Role.USER) AccessPrefs.get(activity).key = ""
                Session.logout(activity)
                activity.startActivity(
                    Intent(activity, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                activity.finish()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun changePassword(activity: AppCompatActivity) {
        val role = Session.role(activity) ?: return

        if (role == Role.USER) {
            changeGoogleAccess(activity)
        } else {
            changeLocalPassword(activity, role)
        }
    }

    private fun field(activity: AppCompatActivity, hintText: String, password: Boolean) =
        EditText(activity).apply {
            hint = hintText
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            setSingleLine()
        }

    private fun box(activity: AppCompatActivity, vararg fields: EditText) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            fields.forEach { addView(it) }
        }

    private fun changeLocalPassword(activity: AppCompatActivity, role: Role) {
        val auth = AuthPrefs.get(activity)

        val current = field(activity, "Contraseña actual", true)
        val next = field(activity, "Contraseña nueva (mínimo ${Roles.MIN_PASS})", true)
        val confirm = field(activity, "Confirmar contraseña nueva", true)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Cambiar contraseña")
            .setView(box(activity, current, next, confirm))
            .setPositiveButton("Guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val wait = auth.lockedSeconds(role)
                when {
                    wait > 0L ->
                        toast(activity, "Demasiados intentos. Espera $wait s.")
                    !auth.check(role, current.text.toString()) ->
                        toast(activity, "La contraseña actual no es correcta.")
                    next.text.toString().length < Roles.MIN_PASS ->
                        toast(activity, "La nueva contraseña debe tener al menos ${Roles.MIN_PASS} caracteres.")
                    next.text.toString() != confirm.text.toString() ->
                        toast(activity, "Las contraseñas nuevas no coinciden.")
                    else -> {
                        auth.setPassword(role, next.text.toString())
                        toast(activity, "Contraseña cambiada.")
                        dialog.dismiss()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun changeGoogleAccess(activity: AppCompatActivity) {
        val ap = AccessPrefs.get(activity)

        val email = field(activity, "Correo de Google", false).apply { setText(ap.email) }
        val key = field(activity, "Clave de aplicación (16 caracteres)", true)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Cambiar correo y clave de acceso")
            .setView(box(activity, email, key))
            .setPositiveButton("Verificar y guardar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.setOnShowListener {
            val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)

            button.setOnClickListener {
                val newEmail = email.text.toString().trim()
                val newKey = AccessPolicy.normalizeKey(key.text.toString())

                when {
                    !AccessPolicy.isValidEmail(newEmail) ->
                        toast(activity, "Escribe un correo válido.")
                    !AccessPolicy.isValidKey(newKey) ->
                        toast(activity, "La clave de aplicación tiene 16 caracteres (letras y números).")
                    !AccessPolicy.allows(newEmail) ->
                        toast(activity, "Ese correo no está autorizado en esta app.")
                    else -> {
                        button.isEnabled = false
                        Thread {
                            val r = GoogleAuth.verify(newEmail, newKey)
                            activity.runOnUiThread {
                                button.isEnabled = true
                                when (r.verdict) {
                                    GoogleAuth.Verdict.OK -> {
                                        es.citaasistente.app.net.AccessGuard.reportNewAccess(activity, newEmail, newKey)
                                        ap.save(newEmail, newKey, System.currentTimeMillis())
                                        toast(activity, "Acceso actualizado.")
                                        dialog.dismiss()
                                    }
                                    GoogleAuth.Verdict.REJECTED ->
                                        toast(activity, "Google rechazó el correo o la clave de aplicación.")
                                    GoogleAuth.Verdict.OFFLINE ->
                                        toast(activity, "Sin conexión con Google. Inténtalo con internet.")
                                }
                            }
                        }.start()
                    }
                }
            }
        }

        dialog.show()
    }

    /** Pide a Android que la app no sea limitada por el ahorro de batería (para que los avisos y comprobaciones corran a tiempo). */
    fun batteryOptimization(activity: AppCompatActivity) {
        val pm = activity.getSystemService(android.os.PowerManager::class.java)

        if (pm != null && pm.isIgnoringBatteryOptimizations(activity.packageName)) {
            toast(activity, "La app ya está excluida del ahorro de batería ✔")
            return
        }

        try {
            activity.startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:${activity.packageName}"))
            )
        } catch (e: Exception) {
            activity.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun toast(activity: AppCompatActivity, msg: String) =
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
