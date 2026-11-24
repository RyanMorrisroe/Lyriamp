package com.ryanmorrisroe.lyriamp.data.persistence

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibrarySort
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibrarySortOrder
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayPreferencesRepositoryTest {
    @Test
    fun persistsIndependentLayoutsAndAppearance() = runTest {
        val directory = Files.createTempDirectory("lyriamp-display").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreFactory.create(
                storage = OkioStorage(
                    fileSystem = FileSystem.SYSTEM,
                    serializer = PreferencesSerializer,
                    producePath = { directory.resolve("display.preferences_pb").absolutePath.toPath() }
                ),
                scope = scope
            )
            val repository = DataStoreDisplayPreferencesRepository(store)
            assertEquals(DisplayPreferences(), repository.observe().first())

            repository.setAppearance(Appearance.DARK)
            repository.setLayout(LibraryViewType.ARTISTS, LibraryLayout.COVER_WALL)
            repository.setLayout(LibraryViewType.ALBUMS, LibraryLayout.COMPACT)
            repository.setLayout(LibraryViewType.SONGS, LibraryLayout.COMFORTABLE)
            repository.setSort(
                LibraryViewType.ALBUMS,
                LibrarySortPreference(LmsLibrarySort.YEAR_ALBUM, LmsLibrarySortOrder.DESCENDING)
            )
            repository.setSort(
                LibraryViewType.SONGS,
                LibrarySortPreference(LmsLibrarySort.TRACK_NUMBER)
            )

            val restored = DataStoreDisplayPreferencesRepository(store).observe().first()
            assertEquals(Appearance.DARK, restored.appearance)
            assertEquals(LibraryLayout.COVER_WALL, restored.layoutFor(LibraryViewType.ARTISTS))
            assertEquals(LibraryLayout.COMPACT, restored.layoutFor(LibraryViewType.ALBUMS))
            assertEquals(LibraryLayout.COMFORTABLE, restored.layoutFor(LibraryViewType.GENRES))
            assertEquals(LibraryLayout.COMFORTABLE, restored.layoutFor(LibraryViewType.SONGS))
            assertEquals(
                LibrarySortPreference(LmsLibrarySort.YEAR_ALBUM, LmsLibrarySortOrder.DESCENDING),
                restored.sortFor(LibraryViewType.ALBUMS)
            )
            assertEquals(
                LibrarySortPreference(LmsLibrarySort.TRACK_NUMBER),
                restored.sortFor(LibraryViewType.SONGS)
            )
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun defaultsMissingStoredSortOrderToAscending() = runTest {
        val directory = Files.createTempDirectory("lyriamp-display-order").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreFactory.create(
                storage = OkioStorage(
                    fileSystem = FileSystem.SYSTEM,
                    serializer = PreferencesSerializer,
                    producePath = { directory.resolve("display.preferences_pb").absolutePath.toPath() }
                ),
                scope = scope
            )
            store.edit {
                it[stringPreferencesKey("sort_albums")] = LmsLibrarySort.YEAR_ALBUM.name
            }

            val restored = DataStoreDisplayPreferencesRepository(store).observe().first()
            assertEquals(
                LibrarySortPreference(LmsLibrarySort.YEAR_ALBUM, LmsLibrarySortOrder.ASCENDING),
                restored.sortFor(LibraryViewType.ALBUMS)
            )
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsCoverWallForSongs() = runTest {
        val directory = Files.createTempDirectory("lyriamp-display-invalid").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreFactory.create(
                storage = OkioStorage(
                    fileSystem = FileSystem.SYSTEM,
                    serializer = PreferencesSerializer,
                    producePath = { directory.resolve("display.preferences_pb").absolutePath.toPath() }
                ),
                scope = scope
            )
            val repository = DataStoreDisplayPreferencesRepository(store)
            try {
                repository.setLayout(LibraryViewType.SONGS, LibraryLayout.COVER_WALL)
                throw AssertionError("Cover wall should not be allowed for songs")
            } catch (_: IllegalArgumentException) {
                assertEquals(DisplayPreferences(), repository.observe().first())
            }
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
