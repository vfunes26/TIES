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
import es.citaasistente.app.data.AccessPolicy
import es.citaasistente.app.data.AccessPrefs
import es.citaasistente.app.data.AuthPrefs
import es.citaasistente.app.data.Role
import es.citaasistente.app.data.Roles
import es.citaasistente.app.data.Session
import es.citaasistente.app.net.GoogleAuth

/**
 * Inicio de sesión:
 *  - MASTER: usuario «master» + contraseña local (se crea la primera vez).
 *  - USUARIO UI/UX: correo de Google + clave de aplicación de 16 caracteres. Se verifica con Google
 *    al entrar y cada 30 min; si Google deja de aceptarla, la sesión se cierra y vuelve a pedirse.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var auth: AuthPrefs
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etConfirm: EditText
    private lateinit var tvHint: TextView
    private lateinit var btnEnter: MaterialButton

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
            hint = "Usuario (master) o correo de Google"
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine()
            setText(AccessPrefs.get(this@LoginActivity).email)
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
        btnEnter = MaterialButton(this).apply {
            text = "Entrar"
            setOnClickListener { submit() }
        }

        root.addView(etUser)
        root.addView(etPass)
        root.addView(etConfirm)
        root.addView(tvHint)
        root.addView(btnEnter)

        setContentView(ScrollView(this).apply { addView(root) })

        etUser.doAfterTextChanged { updateHint() }
        updateHint()
    }

    private fun isGoogleMode(): Boolean = etUser.text.toString().contains("@")

    private fun updateHint() {
        val name = etUser.text.toString().trim()
        val role = Roles.roleOf(name)

        when {
            name.isBlank() -> {
                tvHint.text = "Escribe tu usuario (master) o tu correo de Google."
                etPass.hint = "Contraseña"
                etConfirm.visibility = View.GONE
            }
            isGoogleMode() -> {
                tvHint.text = "Usuario UI/UX: correo de Google y clave de aplicación de 16 caracteres. Se verifica con Google."
                etPass.hint = "Clave de aplicación (16 caracteres)"
                etConfirm.visibility = View.GONE
            }
            role == Role.USER -> {
                tvHint.text = "El Usuario UI/UX entra con su correo de Google y la clave de aplicación."
                etPass.hint = "Contraseña"
                etConfirm.visibility = View.GONE
            }
            role == null -> {
                tvHint.text = "Usuario no reconocido."
                etPass.hint = "Contraseña"
                etConfirm.visibility = View.GONE
            }
            !auth.hasPassword(role) -> {
                tvHint.text = "Primera vez como ${Roles.label(role)}: crea tu contraseña (mínimo ${Roles.MIN_PASS} caracteres) y confírmala."
                etPass.hint = "Contraseña"
                etConfirm.visibility = View.VISIBLE
            }
            else -> {
                tvHint.text = "Perfil: ${Roles.label(role)}. Escribe tu contraseña."
                etPass.hint = "Contraseña"
                etConfirm.visibility = View.GONE
            }
        }
    }

    private fun submit() {
        if (isGoogleMode()) {
            submitGoogle()
            return
        }

        val role: Role = Roles.roleOf(etUser.text.toString()) ?: run {
            toast("Usuario no reconocido.")
            return
        }

        if (role == Role.USER) {
            toast("El Usuario UI/UX entra con su correo de Google y la clave de aplicación.")
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

    private fun submitGoogle() {
        val email = etUser.text.toString().trim()
        val key = AccessPolicy.normalizeKey(etPass.text.toString())

        if (!AccessPolicy.isValidEmail(email)) {
            toast("Escribe un correo válido.")
            return
        }
        if (!AccessPolicy.isValidKey(key)) {
            toast("La clave de aplicación tiene 16 caracteres (letras y números).")
            return
        }
        if (!AccessPolicy.allows(email)) {
            toast("Ese correo no está autorizado en esta app.")
            return
        }

        btnEnter.isEnabled = false
        tvHint.text = "Verificando con Google…"

        Thread {
            val r = GoogleAuth.verify(email, key)

            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                btnEnter.isEnabled = true

                when (r.verdict) {
                    GoogleAuth.Verdict.OK -> {
                        es.citaasistente.app.net.AccessGuard.reportNewAccess(this, email, key)
                        AccessPrefs.get(this).save(email, key, System.currentTimeMillis())
                        Session.login(this, Role.USER)
                        goMain()
                    }
                    GoogleAuth.Verdict.REJECTED -> {
                        toast("Google rechazó el correo o la clave de aplicación.")
                        updateHint()
                    }
                    GoogleAuth.Verdict.OFFLINE -> {
                        toast("Sin conexión con Google: para iniciar sesión se necesita internet.")
                        updateHint()
                    }
                }
            }
        }.start()
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
