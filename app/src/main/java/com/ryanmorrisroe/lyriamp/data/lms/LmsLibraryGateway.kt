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
    val isContainer: Boolean = false
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

interface LmsLibraryGateway {
    suspend fun getPage(
        connection: LmsServerConnection,
        query: LmsLibraryQuery,
        offset: Int,
        limit: Int,
        filter: LmsLibraryFilter = LmsLibraryFilter(),
        search: String? = null
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