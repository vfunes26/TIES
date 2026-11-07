package es.citaasistente.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import es.citaasistente.app.data.AuthPrefs
import es.citaasistente.app.data.Role
import es.citaasistente.app.data.Roles
import es.citaasistente.app.data.Session

/**
 * Inicio de sesión sin registro. El nombre de usuario decide el perfil (master o Usuario UI/UX).
 * La primera vez que se entra con un nombre, se crea su contraseña; después se usa para entrar.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var auth: AuthPrefs
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etConfirm: EditText
    private lateinit var tvHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        auth = AuthPrefs.get(this)

        if (Session.isLoggedIn(this)) {
            goMain()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }

        root.addView(label("CitaAsistente", 26f, bold = true))
        root.addView(label("Inicia sesión", 16f))

        etUser = EditText(this).apply {
            hint = "Usuario"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
        }
        etPass = EditText(this).apply {
            hint = "Contraseña"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        etConfirm = EditText(this).apply {
            hint = "Confirmar contraseña"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            visibility = View.GONE
        }
        tvHint = label("", 13f)

        root.addView(etUser)
        root.addView(etPass)
        root.addView(etConfirm)
        root.addView(tvHint)
        root.addView(MaterialButton(this).apply {
            text = "Entrar"
            setOnClickListener { submit() }
        })

        setContentView(ScrollView(this).apply { addView(root) })

        etUser.doAfterTextChanged { updateHint() }
        updateHint()
    }

    private fun updateHint() {
        val name = etUser.text.toString()
        val role = Roles.roleOf(name)

        when {
            name.isBlank() -> {
                tvHint.text = "Escribe tu usuario."
                etConfirm.visibility = View.GONE
            }
            role == null -> {
                tvHint.text = "Usuario no reconocido."
                etConfirm.visibility = View.GONE
            }
            !auth.hasPassword(role) -> {
                tvHint.text = "Primera vez como ${Roles.label(role)}: crea tu contraseña (mínimo ${Roles.MIN_PASS} caracteres) y confírmala."
                etConfirm.visibility = View.VISIBLE
            }
            else -> {
                tvHint.text = "Perfil: ${Roles.label(role)}. Escribe tu contraseña."
                etConfirm.visibility = View.GONE
            }
        }
    }

    private fun submit() {
        val role: Role = Roles.roleOf(etUser.text.toString()) ?: run {
            toast("Usuario no reconocido.")
            return
        }
        val pass = etPass.text.toString()

        if (!auth.hasPassword(role)) {
            if (pass.length < Roles.MIN_PASS) {
                toast("La contraseña debe tener al menos ${Roles.MIN_PASS} caracteres.")
                return
            }
            if (pass != etConfirm.text.toString()) {
                toast("Las contraseñas no coinciden.")
                return
            }
            auth.setPassword(role, pass)
        } else {
            val wait = auth.lockedSeconds(role)
            if (wait > 0L) {
                toast("Demasiados intentos. Espera $wait s.")
                return
            }
            if (!auth.check(role, pass)) {
                val after = auth.lockedSeconds(role)
                toast(if (after > 0L) "Contraseña incorrecta. Bloqueado $after s." else "Contraseña incorrecta.")
                return
            }
        }

        Session.login(this, role)
        goMain()
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun label(t: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }
}
