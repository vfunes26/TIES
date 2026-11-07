package es.citaasistente.app.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Guarda contraseñas como sal + PBKDF2-SHA256 (nunca la contraseña en claro). Formato: p1:sal:hash */
object PasswordHasher {

    private const val ITERATIONS = 120_000
    private const val SALT_LEN = 16
    private const val PREFIX = "p1"

    fun create(pass: String): String {
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        return PREFIX + ":" + enc(salt) + ":" + enc(derive(pass, salt))
    }

    fun verify(pass: String, stored: String): Boolean {
        return try {
            val parts = stored.split(":")
            if (parts.size != 3 || parts[0] != PREFIX) {
                false
            } else {
                val salt = Base64.getDecoder().decode(parts[1])
                val expected = Base64.getDecoder().decode(parts[2])
                MessageDigest.isEqual(derive(pass, salt), expected)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun enc(b: ByteArray): String = Base64.getEncoder().withoutPadding().encodeToString(b)

    private fun derive(pass: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pass.toCharArray(), salt, ITERATIONS, 256)
        val out = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return out
    }
}
