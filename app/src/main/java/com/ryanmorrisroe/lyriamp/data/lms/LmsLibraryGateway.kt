package com.ryanmorrisroe.lyriamp.data.lms

enum class LmsLibraryItemType {
    ARTIST,
    ALBUM,
    GENRE,
    TRACK,
    PLAYLIST
}

enum class LmsLibraryQuery(
    internal val command: List<String>,
    internal val loopName: String,
    internal val idField: String,
    internal val titleField: String,
    internal val itemType: LmsLibraryItemType
) {
    ARTISTS(listOf("artists"), "artists_loop", "id", "artist", LmsLibraryItemType.ARTIST),
    ALBUMS(listOf("albums"), "albums_loop", "id", "album", LmsLibraryItemType.ALBUM),
    GENRES(listOf("genres"), "genres_loop", "id", "genre", LmsLibraryItemType.GENRE),
    TRACKS(listOf("titles"), "titles_loop", "id", "title", LmsLibraryItemType.TRACK),
    PLAYLISTS(listOf("playlists"), "playlists_loop", "id", "playlist", LmsLibraryItemType.PLAYLIST),
    PLAYLIST_TRACKS(
        listOf("playlists", "tracks"),
        "playlisttracks_loop",
        "id",
        "title",
        LmsLibraryItemType.TRACK
    )
}

enum class LmsLibrarySort(
    internal val lmsKey: String,
    internal val naturallyDescending: Boolean = false
) {
    ARTIST("artist"),
    NEW("new", naturallyDescending = true),
    PLAY_COUNT("playcount", naturallyDescending = true),
    POPULAR("popular", naturallyDescending = true),
    RECENTLY_PLAYED("recentlyplayed", naturallyDescending = true),
    ALBUM("album"),
    ARTIST_ALBUM("artistalbum"),
    YEAR_ALBUM("yearalbum"),
    YEAR_ARTIST_ALBUM("yearartistalbum"),
    ART_FLOW("artflow"),
    CHANGED("changed", naturallyDescending = true),
    RANDOM("random"),
    TITLE("title"),
    TRACK_NUMBER("tracknum"),
    ALBUM_TRACK("albumtrack")
}

enum class LmsLibrarySortOrder {
    ASCENDING,
    DESCENDING
}

fun LmsLibraryQuery.supportedSortOptions(): List<LmsLibrarySort> = when (this) {
    LmsLibraryQuery.ARTISTS -> listOf(
        LmsLibrarySort.ARTIST,
        LmsLibrarySort.NEW,
        LmsLibrarySort.PLAY_COUNT,
        LmsLibrarySort.POPULAR,
        LmsLibrarySort.RECENTLY_PLAYED
    )
    LmsLibraryQuery.ALBUMS -> listOf(
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
    LmsLibraryQuery.TRACKS,
    LmsLibraryQuery.PLAYLIST_TRACKS -> listOf(
        LmsLibrarySort.TITLE,
        LmsLibrarySort.TRACK_NUMBER,
        LmsLibrarySort.ALBUM_TRACK
    )
    LmsLibraryQuery.GENRES,
    LmsLibraryQuery.PLAYLISTS -> emptyList()
}

data class LmsLibraryFilter(
    val artistId: String? = null,
    val albumId: String? = null,
    val genreId: String? = null,
    val playlistId: String? = null,
    val folderId: String? = null
)

data class LmsLibraryItem(
    val id: String,
    val type: LmsLibraryItemType,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationSeconds: Double? = null,
    val artworkId: String? = null,
    val artworkUrl: String? = null,
    val isContainer: Boolean = false,
    val year: String? = null,
    val albumId: String? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null
)

data class LmsLibraryPage(
    val items: List<LmsLibraryItem>,
    val offset: Int,
    val totalCount: Int
)

data class LmsLibrarySearchResults(
    val artists: LmsLibraryPage,
    val albums: LmsLibraryPage,
    val tracks: LmsLibraryPage
)

internal fun List<LmsLibraryItem>.sortedForAlbumTracks(): List<LmsLibraryItem> =
    distinctBy { it.id }
        .sortedWith(
            compareBy<LmsLibraryItem>(
                { it.discNumber?.takeIf { disc -> disc > 0 } ?: 1 }
            ).thenBy {
                it.trackNumber?.takeIf { track -> track > 0 } ?: Int.MAX_VALUE
            }.thenBy { it.title.lowercase() }
                .thenBy { it.id }
        )

interface LmsLibraryGateway {
    suspend fun getPage(
        connection: LmsServerConnection,
        query: LmsLibraryQuery,
        offset: Int,
        limit: Int,
        filter: LmsLibraryFilter = LmsLibraryFilter(),
        search: String? = null,
        sort: LmsLibrarySort? = null
    ): LmsLibraryPage

    suspend fun search(
        connection: LmsServerConnection,
        term: String,
        offset: Int,
        limit: Int
    ): LmsLibrarySearchResults

    suspend fun getArtistArtworkUrl(
        connection: LmsServerConnection,
        artistId: String
    ): String?

    suspend fun playNow(
        connection: LmsServerConnection,
        playerId: String,
        item: LmsLibraryItem
    )
}