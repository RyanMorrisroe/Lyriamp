package com.ryanmorrisroe.lyriamp.data.persistence

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryFilter
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryQuery

class LastSessionRepositoryTest {
    @Test
    fun savesRestoresAndClearsTheLastSession() = runTest {
        val directory = Files.createTempDirectory("lyriamp-last-session").toFile()
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = DataStoreFactory.create(
                storage = OkioStorage(
                    fileSystem = FileSystem.SYSTEM,
                    serializer = PreferencesSerializer,
                    producePath = {
                        directory.resolve("session.preferences_pb").absolutePath.toPath()
                    }
                ),
                scope = dataStoreScope
            )
            val repository = DataStoreLastSessionRepository(dataStore)
            val snapshot = LastSessionSnapshot(
                serverProfileId = "server-1",
                playerId = "player-1",
                selectedPlayerIds = listOf("player-1", "player-2"),
                locationStack = listOf(
                    LastSessionLocation("Library"),
                    LastSessionLocation("Artists", LmsLibraryQuery.ARTISTS),
                    LastSessionLocation(
                        "Albums",
                        LmsLibraryQuery.ALBUMS,
                        LmsLibraryFilter(artistId = "artist-1")
                    ),
                    LastSessionLocation(
                        "Album",
                        LmsLibraryQuery.TRACKS,
                        LmsLibraryFilter(albumId = "album-1")
                    )
                ),
                searchText = "live",
                scrollAnchor = LastSessionScrollAnchor(
                    locationKey = "Library/Artists/Albums/Album",
                    searchText = "live",
                    itemId = "track-49",
                    index = 49,
                    offsetPx = 23
                ),
                activeTab = "ARTISTS",
                lastVisitedTab = "ARTISTS",
                tabs = mapOf(
                    "ARTISTS" to LastSessionTabSnapshot(
                        listOf(
                            LastSessionLocation("Library"),
                            LastSessionLocation("Artists", LmsLibraryQuery.ARTISTS)
                        ),
                        LastSessionScrollAnchor("artist-key", "", "artist-42", 42, 4)
                    )
                )
            )

            repository.save(snapshot)

            assertEquals(snapshot, repository.load())
            repository.clear()
            assertNull(repository.load())

            dataStore.edit {
                it[stringPreferencesKey("last_session_snapshot")] =
                    """{"version":1,"serverProfileId":"old-server","playerId":"old-player","searchText":"","locations":[{"title":"Library"},{"title":"Albums","query":"ALBUMS"}]}"""
            }
            assertEquals(
                LastSessionSnapshot(
                    serverProfileId = "old-server",
                    playerId = "old-player",
                    locationStack = listOf(
                        LastSessionLocation("Library"),
                        LastSessionLocation("Albums", LmsLibraryQuery.ALBUMS)
                    ),
                    searchText = ""
                ),
                repository.load()
            )
        } finally {
            dataStoreScope.cancel()
            directory.deleteRecursively()
        }
    }
}
