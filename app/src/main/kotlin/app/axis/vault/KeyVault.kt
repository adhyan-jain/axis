package app.axis.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import axis.core.Sensitive
import axis.llm.CostClass
import axis.llm.KeyEntry
import axis.llm.Provider
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores API keys encrypted with a non-exportable Android Keystore key. Nothing is written in plain text. */
class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("axis_vault", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val raw = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
            .apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw.copyOfRange(0, IV_LEN))) }
        String(cipher.doFinal(raw.copyOfRange(IV_LEN, raw.size)))
    }.getOrNull()

    private fun ids(): List<String> = prefs.getString("ids", "")!!.split(',').filter { it.isNotBlank() }

    /** Returns an error message for the user, or null when the key was saved. */
    fun add(provider: Provider, rawKey: String): String? {
        val key = rawKey.trim()
        validate(key)?.let { return it }
        if (list().any { it.key.reveal() == key }) return "That key is already added."
        val id = UUID.randomUUID().toString().take(8)
        prefs.edit()
            .putString("k.$id", encrypt(key))
            .putString("p.$id", provider.name)
            .putString("ids", (ids() + id).joinToString(","))
            .apply()
        return null
    }

    fun remove(id: String) {
        prefs.edit().remove("k.$id").remove("p.$id").putString("ids", ids().filter { it != id }.joinToString(",")).apply()
    }

    fun list(): List<KeyEntry> = ids().mapIndexedNotNull { index, id ->
        val key = prefs.getString("k.$id", null)?.let(::decrypt) ?: return@mapIndexedNotNull null
        val provider = runCatching { Provider.valueOf(prefs.getString("p.$id", "")!!) }.getOrNull() ?: return@mapIndexedNotNull null
        KeyEntry(id, provider, Sensitive(key), CostClass.FREE, index)
    }

    /** Small encrypted values such as the GitHub token or remembered facts. */
    fun putSecret(name: String, value: String) { prefs.edit().putString("s.$name", encrypt(value)).apply() }
    fun getSecret(name: String): String? = prefs.getString("s.$name", null)?.let(::decrypt)
    fun deleteSecret(name: String) { prefs.edit().remove("s.$name").apply() }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "axis_vault_key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12

        fun validate(key: String): String? = when {
            key.isEmpty() -> "Paste your key first."
            key.any { it.isWhitespace() } -> "A key has no spaces. Check what you pasted."
            key.length < 20 -> "That looks too short to be a key."
            else -> null
        }
    }
}
