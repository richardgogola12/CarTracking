package sk.cartracker.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object KeystoreCrypto {
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun getOrCreateKey(alias: String, requireBiometric: Boolean = false): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (requireBiometric) {
            builder.setUserAuthenticationRequired(true)
            builder.setInvalidatedByBiometricEnrollment(true) // nový odtlačok = kľúč neplatný
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(builder.build()) }
            .generateKey()
    }

    fun deleteKey(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    fun encryptCipher(alias: String, requireBiometric: Boolean): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias, requireBiometric)) }

    fun decryptCipher(alias: String, iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(alias, true), GCMParameterSpec(128, iv))
        }

    /** Šifrovanie bez biometrie: výsledok "iv:ciphertext" v Base64. */
    fun encrypt(alias: String, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias)) }
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun decrypt(alias: String, data: String): String? = runCatching {
        val (iv, ct) = data.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(alias), GCMParameterSpec(128, iv))
        }
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()
}
