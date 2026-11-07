package es.citaasistente.app.ui

import android.content.DialogInterface
import android.content.Intent
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import es.citaasistente.app.data.AuthPrefs
import es.citaasistente.app.data.Roles
import es.citaasistente.app.data.Session

/** Cerrar sesión y cambiar contraseña (botones de la sección Estadísticas). */
object AccountUi {

    fun logout(activity: AppCompatActivity) {
        if (AssistantActivity.active) {
            Toast.makeText(activity, "Hay una cola en proceso. Termínala antes de cerrar sesión.", Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Cerrar sesión")
            .setMessage("Tendrás que volver a entrar con tu usuario y contraseña.")
            .setPositiveButton("Cerrar sesión") { _, _ ->
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
        val auth = AuthPrefs.get(activity)

        fun field(hintText: String) = EditText(activity).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }

        val current = field("Contraseña actual")
        val next = field("Contraseña nueva (mínimo ${Roles.MIN_PASS})")
        val confirm = field("Confirmar contraseña nueva")

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(current)
            addView(next)
            addView(confirm)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Cambiar contraseña")
            .setView(box)
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

    private fun toast(activity: AppCompatActivity, msg: String) =
        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
