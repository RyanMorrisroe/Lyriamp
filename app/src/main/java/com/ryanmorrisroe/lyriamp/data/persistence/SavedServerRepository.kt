package com.ryanmorrisroe.lyriamp.data.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.savedServerDataStore: DataStore<Preferences> by preferencesDataStore(name = "saved_servers")

data class SavedServerProfile(
    val id: String,
    val host: String,
    val port: Int,
    val useHttps: Boolean,
    val hasCredentials: Boolean
)

interface CredentialCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

interface SavedServerRepository {
    fun observeServers(): Flow<List<SavedServerProfile>>
    suspend fun save(connection: LmsServerConnection): SavedServerProfile
    suspend fun getConnection(id: String): LmsServerConnection?
    suspend fun remove(id: String)
}

class DataStoreSavedServerRepository(
    private val dataStore: DataStore<Preferences>,
    private val credentialCipher: CredentialCipher
) : SavedServerRepository {
    override fun observeServers(): Flow<List<SavedServerProfile>> = dataStore.data.map { preferences ->
        preferences[SERVER_IDS].orEmpty().mapNotNull { id ->
            val host = preferences[hostKey(id)] ?: return@mapNotNull null
            val port = preferences[portKey(id)] ?: return@mapNotNull null
            SavedServerProfile(
                id = id,
                host = host,
                port = port,
                useHttps = preferences[httpsKey(id)] ?: false,
                hasCredentials = preferences[credentialsKey(id)] != null
            )
        }.sortedWith(
            compareBy<SavedServerProfile, String>(String.CASE_INSENSITIVE_ORDER) { it.host }
                .thenBy { it.port }
        )
    }

    override suspend fun save(connection: LmsServerConnection): SavedServerProfile {
        val normalized = connection.copy(
            host = connection.host.trim(),
            username = connection.username?.takeIf(String::isNotBlank),
            password = connection.password?.takeIf(String::isNotBlank)
        )
        normalized.jsonRpcUrl()
        val encryptedCredentials = if (normalized.username != null || normalized.password != null) {
            Base64.getEncoder().encodeToString(credentialCipher.encrypt(encodeCredentials(normalized)))
        } else {
            null
        }
        var savedProfile: SavedServerProfile? = null

        dataStore.edit { preferences ->
            val ids = preferences[SERVER_IDS].orEmpty()
            val existingId = ids.firstOrNull { id ->
                preferences[hostKey(id)]?.equals(normalized.host, ignoreCase = true) == true &&
                    preferences[portKey(id)] == normalized.port &&
                    preferences[httpsKey(id)] == normalized.useHttps
            }
            val id = existingId ?: UUID.randomUUID().toString()
            preferences[SERVER_IDS] = ids + id
            preferences[hostKey(id)] = normalized.host
            preferences[portKey(id)] = normalized.port
            preferences[httpsKey(id)] = normalized.useHttps
            if (encryptedCredentials == null) {
                preferences.remove(credentialsKey(id))
            } else {
                preferences[credentialsKey(id)] = encryptedCredentials
            }
            savedProfile = SavedServerProfile(
                id,
                normalized.host,
                normalized.port,
                normalized.useHttps,
                encryptedCredentials != null
            )
        }

        return checkNotNull(savedProfile)
    }

    override suspend fun getConnection(id: String): LmsServerConnection? {
        val preferences = dataStore.data.first()
        val host = preferences[hostKey(id)] ?: return null
        val port = preferences[portKey(id)] ?: return null
        val credentialBlob = preferences[credentialsKey(id)]
        val credentials = credentialBlob?.let {
            decodeCredentials(credentialCipher.decrypt(Base64.getDecoder().decode(it)))
        }
        return LmsServerConnection(
            host = host,
            port = port,
            useHttps = preferences[httpsKey(id)] ?: false,
            username = credentials?.first,
            password = credentials?.second
        )
    }

    override suspend fun remove(id: String) {
        dataStore.edit { preferences ->
            preferences[SERVER_IDS] = preferences[SERVER_IDS].orEmpty() - id
            preferences.remove(hostKey(id))
            preferences.remove(portKey(id))
            preferences.remove(httpsKey(id))
            preferences.remove(credentialsKey(id))
        }
    }

    private fun encodeCredentials(connection: LmsServerConnection): ByteArray {
        val username = connection.username?.toByteArray(StandardCharsets.UTF_8)
        val password = connection.password?.toByteArray(StandardCharsets.UTF_8)
        val usernameSize = username?.size ?: -1
        val passwordSize = password?.size ?: -1
        return ByteBuffer.allocate(8 + maxOf(usernameSize, 0) + maxOf(passwordSize, 0))
            .putInt(usernameSize)
            .apply { username?.let(::put) }
            .putInt(passwordSize)
            .apply { password?.let(::put) }
            .array()
    }

    private fun decodeCredentials(bytes: ByteArray): Pair<String?, String?> {
        val buffer = ByteBuffer.wrap(bytes)
        fun readValue(): String? {
            require(buffer.remaining() >= Int.SIZE_BYTES) { "Invalid saved credentials." }
            val length = buffer.int
            if (length == -1) return null
            require(length >= 0 && length <= buffer.remaining()) { "Invalid saved credentials." }
            val value = ByteArray(length)
            buffer.get(value)
            return String(value, StandardCharsets.UTF_8)
        }
        val credentials = readValue() to readValue()
        require(!buffer.hasRemaining()) { "Invalid saved credentials." }
        return credentials
    }

    private companion object {
        val SERVER_IDS = stringSetPreferencesKey("server_ids")

        fun hostKey(id: String) = stringPreferencesKey("server_${id}_host")
        fun portKey(id: String) = intPreferencesKey("server_${id}_port")
        fun httpsKey(id: String) = booleanPreferencesKey("server_${id}_https")
        fun credentialsKey(id: String) = stringPreferencesKey("server_${id}_credentials")
    }
}