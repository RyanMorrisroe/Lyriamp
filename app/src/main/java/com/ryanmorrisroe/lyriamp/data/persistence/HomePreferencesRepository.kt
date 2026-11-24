package com.ryanmorrisroe.lyriamp.data.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryFilter
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItemType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

enum class HomePinKind {
    LIBRARY_ITEM,
    SPECIAL
}

enum class HomeSpecialAction {
    SHUFFLE_TRACKS,
    SHUFFLE_ARTISTS,
    SHUFFLE_ALBUMS,
    FAVORITES
}

enum class HomeDensity {
    COMPACT,
    COMFORTABLE,
    SPACIOUS
}

enum class HomeSectionType {
    RECENTLY_ADDED_ALBUMS,
    MOST_PLAYED_ALBUMS,
    RECENTLY_PLAYED_ALBUMS,
    RECENTLY_ADDED_ARTISTS,
    MOST_PLAYED_ARTISTS,
    RECENTLY_PLAYED_ARTISTS
}

data class HomePin(
    val kind: HomePinKind,
    val title: String,
    val itemId: String? = null,
    val itemType: LmsLibraryItemType? = null,
    val filter: LmsLibraryFilter = LmsLibraryFilter(),
    val specialAction: HomeSpecialAction? = null,
    val artworkId: String? = null,
    val artworkUrl: String? = null
) {
    init {
        require(title.isNotBlank()) { "A Home pin title cannot be blank." }
        when (kind) {
            HomePinKind.LIBRARY_ITEM -> {
                require(!itemId.isNullOrBlank()) { "A library Home pin requires an item ID." }
                require(itemType != null) { "A library Home pin requires an item type." }
            }
            HomePinKind.SPECIAL -> {
                require(specialAction != null) { "A special Home pin requires an action." }
            }
        }
    }

    companion object {
        fun libraryItem(
            itemType: LmsLibraryItemType,
            itemId: String,
            title: String,
            filter: LmsLibraryFilter,
            artworkId: String? = null,
            artworkUrl: String? = null
        ) = HomePin(
            kind = HomePinKind.LIBRARY_ITEM,
            title = title,
            itemId = itemId,
            itemType = itemType,
            filter = filter,
            artworkId = artworkId,
            artworkUrl = artworkUrl
        )

        fun special(action: HomeSpecialAction, title: String) = HomePin(
            kind = HomePinKind.SPECIAL,
            title = title,
            specialAction = action
        )
    }
}

data class HomeSection(
    val type: HomeSectionType,
    val enabled: Boolean = true,
    val limit: Int = 20
) {
    init {
        require(limit in 1..50) { "A Home section limit must be between 1 and 50." }
    }
}

data class HomePreferences(
    val pins: List<HomePin> = emptyList(),
    val sections: List<HomeSection> = defaultHomeSections(),
    val density: HomeDensity = HomeDensity.COMFORTABLE
) {
    fun normalized(): HomePreferences {
        val uniquePins = pins.distinctBy { pin ->
            if (pin.kind == HomePinKind.LIBRARY_ITEM) {
                "library:${pin.itemType}:${pin.itemId}"
            } else {
                "special:${pin.specialAction}"
            }
        }.take(MAX_HOME_PINS)
        val uniqueSections = sections
            .distinctBy { it.type }
            .take(MAX_HOME_SECTIONS)
            .map { it.copy(limit = it.limit.coerceIn(1, 50)) }
        val completeSections = uniqueSections +
            defaultHomeSections()
                .filterNot { default -> uniqueSections.any { it.type == default.type } }
        return copy(pins = uniquePins, sections = completeSections)
    }

    private companion object {
        const val MAX_HOME_PINS = 8
        const val MAX_HOME_SECTIONS = 12
    }
}

fun defaultHomeSections() = listOf(
    HomeSection(HomeSectionType.RECENTLY_ADDED_ALBUMS),
    HomeSection(HomeSectionType.MOST_PLAYED_ALBUMS),
    HomeSection(HomeSectionType.RECENTLY_PLAYED_ALBUMS),
    HomeSection(HomeSectionType.RECENTLY_ADDED_ARTISTS, enabled = false),
    HomeSection(HomeSectionType.MOST_PLAYED_ARTISTS, enabled = false),
    HomeSection(HomeSectionType.RECENTLY_PLAYED_ARTISTS, enabled = false)
)

interface HomePreferencesRepository {
    fun observe(endpointKey: String): Flow<HomePreferences>
    suspend fun setPreferences(endpointKey: String, preferences: HomePreferences)
    suspend fun reset(endpointKey: String)
}

class DataStoreHomePreferencesRepository(
    private val dataStore: DataStore<Preferences>
) : HomePreferencesRepository {
    override fun observe(endpointKey: String): Flow<HomePreferences> {
        require(endpointKey.isNotBlank()) { "A server endpoint key cannot be blank." }
        return dataStore.data.map { preferences ->
            preferences[homeKey(endpointKey)]
                ?.let(::decode)
                ?.normalized()
                ?: HomePreferences()
        }
    }

    override suspend fun setPreferences(endpointKey: String, preferences: HomePreferences) {
        require(endpointKey.isNotBlank()) { "A server endpoint key cannot be blank." }
        val normalized = preferences.normalized()
        dataStore.edit { it[homeKey(endpointKey)] = encode(normalized).toString() }
    }

    override suspend fun reset(endpointKey: String) {
        require(endpointKey.isNotBlank()) { "A server endpoint key cannot be blank." }
        dataStore.edit { it.remove(homeKey(endpointKey)) }
    }

    private fun encode(preferences: HomePreferences) = buildJsonObject {
        put("version", VERSION)
        put("density", preferences.density.name)
        put("pins", buildJsonArray {
            preferences.pins.forEach { pin ->
                add(buildJsonObject {
                    put("kind", pin.kind.name)
                    put("title", pin.title)
                    pin.itemId?.let { put("itemId", it) }
                    pin.itemType?.let { put("itemType", it.name) }
                    pin.specialAction?.let { put("specialAction", it.name) }
                    pin.artworkId?.let { put("artworkId", it) }
                    pin.artworkUrl?.let { put("artworkUrl", it) }
                    put("filter", encodeFilter(pin.filter))
                })
            }
        })
        put("sections", buildJsonArray {
            preferences.sections.forEach { section ->
                add(buildJsonObject {
                    put("type", section.type.name)
                    put("enabled", section.enabled)
                    put("limit", section.limit)
                })
            }
        })
    }

    private fun encodeFilter(filter: LmsLibraryFilter) = buildJsonObject {
        filter.artistId?.let { put("artistId", it) }
        filter.albumId?.let { put("albumId", it) }
        filter.genreId?.let { put("genreId", it) }
        filter.playlistId?.let { put("playlistId", it) }
        filter.folderId?.let { put("folderId", it) }
        filter.favoriteId?.let { put("favoriteId", it) }
    }

    private fun decode(serialized: String): HomePreferences? = runCatching {
        val root = Json.parseToJsonElement(serialized).jsonObject
        require(root.int("version") == VERSION)
        val density = root.string("density")
            ?.let { value -> runCatching { HomeDensity.valueOf(value) }.getOrNull() }
            ?: HomeDensity.COMFORTABLE
        val pins = root["pins"]
            ?.takeUnless { it == JsonNull }
            ?.jsonArray
            ?.mapNotNull(::decodePin)
            .orEmpty()
        val sections = root["sections"]
            ?.takeUnless { it == JsonNull }
            ?.jsonArray
            ?.mapNotNull(::decodeSection)
            .orEmpty()
        HomePreferences(pins, if (sections.isEmpty()) defaultHomeSections() else sections, density)
    }.getOrNull()

    private fun decodePin(element: kotlinx.serialization.json.JsonElement): HomePin? =
        runCatching {
            val value = element.jsonObject
            val kind = value.string("kind")?.let { kind ->
                runCatching { HomePinKind.valueOf(kind) }.getOrNull()
            } ?: return@runCatching null
            val title = value.string("title") ?: return@runCatching null
            when (kind) {
                HomePinKind.LIBRARY_ITEM -> {
                    val itemId = value.string("itemId") ?: return@runCatching null
                    val itemType = value.string("itemType")
                        ?.let { runCatching { LmsLibraryItemType.valueOf(it) }.getOrNull() }
                        ?: return@runCatching null
                    HomePin.libraryItem(
                        itemType = itemType,
                        itemId = itemId,
                        title = title,
                        filter = decodeFilter(value["filter"]),
                        artworkId = value.string("artworkId"),
                        artworkUrl = value.string("artworkUrl")
                    )
                }
                HomePinKind.SPECIAL -> {
                    val action = value.string("specialAction")
                        ?.let { runCatching { HomeSpecialAction.valueOf(it) }.getOrNull() }
                        ?: return@runCatching null
                    HomePin.special(action, title)
                }
            }
        }.getOrNull()

    private fun decodeSection(element: kotlinx.serialization.json.JsonElement): HomeSection? =
        runCatching {
            val value = element.jsonObject
            val type = value.string("type")
                ?.let { runCatching { HomeSectionType.valueOf(it) }.getOrNull() }
                ?: return@runCatching null
            HomeSection(
                type = type,
                enabled = value.boolean("enabled") ?: true,
                limit = value.int("limit")?.coerceIn(1, 50) ?: 20
            )
        }.getOrNull()

    private fun decodeFilter(element: kotlinx.serialization.json.JsonElement?): LmsLibraryFilter {
        val value = element?.takeUnless { it == JsonNull }?.jsonObject ?: return LmsLibraryFilter()
        return LmsLibraryFilter(
            artistId = value.string("artistId"),
            albumId = value.string("albumId"),
            genreId = value.string("genreId"),
            playlistId = value.string("playlistId"),
            folderId = value.string("folderId"),
            favoriteId = value.string("favoriteId")
        )
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.boolean(key: String): Boolean? =
        this[key]?.takeUnless { it == JsonNull }?.jsonPrimitive?.let { primitive ->
            primitive.contentOrNull?.toBooleanStrictOrNull()
        }

    private companion object {
        const val VERSION = 1

        fun homeKey(endpointKey: String): Preferences.Key<String> {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(endpointKey.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return stringPreferencesKey("home_preferences_$digest")
        }
    }
}
