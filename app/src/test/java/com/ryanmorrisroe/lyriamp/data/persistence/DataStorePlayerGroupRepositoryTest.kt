package com.ryanmorrisroe.lyriamp.data.persistence

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer
import java.io.File
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

class DataStorePlayerGroupRepositoryTest {
    @Test
    fun persistsGroupsPerEndpointAndProtectsGroupsFromOtherEndpointsOnDelete() = runTest {
        val directory = Files.createTempDirectory("lyriamp-player-groups").toFile()
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = DataStoreFactory.create(
                storage = OkioStorage(
                    fileSystem = FileSystem.SYSTEM,
                    serializer = PreferencesSerializer,
                    producePath = {
                        File(directory, "saved_servers.preferences_pb").absolutePath.toPath()
                    }
                ),
                scope = dataStoreScope
            )
            val repository = DataStorePlayerGroupRepository(dataStore)

            val localGroup = repository.saveGroup(
                endpointKey = "http://lms.local:9000",
                name = "  Downstairs  ",
                playerIds = listOf("living-room", "kitchen")
            )
            val remoteGroup = repository.saveGroup(
                endpointKey = "https://lms.local:9000",
                name = "Upstairs",
                playerIds = listOf("bedroom", "office")
            )
            val updatedLocalGroup = localGroup.copy(
                name = "Downstairs speakers",
                playerIds = listOf("living-room", "kitchen", "dining-room")
            )

            assertEquals("Downstairs", localGroup.name)
            assertTrue(
                repository.updateGroup(
                    endpointKey = localGroup.endpointKey,
                    groupId = localGroup.id,
                    name = updatedLocalGroup.name,
                    playerIds = updatedLocalGroup.playerIds
                )
            )
            assertFalse(
                repository.updateGroup(
                    endpointKey = remoteGroup.endpointKey,
                    groupId = localGroup.id,
                    name = "Wrong server",
                    playerIds = updatedLocalGroup.playerIds
                )
            )
            assertEquals(
                listOf(updatedLocalGroup),
                repository.observeGroups("http://lms.local:9000").first()
            )
            assertEquals(listOf(remoteGroup), repository.observeGroups("https://lms.local:9000").first())
            assertFalse(repository.removeGroup(remoteGroup.endpointKey, localGroup.id))
            assertTrue(repository.removeGroup(localGroup.endpointKey, localGroup.id))
            assertTrue(repository.observeGroups(localGroup.endpointKey).first().isEmpty())
            assertEquals(listOf(remoteGroup), repository.observeGroups(remoteGroup.endpointKey).first())
        } finally {
            dataStoreScope.cancel()
            directory.deleteRecursively()
        }
    }
}
