package com.ryanmorrisroe.lyriamp.data.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.displayPreferencesDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "display_preferences")

enum class Appearance { SYSTEM, LIGHT, DARK }

enum class LibraryLayout { COVER_WALL, COMPACT, COMFORTABLE }

enum class LibraryViewType(val allowsCoverWall: Boolean) {
    ARTISTS(true),
    ALBUMS(true),
    GENRES(true),
    SONGS(false),
    PLAYLISTS(false),
    SEARCH(false)
}

data class DisplayPreferences(
    val appearance: Appearance = Appearance.SYSTEM,
    val layouts: Map<LibraryViewType, LibraryLayout> = emptyMap()
) {
    fun layoutFor(type: LibraryViewType): LibraryLayout =
        layouts[type] ?: LibraryLayout.COMFORTABLE
}

interface DisplayPreferencesRepository {
    fun observe(): Flow<DisplayPreferences>
    suspend fun setAppearance(appearance: Appearance)
    suspend fun setLayout(type: LibraryViewType, layout: LibraryLayout)
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

    private companion object {
        val APPEARANCE = stringPreferencesKey("appearance")
        fun layoutKey(type: LibraryViewType) = stringPreferencesKey("layout_${type.name.lowercase()}")
    }
}
