package com.ryanmorrisroe.lyriamp.data.persistence

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryFilter
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItemType
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePreferencesRepositoryTest {
    @Test
    fun persistsPinsSectionsAndDensityPerEndpoint() = runTest {
        val directory = Files.createTempDirectory("lyriamp-home-preferences").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreFactory.create(
                storage = OkioStorage(
                    fileSystem = FileSystem.SYSTEM,
                    serializer = PreferencesSerializer,
                    producePath = {
                        directory.resolve("home.preferences_pb").absolutePath.toPath()
                    }
                ),
                scope = scope
            )
            val repository = DataStoreHomePreferencesRepository(store)
            val localEndpoint = "http://lms.local:9000"
            val remoteEndpoint = "https://music.example.com:9000"

            assertEquals(HomePreferences(), repository.observe(localEndpoint).first())

            val preferences = HomePreferences(
                pins = listOf(
                    HomePin.libraryItem(
                        itemType = LmsLibraryItemType.ARTIST,
                        itemId = "42",
                        title = "Miles Davis",
                        filter = LmsLibraryFilter(artistId = "42"),
                        artworkId = "artist-42",
                        artworkUrl = "/contributor/42/image"
                    ),
                    HomePin.special(
                        action = HomeSpecialAction.SHUFFLE_TRACKS,
                        title = "Shuffle"
                    )
                ),
                sections = listOf(
                    HomeSection(HomeSectionType.RECENTLY_ADDED_ALBUMS, limit = 12),
                    HomeSection(HomeSectionType.MOST_PLAYED_ARTISTS, enabled = false, limit = 24)
                ),
                density = HomeDensity.SPACIOUS
            )

            repository.setPreferences(localEndpoint, preferences)

            val restored = repository.observe(localEndpoint).first()
            assertEquals(2, restored.pins.size)
            assertEquals("Miles Davis", restored.pins.first().title)
            assertEquals(LmsLibraryItemType.ARTIST, restored.pins.first().itemType)
            assertEquals("artist-42", restored.pins.first().artworkId)
            assertEquals("/contributor/42/image", restored.pins.first().artworkUrl)
            assertEquals(HomeSpecialAction.SHUFFLE_TRACKS, restored.pins[1].specialAction)
            assertEquals(HomeDensity.SPACIOUS, restored.density)
            assertTrue(
                restored.sections.any {
                    it.type == HomeSectionType.RECENTLY_ADDED_ALBUMS && it.limit == 12
                }
            )
            assertTrue(
                restored.sections.any {
                    it.type == HomeSectionType.MOST_PLAYED_ARTISTS && !it.enabled
                }
            )
            assertEquals(emptyList<HomePin>(), repository.observe(remoteEndpoint).first().pins)
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun resetRemovesOnlyTheRequestedEndpointPreferences() = runTest {
        val directory = Files.createTempDirectory("lyriamp-home-reset").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreFactory.create(
                storage = OkioStorage(
                    fileSystem = FileSystem.SYSTEM,
                    serializer = PreferencesSerializer,
                    producePath = {
                        directory.resolve("home-reset.preferences_pb").absolutePath.toPath()
                    }
                ),
                scope = scope
            )
            val repository = DataStoreHomePreferencesRepository(store)
            val firstEndpoint = "http://one.local:9000"
            val secondEndpoint = "http://two.local:9000"

            repository.setPreferences(
                firstEndpoint,
                HomePreferences(
                    pins = listOf(
                        HomePin.special(HomeSpecialAction.FAVORITES, "Favorites")
                    )
                )
            )
            repository.setPreferences(
                secondEndpoint,
                HomePreferences(
                    pins = listOf(
                        HomePin.special(HomeSpecialAction.SHUFFLE_ALBUMS, "Shuffle albums")
                    )
                )
            )

            repository.reset(firstEndpoint)

            assertTrue(repository.observe(firstEndpoint).first().pins.isEmpty())
            assertFalse(repository.observe(secondEndpoint).first().pins.isEmpty())
            assertEquals(
                HomeSpecialAction.SHUFFLE_ALBUMS,
                repository.observe(secondEndpoint).first().pins.single().specialAction
            )
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
