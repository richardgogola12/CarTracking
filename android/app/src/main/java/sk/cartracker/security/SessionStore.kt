package sk.cartracker.security

import android.content.Context
import android.util.Base64

/**
 * Prihlasovací token je šifrovaný kľúčom v Android Keystore.
 *  - "token"      – aktívna relácia (čitateľná aj na pozadí, potrebuje ju synchronizácia)
 *  - "bio_token"  – kópia tokenu zašifrovaná kľúčom, ktorý odomkne iba odtlačok prsta
 */
class SessionStore(context: Context) {
    data class User(val id: Int, val name: String, val email: String)

    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    val isLoggedIn: Boolean get() = prefs.contains("token")

    fun token(): String? = prefs.getString("token", null)?.let { KeystoreCrypto.decrypt(SESSION_KEY, it) }

    fun user(): User? {
        val id = prefs.getInt("user_id", -1)
        if (id < 0 || !isLoggedIn) return null
        return User(id, prefs.getString("user_name", "") ?: "", prefs.getString("user_email", "") ?: "")
    }

    fun lastEmail(): String = prefs.getString("last_email", "") ?: ""

    fun save(token: String, user: User) {
        prefs.edit()
            .putString("token", KeystoreCrypto.encrypt(SESSION_KEY, token))
            .putInt("user_id", user.id)
            .putString("user_name", user.name)
            .putString("user_email", user.email)
            .putString("last_email", user.email)
            .apply()
    }

    fun saveToken(token: String) {
        prefs.edit().putString("token", KeystoreCrypto.encrypt(SESSION_KEY, token)).apply()
    }

    fun clearSession() {
        prefs.edit().remove("token").remove("user_id").remove("user_name").remove("user_email").apply()
    }

    // ---- biometria ----
    fun hasBiometricToken(): Boolean = prefs.contains("bio_token") && prefs.contains("bio_iv")
    fun biometricIv(): ByteArray? = prefs.getString("bio_iv", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
    fun biometricCipherText(): ByteArray? = prefs.getString("bio_token", null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun saveBiometricToken(cipherText: ByteArray, iv: ByteArray) {
        prefs.edit()
            .putString("bio_token", Base64.encodeToString(cipherText, Base64.NO_WRAP))
            .putString("bio_iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()
    }

    fun clearBiometricToken() {
        prefs.edit().remove("bio_token").remove("bio_iv").apply()
        KeystoreCrypto.deleteKey(BiometricAuth.BIO_KEY)
    }

    companion object { private const val SESSION_KEY = "ct_session_key" }
}
