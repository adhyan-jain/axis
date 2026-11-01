package app.axis.device

import app.axis.vault.KeyVault
import axis.agent.MutableFactStorage

/** Remembered facts, one per line, encrypted under the Keystore key. */
class EncryptedFactStorage(private val vault: KeyVault) : MutableFactStorage {
    override fun load(): List<String> = vault.getSecret(NAME)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
    override fun save(facts: List<String>) = vault.putSecret(NAME, facts.joinToString("\n") { it.replace('\n', ' ') })
    private companion object { const val NAME = "facts" }
}
