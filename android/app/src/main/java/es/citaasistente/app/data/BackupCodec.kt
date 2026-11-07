package es.citaasistente.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cifrado del archivo de respaldo con TU frase (no con el Keystore del teléfono, que se pierde al reinstalar).
 * Formato: "TIES1" + sal(16) + iv(12) + AES-256-GCM( gzip(json) ). La clave sale de la frase con PBKDF2-SHA256.
 */
object BackupCodec {

    class WrongPassphraseException : Exception("Frase incorrecta o archivo dañado")
    class NotABackupException : Exception("No es un archivo de respaldo de TIES")

    private val MAGIC = "TIES1".toByteArray(Charsets.US_ASCII)
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 120_000

    fun encrypt(plain: ByteArray, pass: CharArray): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { rnd.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(pass, salt), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(MAGIC)

        return MAGIC + salt + iv + cipher.doFinal(gzip(plain))
    }

    fun decrypt(data: ByteArray, pass: CharArray): ByteArray {
        val headerLen = MAGIC.size + SALT_LEN + IV_LEN
        val minLen = headerLen + TAG_BITS / 8

        if (data.size < minLen || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw NotABackupException()
        }

        val salt = data.copyOfRange(MAGIC.size, MAGIC.size + SALT_LEN)
        val iv = data.copyOfRange(MAGIC.size + SALT_LEN, headerLen)
        val body = data.copyOfRange(headerLen, data.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(pass, salt), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(MAGIC)

        val packed = try {
            cipher.doFinal(body)
        } catch (e: BadPaddingException) {
            throw WrongPassphraseException()
        }

        return gunzip(packed)
    }

    private fun key(pass: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pass, salt, ITERATIONS, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
}
