package com.ryanmorrisroe.lyriamp.data.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibrarySort
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibrarySortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.displayPreferencesDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "display_preferences")

enum class Appearance { SYSTEM, LIGHT, DARK }

enum class LibraryLayout { COVER_WALL, COMPACT, COMFORTABLE }

enum class LibraryViewType(val allowsCoverWall: Boolean) {
    ARTISTS(true),
    ALBUMS(true),
    GENRES(false),
    SONGS(false),
    PLAYLISTS(false),
    SEARCH(false)
}

data class LibrarySortPreference(
    val sort: LmsLibrarySort,
    val order: LmsLibrarySortOrder = LmsLibrarySortOrder.ASCENDING
)

data class DisplayPreferences(
    val appearance: Appearance = Appearance.SYSTEM,
    val layouts: Map<LibraryViewType, LibraryLayout> = emptyMap(),
    val sorts: Map<LibraryViewType, LibrarySortPreference> = emptyMap()
) {
    fun layoutFor(type: LibraryViewType): LibraryLayout =
        layouts[type] ?: LibraryLayout.COMFORTABLE

    fun sortFor(type: LibraryViewType): LibrarySortPreference? =
        sorts[type] ?: when (type) {
            LibraryViewType.ARTISTS -> LibrarySortPreference(LmsLibrarySort.ARTIST)
            LibraryViewType.ALBUMS -> LibrarySortPreference(LmsLibrarySort.ALBUM)
            LibraryViewType.SONGS -> LibrarySortPreference(LmsLibrarySort.TITLE)
            LibraryViewType.GENRES,
            LibraryViewType.PLAYLISTS,
            LibraryViewType.SEARCH -> null
        }
}

interface DisplayPreferencesRepository {
    fun observe(): Flow<DisplayPreferences>
    suspend fun setAppearance(appearance: Appearance)
    suspend fun setLayout(type: LibraryViewType, layout: LibraryLayout)
    suspend fun setSort(type: LibraryViewType, preference: LibrarySortPreference)
}

class DataStoreDisplayPreferencesRepository(
    private val dataStore: DataStore<Preferences>
) : DisplayPreferencesRepository {
    override fun observe(): Flow<DisplayPreferences> = dataStore.data.map { preferences ->
        DisplayPreferences(
            appearance = preferences[APPEARANCE]?.let(Appearance::valueOf) ?: Appearance.SYSTEM,
            layouts = LibraryViewType.entries.mapNotNull { type ->
                preferences[layoutKey(type)]?.let { value ->
                    val layout = LibraryLayout.valueOf(value)
                    require(layout != LibraryLayout.COVER_WALL || type.allowsCoverWall)
                    type to layout
                }
            }.toMap(),
            sorts = LibraryViewType.entries.mapNotNull { type ->
                preferences[sortKey(type)]?.let { value ->
                    val sort = LmsLibrarySort.valueOf(value)
                    val order = preferences[sortOrderKey(type)]
                        ?.let(LmsLibrarySortOrder::valueOf)
                        ?: LmsLibrarySortOrder.ASCENDING
                    type to LibrarySortPreference(sort, order)
                }
            }.toMap()
        )
    }

    override suspend fun setAppearance(appearance: Appearance) {
        dataStore.edit { it[APPEARANCE] = appearance.name }
    }

    override suspend fun setLayout(type: LibraryViewType, layout: LibraryLayout) {
        require(layout != LibraryLayout.COVER_WALL || type.allowsCoverWall)
        dataStore.edit { it[layoutKey(type)] = layout.name }
    }

    override suspend fun setSort(type: LibraryViewType, preference: LibrarySortPreference) {
        require(type != LibraryViewType.GENRES)
        require(type != LibraryViewType.SEARCH)
        require(type != LibraryViewType.PLAYLISTS)
        require(preference.sort in preference.sort.supportedFor(type))
        dataStore.edit {
            it[sortKey(type)] = preference.sort.name
            it[sortOrderKey(type)] = preference.order.name
        }
    }

    private companion object {
        val APPEARANCE = stringPreferencesKey("appearance")
        fun layoutKey(type: LibraryViewType) = stringPreferencesKey("layout_${type.name.lowercase()}")
        fun sortKey(type: LibraryViewType) = stringPreferencesKey("sort_${type.name.lowercase()}")
        fun sortOrderKey(type: LibraryViewType) = stringPreferencesKey("sort_order_${type.name.lowercase()}")
    }
}

private fun LmsLibrarySort.supportedFor(type: LibraryViewType): List<LmsLibrarySort> = when (type) {
    LibraryViewType.ARTISTS -> listOf(
        LmsLibrarySort.ARTIST,
        LmsLibrarySort.NEW,
        LmsLibrarySort.PLAY_COUNT,
        LmsLibrarySort.POPULAR,
        LmsLibrarySort.RECENTLY_PLAYED
    )
    LibraryViewType.ALBUMS -> listOf(
        LmsLibrarySort.ALBUM,
        LmsLibrarySort.ARTIST_ALBUM,
        LmsLibrarySort.YEAR_ALBUM,
        LmsLibrarySort.YEAR_ARTIST_ALBUM,
        LmsLibrarySort.ART_FLOW,
        LmsLibrarySort.NEW,
        LmsLibrarySort.CHANGED,
        LmsLibrarySort.PLAY_COUNT,
        LmsLibrarySort.POPULAR,
        LmsLibrarySort.RECENTLY_PLAYED,
        LmsLibrarySort.RANDOM
    )
    LibraryViewType.SONGS -> listOf(
        LmsLibrarySort.TITLE,
        LmsLibrarySort.TRACK_NUMBER,
        LmsLibrarySort.ALBUM_TRACK
    )
    LibraryViewType.GENRES,
    LibraryViewType.PLAYLISTS,
    LibraryViewType.SEARCH -> emptyList()
}
