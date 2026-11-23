package com.ryanmorrisroe.lyriamp.data.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryFilter
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryQuery
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface LastSessionRepository {
    suspend fun load(): LastSessionSnapshot?
    suspend fun save(snapshot: LastSessionSnapshot)
    suspend fun clear()
}

data class LastSessionSnapshot(
    val serverProfileId: String,
    val playerId: String,
    val locationStack: List<LastSessionLocation>,
    val searchText: String,
    val selectedPlayerIds: List<String> = emptyList(),
    val scrollAnchor: LastSessionScrollAnchor? = null,
    val activeTab: String? = null,
    val lastVisitedTab: String? = null,
    val tabs: Map<String, LastSessionTabSnapshot> = emptyMap()
)

data class LastSessionTabSnapshot(
    val locationStack: List<LastSessionLocation>,
    val scrollAnchor: LastSessionScrollAnchor? = null
)

data class LastSessionLocation(
    val title: String,
    val query: LmsLibraryQuery? = null,
    val filter: LmsLibraryFilter = LmsLibraryFilter()
)

data class LastSessionScrollAnchor(
    val locationKey: String,
    val searchText: String,
    val itemId: String?,
    val index: Int,
    val offsetPx: Int
)

class DataStoreLastSessionRepository(
    private val dataStore: DataStore<Preferences>
) : LastSessionRepository {
    override suspend fun load(): LastSessionSnapshot? {
        val serialized = dataStore.data.first()[LAST_SESSION] ?: return null
        return decode(serialized)
    }

    override suspend fun save(snapshot: LastSessionSnapshot) {
        dataStore.edit { it[LAST_SESSION] = encode(snapshot).toString() }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(LAST_SESSION) }
    }

    private fun encode(snapshot: LastSessionSnapshot) = buildJsonObject {
        put("version", VERSION)
        put("serverProfileId", snapshot.serverProfileId)
        put("playerId", snapshot.playerId)
        put("selectedPlayerIds", buildJsonArray {
            snapshot.selectedPlayerIds.forEach { add(JsonPrimitive(it)) }
        })
        put("searchText", snapshot.searchText)
        put("locations", encodeLocations(snapshot.locationStack))
        snapshot.scrollAnchor?.let { put("scrollAnchor", encodeAnchor(it)) }
        snapshot.activeTab?.let { put("activeTab", it) }
        snapshot.lastVisitedTab?.let { put("lastVisitedTab", it) }
        put("tabs", buildJsonObject {
            snapshot.tabs.forEach { (tab, state) ->
                put(tab, buildJsonObject {
                    put("locations", encodeLocations(state.locationStack))
                    state.scrollAnchor?.let { put("scrollAnchor", encodeAnchor(it)) }
                })
            }
        })
    }

    private fun encodeLocations(locations: List<LastSessionLocation>) = buildJsonArray {
        locations.forEach { location ->
            add(buildJsonObject {
                put("title", location.title)
                location.query?.let { put("query", it.name) }
                location.filter.artistId?.let { put("artistId", it) }
                location.filter.albumId?.let { put("albumId", it) }
                location.filter.genreId?.let { put("genreId", it) }
                location.filter.playlistId?.let { put("playlistId", it) }
                location.filter.folderId?.let { put("folderId", it) }
            })
        }
    }

    private fun encodeAnchor(anchor: LastSessionScrollAnchor) = buildJsonObject {
        put("locationKey", anchor.locationKey)
        put("searchText", anchor.searchText)
        if (anchor.itemId == null) put("itemId", JsonNull) else put("itemId", anchor.itemId)
        put("index", anchor.index)
        put("offsetPx", anchor.offsetPx)
    }

    private fun decode(serialized: String): LastSessionSnapshot? = runCatching {
        val root = Json.parseToJsonElement(serialized).jsonObject
        require(root.int("version") == VERSION)
        val serverProfileId = requireNotNull(root.string("serverProfileId"))
        val playerId = requireNotNull(root.string("playerId"))
        val locations = decodeLocations(root["locations"]!!.jsonArray)
        val scroll = decodeAnchor(root["scrollAnchor"])
        val tabs = root["tabs"]?.jsonObject?.mapValues { (_, element) ->
            val tab = element.jsonObject
            LastSessionTabSnapshot(
                locationStack = decodeLocations(tab["locations"]!!.jsonArray),
                scrollAnchor = decodeAnchor(tab["scrollAnchor"])
            )
        }.orEmpty()
        LastSessionSnapshot(
            serverProfileId = serverProfileId,
            playerId = playerId,
            selectedPlayerIds = root["selectedPlayerIds"]?.jsonArray?.map { element ->
                requireNotNull(element.jsonPrimitive.contentOrNull)
            }.orEmpty(),
            locationStack = locations,
            searchText = root.string("searchText").orEmpty(),
            scrollAnchor = scroll,
            activeTab = root.string("activeTab"),
            lastVisitedTab = root.string("lastVisitedTab"),
            tabs = tabs
        )
    }.getOrNull()

    private fun decodeLocations(locations: JsonArray): List<LastSessionLocation> =
        locations.map { element ->
            val value = element.jsonObject
            LastSessionLocation(
                title = requireNotNull(value.string("title")),
                query = value.string("query")?.let(LmsLibraryQuery::valueOf),
                filter = LmsLibraryFilter(
                    artistId = value.string("artistId"),
                    albumId = value.string("albumId"),
                    genreId = value.string("genreId"),
                    playlistId = value.string("playlistId"),
                    folderId = value.string("folderId")
                )
            )
        }

    private fun decodeAnchor(element: kotlinx.serialization.json.JsonElement?): LastSessionScrollAnchor? =
        element?.takeUnless { it == JsonNull }?.jsonObject?.let { value ->
            LastSessionScrollAnchor(
                locationKey = requireNotNull(value.string("locationKey")),
                searchText = value.string("searchText").orEmpty(),
                itemId = value.string("itemId"),
                index = value.int("index")?.coerceAtLeast(0) ?: 0,
                offsetPx = value.int("offsetPx")?.coerceAtLeast(0) ?: 0
            )
        }

    private fun JsonObject.string(key: String): String? =
        this[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private companion object {
        const val VERSION = 1
        val LAST_SESSION = stringPreferencesKey("last_session_snapshot")
    }
}
