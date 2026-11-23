package com.ryanmorrisroe.lyriamp.data.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.ryanmorrisroe.lyriamp.data.lms.SavedLmsPlayerGroup
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

interface PlayerGroupRepository {
    fun observeGroups(endpointKey: String): Flow<List<SavedLmsPlayerGroup>>
    suspend fun saveGroup(
        endpointKey: String,
        name: String,
        playerIds: List<String>
    ): SavedLmsPlayerGroup
    suspend fun updateGroup(
        endpointKey: String,
        groupId: String,
        name: String,
        playerIds: List<String>
    ): Boolean
    suspend fun removeGroup(endpointKey: String, groupId: String): Boolean
}

class DataStorePlayerGroupRepository(
    private val dataStore: DataStore<Preferences>
) : PlayerGroupRepository {
    override fun observeGroups(endpointKey: String): Flow<List<SavedLmsPlayerGroup>> {
        require(endpointKey.isNotBlank()) { "Server endpoint cannot be blank." }
        return dataStore.data.map { preferences ->
            preferences[GROUP_IDS].orEmpty().mapNotNull { id ->
                val storedEndpointKey = preferences[groupEndpointKey(id)]
                    ?: return@mapNotNull null
                if (storedEndpointKey != endpointKey) return@mapNotNull null
                val name = preferences[nameKey(id)]
                    ?: return@mapNotNull null
                val playerIds = preferences[playerIdsKey(id)]
                    ?.decodePlayerIds()
                    ?: return@mapNotNull null
                SavedLmsPlayerGroup(id, storedEndpointKey, name, playerIds)
            }.sortedWith(
                compareBy<SavedLmsPlayerGroup, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .thenBy { it.id }
            )
        }
    }

    override suspend fun saveGroup(
        endpointKey: String,
        name: String,
        playerIds: List<String>
    ): SavedLmsPlayerGroup {
        require(endpointKey.isNotBlank()) { "Server endpoint cannot be blank." }
        val group = SavedLmsPlayerGroup(
            id = UUID.randomUUID().toString(),
            endpointKey = endpointKey,
            name = name.trim(),
            playerIds = playerIds.toList()
        )
        dataStore.edit { preferences ->
            val ids = preferences[GROUP_IDS].orEmpty()
            preferences[GROUP_IDS] = ids + group.id
            preferences[groupEndpointKey(group.id)] = group.endpointKey
            preferences[nameKey(group.id)] = group.name
            preferences[playerIdsKey(group.id)] = group.playerIds.encodePlayerIds()
        }
        return group
    }

    override suspend fun updateGroup(
        endpointKey: String,
        groupId: String,
        name: String,
        playerIds: List<String>
    ): Boolean {
        require(endpointKey.isNotBlank()) { "Server endpoint cannot be blank." }
        require(groupId.isNotBlank()) { "Player group ID cannot be blank." }
        val group = SavedLmsPlayerGroup(
            id = groupId,
            endpointKey = endpointKey,
            name = name.trim(),
            playerIds = playerIds.toList()
        )
        var updated = false
        dataStore.edit { preferences ->
            if (preferences[groupEndpointKey(groupId)] != endpointKey) return@edit
            preferences[nameKey(groupId)] = group.name
            preferences[playerIdsKey(groupId)] = group.playerIds.encodePlayerIds()
            updated = true
        }
        return updated
    }

    override suspend fun removeGroup(endpointKey: String, groupId: String): Boolean {
        require(endpointKey.isNotBlank()) { "Server endpoint cannot be blank." }
        var removed = false
        dataStore.edit { preferences ->
            if (preferences[groupEndpointKey(groupId)] != endpointKey) return@edit
            preferences[GROUP_IDS] = preferences[GROUP_IDS].orEmpty() - groupId
            preferences.remove(groupEndpointKey(groupId))
            preferences.remove(nameKey(groupId))
            preferences.remove(playerIdsKey(groupId))
            removed = true
        }
        return removed
    }

    private fun List<String>.encodePlayerIds(): String =
        buildJsonArray { forEach { add(JsonPrimitive(it)) } }.toString()

    private fun String.decodePlayerIds(): List<String> {
        val array = try {
            Json.parseToJsonElement(this) as? JsonArray
                ?: throw IllegalStateException("Saved player group has invalid members.")
        } catch (exception: SerializationException) {
            throw IllegalStateException("Saved player group has invalid members.", exception)
        }
        return array.map { element ->
            element.jsonPrimitive.contentOrNull
                ?: throw IllegalStateException("Saved player group has an invalid player ID.")
        }
    }

    private companion object {
        val GROUP_IDS = stringSetPreferencesKey("player_group_ids")

        fun groupEndpointKey(id: String) = stringPreferencesKey("player_group_${id}_endpoint")
        fun nameKey(id: String) = stringPreferencesKey("player_group_${id}_name")
        fun playerIdsKey(id: String) = stringPreferencesKey("player_group_${id}_members")
    }
}
