package com.ryanmorrisroe.lyriamp.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayerStore
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryFilter
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItem
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItemType
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryQuery
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibrarySort
import com.ryanmorrisroe.lyriamp.data.lms.LmsShuffleScope
import com.ryanmorrisroe.lyriamp.data.lms.endpointKey
import com.ryanmorrisroe.lyriamp.data.persistence.HomeDensity
import com.ryanmorrisroe.lyriamp.data.persistence.HomePin
import com.ryanmorrisroe.lyriamp.data.persistence.HomePreferences
import com.ryanmorrisroe.lyriamp.data.persistence.HomePreferencesRepository
import com.ryanmorrisroe.lyriamp.data.persistence.HomeSection
import com.ryanmorrisroe.lyriamp.data.persistence.HomeSectionType
import com.ryanmorrisroe.lyriamp.data.persistence.HomeSpecialAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeShelfState(
    val items: List<LmsLibraryItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

data class HomeUiState(
    val endpointKey: String? = null,
    val preferences: HomePreferences = HomePreferences(),
    val shelves: Map<HomeSectionType, HomeShelfState> = emptyMap(),
    val pinCandidates: List<LmsLibraryItem> = emptyList(),
    val pinCandidateType: LmsLibraryItemType? = null,
    val isLoadingPinCandidates: Boolean = false,
    val pinSearchError: String? = null,
    val isExecutingAction: Boolean = false,
    val actionMessage: String? = null
)

class HomeViewModel(
    private val libraryGateway: LmsLibraryGateway,
    private val activePlayerStore: LmsActivePlayerStore,
    private val preferencesRepository: HomePreferencesRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow(HomeUiState())
    val state = mutableState.asStateFlow()
    private var preferencesJob: Job? = null
    private var playerObservationJob: Job? = null
    private val shelfJobs = mutableMapOf<HomeSectionType, Job>()
    private var pinSearchJob: Job? = null

    init {
        playerObservationJob = viewModelScope.launch {
            activePlayerStore.activePlayer.collect { player ->
                preferencesJob?.cancel()
                shelfJobs.values.forEach(Job::cancel)
                shelfJobs.clear()
                pinSearchJob?.cancel()
                if (player == null) {
                    mutableState.update {
                        it.copy(
                            endpointKey = null,
                            preferences = HomePreferences(),
                            shelves = emptyMap(),
                            pinCandidates = emptyList(),
                            isLoadingPinCandidates = false,
                            pinSearchError = null
                        )
                    }
                    return@collect
                }
                val endpointKey = try {
                    player.connection.endpointKey()
                } catch (_: IllegalArgumentException) {
                    mutableState.update {
                        it.copy(
                            endpointKey = null,
                            preferences = HomePreferences(),
                            shelves = emptyMap(),
                            actionMessage = "Could not identify this server for Home settings."
                        )
                    }
                    return@collect
                }
                mutableState.update { it.copy(endpointKey = endpointKey) }
                preferencesJob = viewModelScope.launch {
                    try {
                        preferencesRepository.observe(endpointKey).collect { preferences ->
                            mutableState.update {
                                it.copy(
                                    endpointKey = endpointKey,
                                    preferences = preferences.normalized(),
                                    actionMessage = null
                                )
                            }
                            reloadShelves(player, preferences.normalized())
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        mutableState.update {
                            it.copy(actionMessage = "Could not read Home settings.")
                        }
                    }
                }
            }
        }
    }

    fun retryShelf(sectionType: HomeSectionType) {
        val player = activePlayerStore.activePlayer.value ?: return
        val preferences = mutableState.value.preferences
        reloadShelf(player, sectionType, preferences.sections.firstOrNull { it.type == sectionType })
    }

    fun setDensity(density: HomeDensity) = savePreferences {
        it.copy(density = density)
    }

    fun setSection(section: HomeSection) = savePreferences { preferences ->
        val sections = preferences.sections.toMutableList()
        val index = sections.indexOfFirst { it.type == section.type }
        if (index >= 0) {
            sections[index] = section
        } else {
            sections.add(section)
        }
        preferences.copy(sections = sections).normalized()
    }

    fun moveSection(fromIndex: Int, toIndex: Int) = savePreferences { preferences ->
        val sections = preferences.sections.toMutableList()
        if (fromIndex !in sections.indices || toIndex !in sections.indices) return@savePreferences preferences
        val moved = sections.removeAt(fromIndex)
        sections.add(toIndex, moved)
        preferences.copy(sections = sections)
    }

    fun addPin(pin: HomePin) = savePreferences { preferences ->
        if (preferences.pins.any { existing ->
                if (pin.kind == existing.kind && pin.kind.name == "LIBRARY_ITEM") {
                    pin.itemType == existing.itemType && pin.itemId == existing.itemId
                } else if (pin.kind == existing.kind) {
                    pin.specialAction == existing.specialAction
                } else {
                    false
                }
            }) {
            preferences
        } else {
            preferences.copy(pins = (preferences.pins + pin).take(8))
        }
    }

    fun removePin(pin: HomePin) = savePreferences { preferences ->
        preferences.copy(pins = preferences.pins.filterNot { it == pin })
    }

    fun movePin(fromIndex: Int, toIndex: Int) = savePreferences { preferences ->
        val pins = preferences.pins.toMutableList()
        if (fromIndex !in pins.indices || toIndex !in pins.indices) return@savePreferences preferences
        val moved = pins.removeAt(fromIndex)
        pins.add(toIndex, moved)
        preferences.copy(pins = pins)
    }

    fun resetPreferences() = savePreferences { HomePreferences() }

    fun searchPinCandidates(type: LmsLibraryItemType, term: String) {
        pinSearchJob?.cancel()
        val player = activePlayerStore.activePlayer.value
        if (player == null) {
            mutableState.update {
                it.copy(
                    pinCandidates = emptyList(),
                    pinCandidateType = type,
                    pinSearchError = "Select a connected player to search."
                )
            }
            return
        }
        val query = when (type) {
            LmsLibraryItemType.ARTIST -> LmsLibraryQuery.ARTISTS
            LmsLibraryItemType.ALBUM -> LmsLibraryQuery.ALBUMS
            LmsLibraryItemType.GENRE -> LmsLibraryQuery.GENRES
            LmsLibraryItemType.PLAYLIST -> LmsLibraryQuery.PLAYLISTS
            LmsLibraryItemType.TRACK -> LmsLibraryQuery.TRACKS
        }
        mutableState.update {
            it.copy(
                pinCandidateType = type,
                pinCandidates = emptyList(),
                isLoadingPinCandidates = true,
                pinSearchError = null
            )
        }
        pinSearchJob = viewModelScope.launch {
            try {
                val page = libraryGateway.getPage(
                    connection = player.connection,
                    query = query,
                    offset = 0,
                    limit = 30,
                    search = term.takeIf(String::isNotBlank)
                )
                mutableState.update {
                    it.copy(
                        pinCandidates = page.items.filter {
                            it.type == type && !it.isContainer
                        },
                        isLoadingPinCandidates = false,
                        pinSearchError = null
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        pinCandidates = emptyList(),
                        isLoadingPinCandidates = false,
                        pinSearchError = "Could not search for ${type.displayName()} pins."
                    )
                }
            }
        }
    }

    fun clearPinCandidates() {
        pinSearchJob?.cancel()
        mutableState.update {
            it.copy(
                pinCandidates = emptyList(),
                pinCandidateType = null,
                isLoadingPinCandidates = false,
                pinSearchError = null
            )
        }
    }

    fun executeSpecialAction(
        action: HomeSpecialAction,
        onCompleted: (() -> Unit)? = null
    ) {
        if (action == HomeSpecialAction.FAVORITES) return
        val player = activePlayerStore.activePlayer.value
        if (player == null) {
            mutableState.update { it.copy(actionMessage = "Select a connected player first.") }
            return
        }
        val scope = when (action) {
            HomeSpecialAction.SHUFFLE_TRACKS -> LmsShuffleScope.TRACKS
            HomeSpecialAction.SHUFFLE_ARTISTS -> LmsShuffleScope.ARTISTS
            HomeSpecialAction.SHUFFLE_ALBUMS -> LmsShuffleScope.ALBUMS
            HomeSpecialAction.FAVORITES -> return
        }
        mutableState.update {
            it.copy(isExecutingAction = true, actionMessage = null)
        }
        viewModelScope.launch {
            try {
                libraryGateway.shuffleLibrary(player.connection, player.playerId, scope)
                mutableState.update {
                    it.copy(
                        isExecutingAction = false,
                        actionMessage = "Shuffle started."
                    )
                }
                onCompleted?.invoke()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        isExecutingAction = false,
                        actionMessage = "Could not start shuffle."
                    )
                }
            }
        }
    }

    fun playSection(
        sectionType: HomeSectionType,
        onCompleted: (() -> Unit)? = null
    ) {
        val player = activePlayerStore.activePlayer.value
        if (player == null) {
            mutableState.update { it.copy(actionMessage = "Select a connected player first.") }
            return
        }
        val items = mutableState.value.shelves[sectionType]?.items.orEmpty()
            .filterNot { it.isContainer }
        if (items.isEmpty()) {
            mutableState.update { it.copy(actionMessage = "This section has nothing to play.") }
            return
        }
        mutableState.update {
            it.copy(isExecutingAction = true, actionMessage = null)
        }
        viewModelScope.launch {
            try {
                libraryGateway.playNowSection(
                    player.connection,
                    player.playerId,
                    items
                )
                mutableState.update {
                    it.copy(
                        isExecutingAction = false,
                        actionMessage = "Playing ${sectionTitle(sectionType)}."
                    )
                }
                onCompleted?.invoke()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        isExecutingAction = false,
                        actionMessage = "Could not play this section."
                    )
                }
            }
        }
    }

    fun clearActionMessage() {
        mutableState.update { it.copy(actionMessage = null) }
    }

    fun favoritesLocation(): LibraryLocation =
        LibraryLocation("Favorites", LmsLibraryQuery.FAVORITES)

    private fun reloadShelves(player: LmsActivePlayer, preferences: HomePreferences) {
        shelfJobs.values.forEach(Job::cancel)
        shelfJobs.clear()
        mutableState.update { state ->
            state.copy(
                shelves = preferences.sections.associate { section ->
                    section.type to HomeShelfState(
                        isLoading = section.enabled
                    )
                }.filterKeys { type ->
                    preferences.sections.any { it.type == type && it.enabled }
                }
            )
        }
        preferences.sections.filter { it.enabled }.forEach { section ->
            reloadShelf(player, section.type, section)
        }
    }

    private fun reloadShelf(
        player: LmsActivePlayer,
        sectionType: HomeSectionType,
        section: HomeSection?
    ) {
        val sectionConfig = section ?: mutableState.value.preferences.sections
            .firstOrNull { it.type == sectionType }
            ?: return
        if (!sectionConfig.enabled) return
        val (query, sort) = when (sectionType) {
            HomeSectionType.RECENTLY_ADDED_ALBUMS ->
                LmsLibraryQuery.ALBUMS to LmsLibrarySort.NEW
            HomeSectionType.MOST_PLAYED_ALBUMS ->
                LmsLibraryQuery.ALBUMS to LmsLibrarySort.PLAY_COUNT
            HomeSectionType.RECENTLY_PLAYED_ALBUMS ->
                LmsLibraryQuery.ALBUMS to LmsLibrarySort.RECENTLY_PLAYED
            HomeSectionType.RECENTLY_ADDED_ARTISTS ->
                LmsLibraryQuery.ARTISTS to LmsLibrarySort.NEW
            HomeSectionType.MOST_PLAYED_ARTISTS ->
                LmsLibraryQuery.ARTISTS to LmsLibrarySort.PLAY_COUNT
            HomeSectionType.RECENTLY_PLAYED_ARTISTS ->
                LmsLibraryQuery.ARTISTS to LmsLibrarySort.RECENTLY_PLAYED
        }
        mutableState.update { state ->
            state.copy(
                shelves = state.shelves + (
                    sectionType to HomeShelfState(isLoading = true)
                    )
            )
        }
        shelfJobs[sectionType]?.cancel()
        shelfJobs[sectionType] = viewModelScope.launch {
            try {
                val page = libraryGateway.getPage(
                    connection = player.connection,
                    query = query,
                    offset = 0,
                    limit = sectionConfig.limit,
                    sort = sort
                )
                val resolvedItems = if (query == LmsLibraryQuery.ARTISTS) {
                    page.items.map { item ->
                        val artworkUrl = runCatching {
                            libraryGateway.getArtistArtworkUrl(player.connection, item.id)
                        }.getOrNull()
                        item.copy(artworkUrl = artworkUrl ?: item.artworkUrl)
                    }
                } else {
                    page.items
                }
                mutableState.update { state ->
                    state.copy(
                        shelves = state.shelves + (
                            sectionType to HomeShelfState(
                                items = resolvedItems,
                                isLoading = false,
                                error = null
                            )
                            )
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.update { state ->
                    state.copy(
                        shelves = state.shelves + (
                            sectionType to HomeShelfState(
                                isLoading = false,
                                error = "Could not load this section."
                            )
                            )
                    )
                }
            }
        }
    }

    private fun savePreferences(transform: (HomePreferences) -> HomePreferences) {
        val endpointKey = mutableState.value.endpointKey ?: return
        val updated = transform(mutableState.value.preferences).normalized()
        mutableState.update { it.copy(preferences = updated) }
        viewModelScope.launch {
            try {
                preferencesRepository.setPreferences(endpointKey, updated)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(actionMessage = "Could not save Home settings.")
                }
            }
        }
    }

    class Factory(
        private val libraryGateway: LmsLibraryGateway,
        private val activePlayerStore: LmsActivePlayerStore,
        private val preferencesRepository: HomePreferencesRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                "Unsupported ViewModel: ${modelClass.name}"
            }
            return HomeViewModel(
                libraryGateway,
                activePlayerStore,
                preferencesRepository
            ) as T
        }
    }
}

private fun sectionTitle(type: HomeSectionType): String = when (type) {
    HomeSectionType.RECENTLY_ADDED_ALBUMS -> "recently added albums"
    HomeSectionType.MOST_PLAYED_ALBUMS -> "most played albums"
    HomeSectionType.RECENTLY_PLAYED_ALBUMS -> "recently played albums"
    HomeSectionType.RECENTLY_ADDED_ARTISTS -> "recently added artists"
    HomeSectionType.MOST_PLAYED_ARTISTS -> "most played artists"
    HomeSectionType.RECENTLY_PLAYED_ARTISTS -> "recently played artists"
}

private fun LmsLibraryItemType.displayName(): String = when (this) {
    LmsLibraryItemType.ARTIST -> "artist"
    LmsLibraryItemType.ALBUM -> "album"
    LmsLibraryItemType.GENRE -> "genre"
    LmsLibraryItemType.TRACK -> "track"
    LmsLibraryItemType.PLAYLIST -> "playlist"
}
