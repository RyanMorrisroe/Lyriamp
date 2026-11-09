package com.ryanmorrisroe.lyriamp.data.persistence

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataStoreSavedServerRepositoryTest {
    @Test
    fun savesEncryptedCredentialsLoadsAndUpdatesProfileThenRemovesIt() = runTest {
        val directory = Files.createTempDirectory("lyriamp-saved-servers").toFile()
        val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { File(directory, "saved_servers.preferences_pb") }
            )
            val repository = DataStoreSavedServerRepository(dataStore, TestCredentialCipher())
            val connection = com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection(
                host = "lms.local",
                port = 9000,
                useHttps = true,
                username = "listener",
                password = "private-password"
            )

            val saved = repository.save(connection)
            val storedValues = dataStore.data.first().asMap().values.joinToString()
            assertFalse(storedValues.contains("listener"))
            assertFalse(storedValues.contains("private-password"))
            assertTrue(saved.hasCredentials)
            assertEquals(connection, repository.getConnection(saved.id))
            assertEquals(listOf(saved), repository.observeServers().first())

            val updated = repository.save(connection.copy(password = "new-password"))
            assertEquals(saved.id, updated.id)
            assertEquals(
                connection.copy(password = "new-password"),
                repository.getConnection(updated.id)
            )
            assertEquals(1, repository.observeServers().first().size)

            repository.remove(updated.id)
            assertNull(repository.getConnection(updated.id))
            assertTrue(repository.observeServers().first().isEmpty())
        } finally {
            dataStoreScope.cancel()
            directory.deleteRecursively()
        }
    }

    private class TestCredentialCipher : CredentialCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray = plaintext.map { byte ->
            (byte.toInt() xor 0x5A).toByte()
        }.toByteArray()

        override fun decrypt(ciphertext: ByteArray): ByteArray = encrypt(ciphertext)
    }
}