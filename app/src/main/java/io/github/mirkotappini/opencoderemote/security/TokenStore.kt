package io.github.mirkotappini.opencoderemote.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credenziali per il foreground service.
 *
 * Il `toString` è oscurato: la password non deve mai finire in un log o in
 * un'eccezione. Il contenuto è cifrato AES/GCM con una chiave non esportabile
 * in AndroidKeyStore; su disco resta solo IV+ciphertext in base64.
 */
class StoredCredentials(
    val baseUrl: String,
    val user: String,
    val password: String,
) {
    override fun toString(): String =
        "StoredCredentials(baseUrl=$baseUrl, user=$user, password=***)"
}

@Serializable
private data class StoredToken(
    val baseUrl: String,
    val user: String,
    val password: String,
)

/**
 * Persistenza cifrata delle credenziali in preferenze private.
 *
 * AES/GCM con chiave `opencode-token` in AndroidKeyStore. Qualunque errore di
 * cifratura/decifratura viene degradato a "nessun token": mai un crash e mai
 * il contenuto in chiaro su disco. Non testabile in JVM (Keystore assente).
 */
class TokenStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun save(credentials: StoredCredentials) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val plain = JSON.encodeToString(
                StoredToken.serializer(),
                StoredToken(
                    baseUrl = credentials.baseUrl,
                    user = credentials.user,
                    password = credentials.password,
                ),
            ).toByteArray(Charsets.UTF_8)
            val encrypted = cipher.doFinal(plain)
            val payload = b64(cipher.iv) + ":" + b64(encrypted)
            // Credenziali nuove = pairing rifatto: via il blocco dei retry automatici.
            prefs.edit()
                .putString(KEY_PAYLOAD, payload)
                .putBoolean(KEY_AUTH_REJECTED, false)
                .apply()
        } catch (_: Exception) {
            // Se la cifratura fallisce non lasciamo credenziali stantie.
            prefs.edit().remove(KEY_PAYLOAD).apply()
        }
    }

    @Synchronized
    fun load(): StoredCredentials? {
        val payload = prefs.getString(KEY_PAYLOAD, null) ?: return null
        val parts = payload.split(":", limit = 2)
        if (parts.size != 2) return null
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(TAG_BITS, iv),
            )
            val plain = cipher.doFinal(encrypted)
            val token = JSON.decodeFromString<StoredToken>(
                String(plain, Charsets.UTF_8),
            )
            if (token.baseUrl.isBlank() || token.password.isEmpty()) {
                null
            } else {
                StoredCredentials(token.baseUrl, token.user, token.password)
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clear() {
        prefs.edit()
            .remove(KEY_PAYLOAD)
            .remove(KEY_AUTH_REJECTED)
            .apply()
    }

    /**
     * M5: il server ha rifiutato il token (401). Il token resta — serve a
     * precompilare il setup — ma all'avvio non si ritenta più da soli.
     */
    @Synchronized
    fun markAuthRejected() {
        prefs.edit().putBoolean(KEY_AUTH_REJECTED, true).apply()
    }

    @Synchronized
    fun isAuthRejected(): Boolean = prefs.getBoolean(KEY_AUTH_REJECTED, false)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "opencode-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val PREFS = "opencode_secure_credentials"
        const val KEY_PAYLOAD = "credentials_v1"
        const val KEY_AUTH_REJECTED = "auth_rejected_v1"

        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
