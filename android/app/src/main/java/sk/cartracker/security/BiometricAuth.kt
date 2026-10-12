package sk.cartracker.security

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

object BiometricAuth {
    const val BIO_KEY = "ct_bio_key"

    fun isAvailable(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun unavailableReason(ctx: Context): String? = when (BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG)) {
        BiometricManager.BIOMETRIC_SUCCESS -> null
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "V telefóne nie je zaregistrovaný žiadny odtlačok prsta."
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "Telefón nemá snímač odtlačkov."
        else -> "Biometria momentálne nie je dostupná."
    }

    private fun prompt(
        activity: FragmentActivity, title: String, subtitle: String, cipher: Cipher,
        onSuccess: (Cipher) -> Unit, onError: (String) -> Unit,
    ) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Zrušiť")
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        val bp = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let(onSuccess) ?: onError("Overenie zlyhalo")
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onError(errString.toString()) }
        })
        bp.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    /** Zapnutie: zašifruje token kľúčom chráneným odtlačkom. */
    fun enroll(activity: FragmentActivity, session: SessionStore, onDone: (Boolean, String?) -> Unit) {
        val token = session.token() ?: return onDone(false, "Nie ste prihlásený")
        val cipher = try {
            KeystoreCrypto.deleteKey(BIO_KEY)
            KeystoreCrypto.encryptCipher(BIO_KEY, requireBiometric = true)
        } catch (e: Exception) { return onDone(false, e.message) }
        prompt(activity, "Zapnúť prihlásenie odtlačkom", "Priložte prst na snímač", cipher,
            onSuccess = { c ->
                runCatching { c.doFinal(token.toByteArray(Charsets.UTF_8)) to c.iv }
                    .onSuccess { (ct, iv) -> session.saveBiometricToken(ct, iv); onDone(true, null) }
                    .onFailure { onDone(false, it.message) }
            },
            onError = { onDone(false, it) })
    }

    /** Prihlásenie / odomknutie: dešifruje uložený token odtlačkom prsta. */
    fun unlock(activity: FragmentActivity, session: SessionStore, onToken: (String) -> Unit, onError: (String, Boolean) -> Unit) {
        val iv = session.biometricIv()
        val ct = session.biometricCipherText()
        if (iv == null || ct == null) return onError("Prihlásenie odtlačkom nie je nastavené", true)
        val cipher = try {
            KeystoreCrypto.decryptCipher(BIO_KEY, iv)
        } catch (e: KeyPermanentlyInvalidatedException) {
            session.clearBiometricToken()
            return onError("Odtlačky v telefóne sa zmenili. Prihláste sa heslom a biometriu zapnite znova.", true)
        } catch (e: Exception) {
            return onError(e.message ?: "Chyba kľúča", true)
        }
        prompt(activity, "Prihlásenie", "Overte sa odtlačkom prsta", cipher,
            onSuccess = { c ->
                runCatching { String(c.doFinal(ct), Charsets.UTF_8) }
                    .onSuccess(onToken)
                    .onFailure { onError("Nepodarilo sa dešifrovať prihlasovacie údaje", false) }
            },
            onError = { onError(it, false) })
    }
}
