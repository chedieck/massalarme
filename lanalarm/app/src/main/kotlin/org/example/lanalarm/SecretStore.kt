package org.example.lanalarm

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the ontoplano token lives.
 *
 * The token is a bearer credential for the user's whole ontoplano account, so
 * it does not belong in plain SharedPreferences next to the wifi name. It is
 * encrypted with a key held in the Android Keystore — hardware-backed on most
 * phones — which is the platform's answer to "put it in the OS keychain", and
 * needs no third-party dependency to reach.
 *
 * The keystore is not universally reliable: a few OEM builds throw on key
 * generation, and a device that changes its lock screen can invalidate keys. A
 * token the app cannot read is an alarm that silently stops syncing, so a
 * failure falls back to storing the value as-is and says so through
 * [isProtected], which the settings screen surfaces rather than hides.
 */
object SecretStore {

    private const val TAG = "SecretStore"
    private const val KEY_ALIAS = "massalarme_secrets"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /** Marks a value as ciphertext, so a fallback plaintext is never fed to the cipher. */
    private const val PREFIX = "enc.v1:"

    fun put(context: Context, key: String, value: String?) {
        val editor = AppSettings.prefs(context).edit()
        if (value.isNullOrBlank()) {
            editor.remove(key).apply()
            return
        }
        editor.putString(key, encrypt(value) ?: value).apply()
    }

    fun get(context: Context, key: String): String? {
        val stored = AppSettings.prefs(context).getString(key, null) ?: return null
        if (!stored.startsWith(PREFIX)) return stored.takeIf { it.isNotBlank() }
        return decrypt(stored)
    }

    /** Is the stored value actually encrypted, or did the keystore let us down? */
    fun isProtected(context: Context, key: String): Boolean =
        AppSettings.prefs(context).getString(key, null)?.startsWith(PREFIX) == true

    // ─── Crypto ──────────────────────────────────────────────────────

    private fun encrypt(plaintext: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // The IV is generated per encryption and is not secret; it only has to
        // travel with the ciphertext, so it is prepended rather than stored
        // under a second key that could drift out of step with this one.
        val packed = cipher.iv + ciphertext
        PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
    }.onFailure {
        Log.w(TAG, "Keystore unavailable, storing unprotected: ${it.message}")
    }.getOrNull()

    private fun decrypt(stored: String): String? = runCatching {
        val packed = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, packed, 0, IV_BYTES)
        )
        String(cipher.doFinal(packed, IV_BYTES, packed.size - IV_BYTES), Charsets.UTF_8)
    }.onFailure {
        Log.e(TAG, "Could not decrypt stored secret: ${it.message}")
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            // Explicitly *not* setUserAuthenticationRequired: an alarm has to be
            // able to sync a weigh-in while the phone is locked on a bedside
            // table, which is precisely when it cannot be unlocked.
            .setRandomizedEncryptionRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setInvalidatedByBiometricEnrollment(false)
                }
            }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }
}
