package com.med.radiogroupe

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement des messages du groupe.
 * - La clé AES-256 est dérivée du mot de passe du groupe (PBKDF2-SHA256).
 * - Chaque message est chiffré en AES-GCM : sans le bon mot de passe,
 *   un message ne peut être ni lu ni modifié.
 */
object Crypto {
    private const val ITERATIONS = 60_000
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    private fun normalize(group: String) = group.trim().lowercase(Locale.ROOT)

    fun deriveKey(group: String, password: String): SecretKey {
        val salt = ("RadioGroupe|v1|" + normalize(group)).toByteArray(Charsets.UTF_8)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw, "AES")
    }

    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_SIZE)
        random.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plain)
    }

    /** Renvoie null si le message n'a pas été chiffré avec la même clé. */
    fun decrypt(key: SecretKey, data: ByteArray): ByteArray? {
        if (data.size < IV_SIZE + TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 0, IV_SIZE))
            cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE)
        } catch (e: Exception) {
            null
        }
    }

    /** Identifiant radio propre à chaque groupe : seuls les téléphones du même groupe se voient. */
    fun serviceId(group: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("RadioGroupe|svc|" + normalize(group)).toByteArray(Charsets.UTF_8))
        return "com.med.radiogroupe." + digest.take(8).joinToString("") { "%02x".format(it) }
    }
}
