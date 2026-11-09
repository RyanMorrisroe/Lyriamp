package com.ryanmorrisroe.lyriamp.feature.connection

import com.ryanmorrisroe.lyriamp.data.lms.LmsConnectionGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayerStore
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsHttpException
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlaybackMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerCommand
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerMixerState
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerPlaybackOptions
import com.ryanmorrisroe.lyriamp.data.lms.LmsRepeatMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerDirectory
import com.ryanmorrisroe.lyriamp.data.lms.LmsShuffleMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerRemoteGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerStatus
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerInfo
import com.ryanmorrisroe.lyriamp.data.persistence.SavedServerProfile
import com.ryanmorrisroe.lyriamp.data.persistence.SavedServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest {
    @Test
    fun savesLoadsAndRemovesServerProfiles() {
        runViewModelTest {
            val savedServerRepository = FakeSavedServerRepository()
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { LmsServerInfo("9.2.0", 0) },
                LmsPlayerDirectory { emptyList() },
                unusedPlayerRemoteGateway(),
                savedServerRepository = savedServerRepository
            )
            viewModel.onHostChanged("lms.local")
            viewModel.onHttpsChanged(true)
            viewModel.onUsernameChanged("listener")
            viewModel.onPasswordChanged("private-password")

            viewModel.saveServer()
            advanceUntilIdle()

            val savedProfile = viewModel.uiState.value.savedServers.single()
            assertEquals("Server saved.", viewModel.uiState.value.savedServerMessage)
            assertEquals(
                LmsServerConnection("lms.local", 9000, true, "listener", "private-password"),
                savedServerRepository.getConnection(savedProfile.id)
            )

            viewModel.onHostChanged("different.local")
            viewModel.loadSavedServer(savedProfile.id)
            advanceUntilIdle()

            assertEquals("lms.local", viewModel.uiState.value.host)
            assertEquals("9000", viewModel.uiState.value.port)
            assertTrue(viewModel.uiState.value.useHttps)
            assertEquals("listener", viewModel.uiState.value.username)
            assertEquals("private-password", viewModel.uiState.value.password)

            viewModel.removeSavedServer(savedProfile.id)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.savedServers.isEmpty())
            assertNull(savedServerRepository.getConnection(savedProfile.id))
        }
    }

    @Test
    fun successfulConnectionPublishesServerInfo() {
        runViewModelTest {
            var requestedConnection: LmsServerConnection? = null
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { connection ->
                    requestedConnection = connection
                    LmsServerInfo(version = "9.2.0", playerCount = 3)
                },
                LmsPlayerDirectory { connection ->
                    assertEquals(requestedConnection, connection)
                    listOf(LmsPlayer("aa:bb", "Living Room", "Squeezebox", true, true))
                },
                unusedPlayerRemoteGateway()
            )
            viewModel.onHostChanged("lms.local")
            viewModel.onHttpsChanged(true)
            viewModel.onUsernameChanged("listener")
            viewModel.onPasswordChanged("secret")

            viewModel.testConnection()
            assertTrue(viewModel.uiState.value.isConnecting)
            advanceUntilIdle()

            assertEquals("lms.local", requestedConnection?.host)
            assertTrue(requestedConnection?.useHttps == true)
            assertEquals("listener", requestedConnection?.username)
            assertEquals(LmsServerInfo("9.2.0", 3), viewModel.uiState.value.serverInfo)
            assertEquals("Living Room", viewModel.uiState.value.players.single().name)
            assertFalse(viewModel.uiState.value.isConnecting)
            assertNull(viewModel.uiState.value.errorMessage)
        }
    }

    @Test
    fun invalidPortDoesNotCallGateway() {
        runViewModelTest {
            var requestCount = 0
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway {
                    requestCount++
                    LmsServerInfo("9.2.0", 0)
                },
                LmsPlayerDirectory { emptyList() },
                unusedPlayerRemoteGateway()
            )
            viewModel.onHostChanged("lms.local")
            viewModel.onPortChanged("9000x")

            viewModel.testConnection()
            advanceUntilIdle()

            assertEquals(0, requestCount)
            assertEquals("Enter a valid port.", viewModel.uiState.value.errorMessage)
        }
    }

    @Test
    fun authenticationFailureGetsActionableMessage() {
        runViewModelTest {
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { throw LmsHttpException(401) },
                LmsPlayerDirectory { emptyList() },
                unusedPlayerRemoteGateway()
            )
            viewModel.onHostChanged("lms.local")

            viewModel.testConnection()
            advanceUntilIdle()

            assertEquals(
                "Authentication failed. Check your credentials.",
                viewModel.uiState.value.errorMessage
            )
            assertNull(viewModel.uiState.value.serverInfo)
            assertFalse(viewModel.uiState.value.isConnecting)
        }
    }

    @Test
    fun selectingPlayerLoadsStatusAndCommandsRefreshIt() {
        runViewModelTest {
            val sentCommands = mutableListOf<LmsPlayerCommand>()
            var statusRequestCount = 0
            var mixerStateRequestCount = 0
            var playbackOptionsRequestCount = 0
            var shuffleMode = LmsShuffleMode.OFF
            val remoteGateway = object : LmsPlayerRemoteGateway {
                override suspend fun getPlayerStatus(
                    connection: LmsServerConnection,
                    playerId: String
                ): LmsPlayerStatus {
                    statusRequestCount++
                    assertEquals("aa:bb", playerId)
                    return LmsPlayerStatus(
                        mode = LmsPlaybackMode.PLAYING,
                        title = "Track",
                        artist = "Artist",
                        album = "Album",
                        positionSeconds = 12.0,
                        durationSeconds = 180.0,
                        canSeek = true
                    )
                }

                override suspend fun getPlayerMixerState(
                    connection: LmsServerConnection,
                    playerId: String
                ): LmsPlayerMixerState {
                    mixerStateRequestCount++
                    assertEquals("aa:bb", playerId)
                    return LmsPlayerMixerState(42, false)
                }

                override suspend fun getPlayerPlaybackOptions(
                    connection: LmsServerConnection,
                    playerId: String
                ): LmsPlayerPlaybackOptions {
                    playbackOptionsRequestCount++
                    return LmsPlayerPlaybackOptions(shuffleMode, LmsRepeatMode.OFF)
                }

                override suspend fun sendCommand(
                    connection: LmsServerConnection,
                    playerId: String,
                    command: LmsPlayerCommand
                ) {
                    sentCommands += command
                    if (command is LmsPlayerCommand.SetShuffleMode) {
                        shuffleMode = command.mode
                    }
                }
            }
            val activePlayerStore = LmsActivePlayerStore()
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { LmsServerInfo("9.2.0", 1) },
                LmsPlayerDirectory {
                    listOf(LmsPlayer("aa:bb", "Living Room", "Squeezebox", true, true))
                },
                remoteGateway,
                activePlayerStore
            )
            viewModel.onHostChanged("lms.local")
            viewModel.testConnection()
            advanceUntilIdle()

            viewModel.selectPlayer("aa:bb")
            advanceUntilIdle()
            assertEquals("Track", viewModel.uiState.value.playerStatus?.title)
            assertEquals(LmsPlayerMixerState(42, false), viewModel.uiState.value.playerMixerState)
            assertEquals(
                LmsPlayerPlaybackOptions(LmsShuffleMode.OFF, LmsRepeatMode.OFF),
                viewModel.uiState.value.playerPlaybackOptions
            )
            assertEquals(1, mixerStateRequestCount)
            assertEquals(1, playbackOptionsRequestCount)
            assertEquals(
                LmsActivePlayer(
                    LmsServerConnection("lms.local", 9000, false, null, null),
                    "aa:bb",
                    "Living Room"
                ),
                activePlayerStore.activePlayer.value
            )

            viewModel.sendPlayerCommand(LmsPlayerCommand.Pause)
            advanceUntilIdle()

            viewModel.sendPlayerCommand(LmsPlayerCommand.SetVolume(23))
            advanceUntilIdle()

            viewModel.sendPlayerCommand(LmsPlayerCommand.SetShuffleMode(LmsShuffleMode.ALBUMS))
            advanceUntilIdle()

            assertEquals(
                listOf(
                    LmsPlayerCommand.Pause,
                    LmsPlayerCommand.SetVolume(23),
                    LmsPlayerCommand.SetShuffleMode(LmsShuffleMode.ALBUMS)
                ),
                sentCommands
            )
            assertEquals(4, statusRequestCount)
            assertEquals(2, mixerStateRequestCount)
            assertEquals(2, playbackOptionsRequestCount)
            assertEquals(
                LmsPlayerPlaybackOptions(LmsShuffleMode.ALBUMS, LmsRepeatMode.OFF),
                viewModel.uiState.value.playerPlaybackOptions
            )
            assertFalse(viewModel.uiState.value.isSendingPlayerCommand)
            assertNull(viewModel.uiState.value.playerErrorMessage)
        }
    }

    @Test
    fun powerCommandRefreshesPlayerAndClearsActiveTargetWhenPoweredOff() {
        runViewModelTest {
            var poweredOn = true
            val activePlayerStore = LmsActivePlayerStore()
            val remoteGateway = object : LmsPlayerRemoteGateway {
                override suspend fun getPlayerStatus(
                    connection: LmsServerConnection,
                    playerId: String
                ) = LmsPlayerStatus(LmsPlaybackMode.STOPPED, null, null, null, 0.0, null, false)

                override suspend fun getPlayerMixerState(
                    connection: LmsServerConnection,
                    playerId: String
                ) = LmsPlayerMixerState(50, false)

                override suspend fun getPlayerPlaybackOptions(
                    connection: LmsServerConnection,
                    playerId: String
                ) = LmsPlayerPlaybackOptions(LmsShuffleMode.OFF, LmsRepeatMode.OFF)

                override suspend fun sendCommand(
                    connection: LmsServerConnection,
                    playerId: String,
                    command: LmsPlayerCommand
                ) {
                    assertEquals(LmsPlayerCommand.SetPower(false), command)
                    poweredOn = false
                }
            }
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { LmsServerInfo("9.2.0", 1) },
                LmsPlayerDirectory {
                    listOf(LmsPlayer("aa:bb", "Living Room", "Squeezebox", true, poweredOn))
                },
                remoteGateway,
                activePlayerStore
            )
            viewModel.onHostChanged("lms.local")
            viewModel.testConnection()
            advanceUntilIdle()
            viewModel.selectPlayer("aa:bb")
            advanceUntilIdle()
            assertTrue(activePlayerStore.activePlayer.value != null)

            viewModel.setPlayerPower("aa:bb", false)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.players.single().power)
            assertNull(viewModel.uiState.value.selectedPlayerId)
            assertNull(activePlayerStore.activePlayer.value)
            assertNull(viewModel.uiState.value.playerStatus)
        }
    }

    private fun unusedPlayerRemoteGateway() = object : LmsPlayerRemoteGateway {
        override suspend fun getPlayerStatus(
            connection: LmsServerConnection,
            playerId: String
        ): LmsPlayerStatus = error("Unexpected player status request")

        override suspend fun getPlayerMixerState(
            connection: LmsServerConnection,
            playerId: String
        ): LmsPlayerMixerState = error("Unexpected player mixer state request")

        override suspend fun getPlayerPlaybackOptions(
            connection: LmsServerConnection,
            playerId: String
        ): LmsPlayerPlaybackOptions = error("Unexpected player playback options request")

        override suspend fun sendCommand(
            connection: LmsServerConnection,
            playerId: String,
            command: LmsPlayerCommand
        ) = error("Unexpected player command")
    }

    private class FakeSavedServerRepository : SavedServerRepository {
        private val connections = mutableMapOf<String, LmsServerConnection>()
        private val profiles = MutableStateFlow<List<SavedServerProfile>>(emptyList())

        override fun observeServers(): Flow<List<SavedServerProfile>> = profiles

        override suspend fun save(connection: LmsServerConnection): SavedServerProfile {
            val normalizedHost = connection.host.trim()
            val existingProfile = profiles.value.firstOrNull {
                it.host.equals(normalizedHost, ignoreCase = true) &&
                    it.port == connection.port && it.useHttps == connection.useHttps
            }
            val profile = existingProfile?.copy(
                hasCredentials = connection.username != null || connection.password != null
            ) ?: SavedServerProfile(
                id = "server-${profiles.value.size + 1}",
                host = normalizedHost,
                port = connection.port,
                useHttps = connection.useHttps,
                hasCredentials = connection.username != null || connection.password != null
            )
            connections[profile.id] = connection.copy(host = normalizedHost)
            profiles.value = (profiles.value.filterNot { it.id == profile.id } + profile)
            return profile
        }

        override suspend fun getConnection(id: String): LmsServerConnection? = connections[id]

        override suspend fun remove(id: String) {
            connections.remove(id)
            profiles.value = profiles.value.filterNot { it.id == id }
        }
    }

    private fun runViewModelTest(block: suspend TestScope.() -> Unit) {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                block()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }
}