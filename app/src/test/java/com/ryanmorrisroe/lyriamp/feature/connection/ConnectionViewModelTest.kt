package com.ryanmorrisroe.lyriamp.feature.connection

import com.ryanmorrisroe.lyriamp.data.lms.LmsConnectionGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayerStore
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsHttpException
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItem
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItemType
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlaybackMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerCommand
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerMixerState
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerPlaybackOptions
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerQueuePage
import com.ryanmorrisroe.lyriamp.data.lms.LmsRepeatMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerDirectory
import com.ryanmorrisroe.lyriamp.data.lms.LmsShuffleMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerRemoteGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerStatus
import com.ryanmorrisroe.lyriamp.data.lms.LmsQueueAddPosition
import com.ryanmorrisroe.lyriamp.data.lms.LmsQueueItem
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerInfo
import com.ryanmorrisroe.lyriamp.data.lms.SavedLmsPlayerGroup
import com.ryanmorrisroe.lyriamp.data.persistence.SavedServerProfile
import com.ryanmorrisroe.lyriamp.data.persistence.SavedServerRepository
import com.ryanmorrisroe.lyriamp.data.persistence.PlayerGroupRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
            assertNull(viewModel.uiState.value.serverProfileId)
        }
    }

    @Test
    fun localNetworkPermissionDenialExplainsRequiredAccess() {
        runViewModelTest {
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { LmsServerInfo("9.2.0", 0) },
                LmsPlayerDirectory { emptyList() },
                unusedPlayerRemoteGateway()
            )

            viewModel.onLocalNetworkPermissionDenied()

            assertEquals(
                "Local network access is required to contact your LMS server.",
                viewModel.uiState.value.errorMessage
            )
        }
    }

    @Test
    fun savingServerRejectsInvalidInputAndReportsStorageFailure() {
        runViewModelTest {
            val repository = FakeSavedServerRepository(
                saveFailure = IllegalStateException("storage unavailable")
            )
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { LmsServerInfo("9.2.0", 0) },
                LmsPlayerDirectory { emptyList() },
                unusedPlayerRemoteGateway(),
                savedServerRepository = repository
            )
            viewModel.onHostChanged("lms.local")
            viewModel.onPortChanged("9000x")

            viewModel.saveServer()

            assertEquals("Enter a valid port.", viewModel.uiState.value.savedServerErrorMessage)
            assertFalse(viewModel.uiState.value.isSavingServer)

            viewModel.onPortChanged("9000")
            viewModel.onHostChanged("lms.local/path")
            viewModel.saveServer()

            assertEquals(
                "Enter a hostname or IP address without a scheme or path.",
                viewModel.uiState.value.savedServerErrorMessage
            )

            viewModel.onHostChanged("lms.local")
            viewModel.saveServer()
            advanceUntilIdle()

            assertEquals("Could not save this server.", viewModel.uiState.value.savedServerErrorMessage)
            assertFalse(viewModel.uiState.value.isSavingServer)
        }
    }

    @Test
    fun successfulConnectionPublishesServerInfo() {
        runViewModelTest {
            var requestedConnection: LmsServerConnection? = null
            var requestCount = 0
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { connection ->
                    requestCount++
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
            viewModel.testConnection()
            advanceUntilIdle()

            assertEquals(1, requestCount)
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
    fun successfulConnectionAutomaticallySavesProfile() {
        runViewModelTest {
            val savedServerRepository = FakeSavedServerRepository()
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { LmsServerInfo("9.2.0", 1) },
                LmsPlayerDirectory { emptyList() },
                unusedPlayerRemoteGateway(),
                savedServerRepository = savedServerRepository
            )
            viewModel.onHostChanged("lms.local")

            viewModel.testConnection()
            advanceUntilIdle()

            assertEquals("server-1", viewModel.uiState.value.serverProfileId)
            assertEquals("lms.local", viewModel.uiState.value.savedServers.single().host)
        }
    }

    @Test
    fun failedConnectionDoesNotSaveProfile() {
        runViewModelTest {
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { throw IllegalStateException("offline") },
                LmsPlayerDirectory { emptyList() },
                unusedPlayerRemoteGateway(),
                savedServerRepository = FakeSavedServerRepository()
            )
            viewModel.onHostChanged("lms.local")

            viewModel.testConnection()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.savedServers.isEmpty())
            assertNull(viewModel.uiState.value.serverProfileId)
        }
    }

    @Test
    fun connectingToSavedProfileReturnsConnectionResult() {
        runViewModelTest {
            val savedServerRepository = FakeSavedServerRepository()
            val connection = LmsServerConnection("lms.local")
            val profile = savedServerRepository.save(connection)
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { LmsServerInfo("9.2.0", 1) },
                LmsPlayerDirectory { emptyList() },
                unusedPlayerRemoteGateway(),
                savedServerRepository = savedServerRepository
            )

            assertTrue(viewModel.connectSavedServer(profile.id))

            assertEquals(connection, viewModel.uiState.value.activeConnection)
            assertEquals(profile.id, viewModel.uiState.value.serverProfileId)
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
    fun invalidHostDoesNotCallGateway() {
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
            viewModel.onHostChanged("lms.local/path")

            viewModel.testConnection()
            advanceUntilIdle()

            assertEquals(0, requestCount)
            assertEquals(
                "Enter a hostname or IP address without a scheme or path.",
                viewModel.uiState.value.errorMessage
            )
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
            val remoteGateway = object : TestPlayerRemoteGateway() {
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
            assertEquals(5, statusRequestCount)
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
    fun refreshingPlayerStatusPublishesTheLatestPosition() {
        runViewModelTest {
            var positionSeconds = 12.0
            val remoteGateway = object : TestPlayerRemoteGateway() {
                override suspend fun getPlayerStatus(
                    connection: LmsServerConnection,
                    playerId: String
                ) = LmsPlayerStatus(
                    LmsPlaybackMode.PLAYING,
                    "Track",
                    "Artist",
                    "Album",
                    positionSeconds,
                    180.0,
                    true
                )

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
                ) = Unit
            }
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { LmsServerInfo("9.2.0", 1) },
                LmsPlayerDirectory {
                    listOf(LmsPlayer("aa:bb", "Living Room", "Squeezebox", true, true))
                },
                remoteGateway
            )
            viewModel.onHostChanged("lms.local")
            viewModel.testConnection()
            advanceUntilIdle()
            viewModel.selectPlayer("aa:bb")
            advanceUntilIdle()

            positionSeconds = 13.0
            viewModel.refreshPlayerStatus()

            assertEquals(13.0, viewModel.uiState.value.playerStatus?.positionSeconds ?: 0.0, 0.0)
        }
    }

    @Test
    fun queueMutationsProtectCurrentTrackAndRefreshAbsoluteIndexes() {
        runViewModelTest {
            var currentIndex = 0
            var queue = mutableListOf(
                LmsQueueItem(0, "current", "Current", "Artist", "Album", 100.0, null, null),
                LmsQueueItem(1, "next", "Next", "Artist", "Album", 110.0, null, null),
                LmsQueueItem(2, "later", "Later", "Artist", "Album", 120.0, null, null)
            )
            val mutations = mutableListOf<String>()
            val remoteGateway = object : TestPlayerRemoteGateway() {
                override suspend fun getPlayerStatus(
                    connection: LmsServerConnection,
                    playerId: String
                ) = LmsPlayerStatus(
                    LmsPlaybackMode.PLAYING,
                    "Current",
                    "Artist",
                    "Album",
                    0.0,
                    100.0,
                    true,
                    playlistIndex = currentIndex,
                    trackId = "current"
                )

                override suspend fun getPlayerQueue(
                    connection: LmsServerConnection,
                    playerId: String,
                    offset: Int,
                    limit: Int
                ): LmsPlayerQueuePage {
                    val indexedQueue = queue.mapIndexed { index, item -> item.copy(index = index) }
                    return LmsPlayerQueuePage(
                        indexedQueue.drop(offset).take(limit),
                        offset,
                        indexedQueue.size,
                        currentIndex
                    )
                }

                override suspend fun addQueueItem(
                    connection: LmsServerConnection,
                    playerId: String,
                    item: LmsLibraryItem,
                    position: LmsQueueAddPosition
                ) {
                    mutations += "add:${position.name}:${item.id}"
                    val target = LmsQueueItem(
                        queue.size,
                        item.id,
                        item.title,
                        null,
                        null,
                        null,
                        null,
                        null
                    )
                    val insertionIndex = if (position == LmsQueueAddPosition.NEXT) currentIndex + 1 else queue.size
                    queue.add(insertionIndex, target)
                }

                override suspend fun removeQueueItem(
                    connection: LmsServerConnection,
                    playerId: String,
                    index: Int
                ) {
                    mutations += "remove:$index"
                    queue.removeAt(index)
                    if (index < currentIndex) currentIndex--
                }

                override suspend fun moveQueueItem(
                    connection: LmsServerConnection,
                    playerId: String,
                    fromIndex: Int,
                    toIndex: Int
                ) {
                    mutations += "move:$fromIndex:$toIndex"
                    queue.add(toIndex, queue.removeAt(fromIndex))
                }
            }
            val viewModel = connectedViewModel(remoteGateway)
            advanceUntilIdle()
            viewModel.selectPlayer("aa:bb")
            advanceUntilIdle()
            assertEquals(3, viewModel.uiState.value.playerQueueTotalCount)

            viewModel.removePlayerQueueItem(0)
            advanceUntilIdle()
            assertTrue(mutations.isEmpty())

            viewModel.removePlayerQueueItem(1)
            advanceUntilIdle()
            viewModel.addPlayerQueueItem(
                LmsLibraryItem("end", LmsLibraryItemType.TRACK, "End"),
                LmsQueueAddPosition.END
            )
            advanceUntilIdle()
            viewModel.addPlayerQueueItem(
                LmsLibraryItem("next", LmsLibraryItemType.TRACK, "Next up"),
                LmsQueueAddPosition.NEXT
            )
            advanceUntilIdle()
            viewModel.movePlayerQueueItem(fromIndex = 3, toIndex = 2)
            advanceUntilIdle()

            assertEquals(listOf("remove:1", "add:END:end", "add:NEXT:next", "move:3:2"), mutations)
            assertEquals(listOf("Current", "Next up", "End", "Later"), queue.map { it.title })
            assertEquals(0, viewModel.uiState.value.playerQueueCurrentIndex)
            assertEquals(4, viewModel.uiState.value.playerQueueTotalCount)
            assertFalse(viewModel.uiState.value.isMutatingPlayerQueue)
        }
    }

    @Test
    fun queueRefreshStartsAtTheNewCurrentTrackAfterPlaybackAdvances() {
        runViewModelTest {
            var currentIndex = 0
            var currentTrackId = "track-0"
            val offsets = mutableListOf<Int>()
            val queue = listOf(
                LmsQueueItem(0, "track-0", "Track 0", null, null, null, null, null),
                LmsQueueItem(1, "track-1", "Track 1", null, null, null, null, null),
                LmsQueueItem(2, "track-2", "Track 2", null, null, null, null, null)
            )
            val remoteGateway = object : TestPlayerRemoteGateway() {
                override suspend fun getPlayerStatus(
                    connection: LmsServerConnection,
                    playerId: String
                ) = LmsPlayerStatus(
                    LmsPlaybackMode.PLAYING,
                    "Track $currentIndex",
                    null,
                    null,
                    0.0,
                    100.0,
                    true,
                    playlistIndex = currentIndex,
                    trackId = currentTrackId
                )

                override suspend fun getPlayerQueue(
                    connection: LmsServerConnection,
                    playerId: String,
                    offset: Int,
                    limit: Int
                ): LmsPlayerQueuePage {
                    offsets += offset
                    return LmsPlayerQueuePage(
                        queue.drop(offset).take(limit),
                        offset,
                        queue.size,
                        currentIndex
                    )
                }
            }
            val viewModel = connectedViewModel(remoteGateway)
            advanceUntilIdle()
            viewModel.selectPlayer("aa:bb")
            advanceUntilIdle()

            currentIndex = 1
            currentTrackId = "track-1"
            viewModel.refreshPlayerStatus()

            assertEquals(listOf(0, 1), offsets)
            assertEquals(1, viewModel.uiState.value.playerQueueOffset)
            assertEquals(listOf(1, 2), viewModel.uiState.value.playerQueue.map { it.index })
        }
    }

    @Test
    fun fullQueueLoadsAdditionalBoundedPages() {
        runViewModelTest {
            val queue = (0 until 150).map { index ->
                LmsQueueItem(index, "track-$index", "Track $index", null, null, null, null, null)
            }
            val offsets = mutableListOf<Int>()
            val remoteGateway = object : TestPlayerRemoteGateway() {
                override suspend fun getPlayerStatus(
                    connection: LmsServerConnection,
                    playerId: String
                ) = LmsPlayerStatus(
                    LmsPlaybackMode.PLAYING,
                    "Track 0",
                    null,
                    null,
                    0.0,
                    100.0,
                    true,
                    playlistIndex = 0,
                    trackId = "track-0"
                )

                override suspend fun getPlayerQueue(
                    connection: LmsServerConnection,
                    playerId: String,
                    offset: Int,
                    limit: Int
                ): LmsPlayerQueuePage {
                    offsets += offset
                    assertEquals(100, limit)
                    return LmsPlayerQueuePage(queue.drop(offset).take(limit), offset, queue.size, 0)
                }
            }
            val viewModel = connectedViewModel(remoteGateway)
            advanceUntilIdle()
            viewModel.selectPlayer("aa:bb")
            advanceUntilIdle()
            assertEquals(100, viewModel.uiState.value.playerQueue.size)

            viewModel.loadMorePlayerQueue()
            advanceUntilIdle()

            assertEquals(listOf(0, 100), offsets)
            assertEquals(150, viewModel.uiState.value.playerQueue.size)
            assertEquals(150, viewModel.uiState.value.playerQueueTotalCount)
            assertEquals(listOf(100, 149), listOf(
                viewModel.uiState.value.playerQueue[100].index,
                viewModel.uiState.value.playerQueue.last().index
            ))
        }
    }

    @Test
    fun queueMutationAndRefreshFailuresAreReportedAndClearPendingState() {
        runViewModelTest {
            var failMutation = true
            var failQueueRefresh = false
            var queueSize = 1
            val remoteGateway = object : TestPlayerRemoteGateway() {
                override suspend fun getPlayerStatus(
                    connection: LmsServerConnection,
                    playerId: String
                ) = LmsPlayerStatus(
                    LmsPlaybackMode.PLAYING,
                    "Current",
                    null,
                    null,
                    0.0,
                    100.0,
                    true,
                    playlistIndex = 0,
                    trackId = "current"
                )

                override suspend fun getPlayerQueue(
                    connection: LmsServerConnection,
                    playerId: String,
                    offset: Int,
                    limit: Int
                ): LmsPlayerQueuePage {
                    if (failQueueRefresh) error("queue unavailable")
                    return LmsPlayerQueuePage(
                        listOf(LmsQueueItem(0, "current", "Current", null, null, null, null, null))
                            .take(queueSize),
                        offset,
                        queueSize,
                        0
                    )
                }

                override suspend fun addQueueItem(
                    connection: LmsServerConnection,
                    playerId: String,
                    item: LmsLibraryItem,
                    position: LmsQueueAddPosition
                ) {
                    if (failMutation) error("mutation failed")
                    queueSize++
                }
            }
            val viewModel = connectedViewModel(remoteGateway)
            advanceUntilIdle()
            viewModel.selectPlayer("aa:bb")
            advanceUntilIdle()

            viewModel.addPlayerQueueItem(
                LmsLibraryItem("new", LmsLibraryItemType.TRACK, "New"),
                LmsQueueAddPosition.END
            )
            advanceUntilIdle()
            assertEquals("Could not update this player's queue.", viewModel.uiState.value.playerQueueErrorMessage)
            assertFalse(viewModel.uiState.value.isMutatingPlayerQueue)

            failMutation = false
            failQueueRefresh = true
            viewModel.addPlayerQueueItem(
                LmsLibraryItem("new", LmsLibraryItemType.TRACK, "New"),
                LmsQueueAddPosition.END
            )
            advanceUntilIdle()
            assertEquals("The queue changed, but could not be refreshed.", viewModel.uiState.value.playerQueueErrorMessage)
            assertFalse(viewModel.uiState.value.isLoadingPlayerQueue)
            assertFalse(viewModel.uiState.value.isMutatingPlayerQueue)
        }
    }

    @Test
    fun playerReadFailuresAreReportedIndependentlyAndOnRefresh() {
        runViewModelTest {
            val remoteGateway = object : TestPlayerRemoteGateway() {
                override suspend fun getPlayerStatus(
                    connection: LmsServerConnection,
                    playerId: String
                ): LmsPlayerStatus = throw IllegalStateException("status unavailable")

                override suspend fun getPlayerMixerState(
                    connection: LmsServerConnection,
                    playerId: String
                ): LmsPlayerMixerState = throw IllegalStateException("mixer unavailable")

                override suspend fun getPlayerPlaybackOptions(
                    connection: LmsServerConnection,
                    playerId: String
                ): LmsPlayerPlaybackOptions = throw IllegalStateException("options unavailable")

                override suspend fun sendCommand(
                    connection: LmsServerConnection,
                    playerId: String,
                    command: LmsPlayerCommand
                ) = Unit
            }
            val viewModel = connectedViewModel(remoteGateway)
            advanceUntilIdle()

            viewModel.selectPlayer("aa:bb")
            advanceUntilIdle()

            assertEquals("Could not read this player's status.", viewModel.uiState.value.playerErrorMessage)
            assertEquals("Could not read this player's volume.", viewModel.uiState.value.playerMixerErrorMessage)
            assertEquals(
                "Could not read this player's shuffle and repeat settings.",
                viewModel.uiState.value.playerPlaybackOptionsErrorMessage
            )
            assertFalse(viewModel.uiState.value.isLoadingPlayerStatus)
            assertFalse(viewModel.uiState.value.isLoadingMixerState)
            assertFalse(viewModel.uiState.value.isLoadingPlaybackOptions)

            viewModel.refreshPlayerStatus()

            assertEquals("Could not read this player's status.", viewModel.uiState.value.playerErrorMessage)
        }
    }

    @Test
    fun commandAndPowerFailuresClearPendingStateAndReportErrors() {
        runViewModelTest {
            val remoteGateway = object : TestPlayerRemoteGateway() {
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
                ) = throw IllegalStateException("command failed")
            }
            val viewModel = connectedViewModel(remoteGateway)
            advanceUntilIdle()
            viewModel.selectPlayer("aa:bb")
            advanceUntilIdle()

            viewModel.sendPlayerCommand(LmsPlayerCommand.SetVolume(25))
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isSendingPlayerCommand)
            assertFalse(viewModel.uiState.value.isLoadingMixerState)
            assertEquals("The player command could not be completed.", viewModel.uiState.value.playerErrorMessage)
            assertEquals("Could not refresh this player's volume.", viewModel.uiState.value.playerMixerErrorMessage)

            viewModel.setPlayerPower("aa:bb", false)
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.powerUpdatingPlayerId)
            assertEquals(
                "Could not change this player's power state.",
                viewModel.uiState.value.playerPowerErrorMessage
            )
        }
    }

    @Test
    fun powerCommandRefreshesPlayerAndClearsActiveTargetWhenPoweredOff() {
        runViewModelTest {
            var poweredOn = true
            val activePlayerStore = LmsActivePlayerStore()
            val remoteGateway = object : TestPlayerRemoteGateway() {
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

    @Test
    fun playerGroupBuilderStagesChangesAndSoloSelectionDissolvesTheLiveGroup() {
        runViewModelTest {
            val players = listOf(
                LmsPlayer("aa:bb", "Living Room", "Squeezebox", true, true),
                LmsPlayer("cc:dd", "Kitchen", "Squeezebox", true, true),
                LmsPlayer("ee:ff", "Bedroom", "Squeezebox", true, true)
            )
            val liveGroup = linkedSetOf<String>()
            val syncCommands = mutableListOf<Pair<String, String>>()
            val unsyncCommands = mutableListOf<String>()
            val remoteGateway = object : TestPlayerRemoteGateway() {
                override suspend fun getPlayerStatus(
                    connection: LmsServerConnection,
                    playerId: String
                ) = LmsPlayerStatus(
                    LmsPlaybackMode.PLAYING,
                    "Track $playerId",
                    "Artist",
                    "Album",
                    0.0,
                    120.0,
                    true
                )

                override suspend fun getSyncGroupMembers(
                    connection: LmsServerConnection,
                    playerId: String
                ) = liveGroup.takeIf { playerId in it }?.toList() ?: listOf(playerId)

                override suspend fun syncPlayers(
                    connection: LmsServerConnection,
                    leaderPlayerId: String,
                    memberPlayerId: String
                ) {
                    syncCommands += leaderPlayerId to memberPlayerId
                    liveGroup += leaderPlayerId
                    liveGroup += memberPlayerId
                }

                override suspend fun unsyncPlayer(connection: LmsServerConnection, playerId: String) {
                    unsyncCommands += playerId
                    liveGroup -= playerId
                }
            }
            val activePlayerStore = LmsActivePlayerStore()
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { LmsServerInfo("9.2.0", players.size) },
                LmsPlayerDirectory { players },
                remoteGateway,
                activePlayerStore
            )
            viewModel.onHostChanged("lms.local")
            viewModel.testConnection()
            advanceUntilIdle()

            assertEquals("Track aa:bb", viewModel.uiState.value.playerStatusesById["aa:bb"]?.title)
            viewModel.beginGroupBuilder()
            viewModel.setGroupBuilderPlayerSelected("aa:bb", true)
            viewModel.setGroupBuilderPlayerSelected("cc:dd", true)
            advanceUntilIdle()
            assertTrue(syncCommands.isEmpty())
            assertTrue(viewModel.uiState.value.selectedPlayerIds.isEmpty())

            viewModel.applyGroupBuilder()
            advanceUntilIdle()
            assertEquals(listOf("aa:bb" to "cc:dd"), syncCommands)
            assertEquals(listOf("aa:bb", "cc:dd"), activePlayerStore.activePlayer.value?.selectedPlayerIds)

            viewModel.addPlayerToCurrentSelection("ee:ff")
            advanceUntilIdle()
            assertEquals(
                listOf("aa:bb" to "cc:dd", "aa:bb" to "ee:ff"),
                syncCommands
            )
            assertEquals("aa:bb", activePlayerStore.activePlayer.value?.playerId)

            viewModel.selectPlayer("cc:dd")
            advanceUntilIdle()
            assertEquals(listOf("cc:dd"), viewModel.uiState.value.selectedPlayerIds)
            assertEquals("cc:dd", activePlayerStore.activePlayer.value?.playerId)
            assertTrue(liveGroup.isEmpty())
            assertEquals(setOf("aa:bb", "cc:dd", "ee:ff"), unsyncCommands.toSet())
        }
    }

    @Test
    fun savedPlayerGroupsCanBeSavedSelectedAndDeletedWithoutChangingLiveSync() {
        runViewModelTest {
            val players = listOf(
                LmsPlayer("aa:bb", "Living Room", "Squeezebox", true, true),
                LmsPlayer("cc:dd", "Kitchen", "Squeezebox", true, true),
                LmsPlayer("ee:ff", "Bedroom", "Squeezebox", true, true)
            )
            val liveGroup = linkedSetOf<String>()
            val repository = FakePlayerGroupRepository()
            val remoteGateway = object : TestPlayerRemoteGateway() {
                override suspend fun getPlayerStatus(
                    connection: LmsServerConnection,
                    playerId: String
                ) = LmsPlayerStatus(
                    LmsPlaybackMode.PLAYING,
                    "Track $playerId",
                    "Artist",
                    "Album",
                    0.0,
                    120.0,
                    true
                )

                override suspend fun getSyncGroupMembers(
                    connection: LmsServerConnection,
                    playerId: String
                ) = liveGroup.takeIf { playerId in it }?.toList() ?: listOf(playerId)

                override suspend fun syncPlayers(
                    connection: LmsServerConnection,
                    leaderPlayerId: String,
                    memberPlayerId: String
                ) {
                    liveGroup += leaderPlayerId
                    liveGroup += memberPlayerId
                }
            }
            val activePlayerStore = LmsActivePlayerStore()
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { LmsServerInfo("9.2.0", players.size) },
                LmsPlayerDirectory { players },
                remoteGateway,
                activePlayerStore,
                playerGroupRepository = repository
            )
            viewModel.onHostChanged("lms.local")
            viewModel.testConnection()
            advanceUntilIdle()

            viewModel.beginGroupBuilder()
            viewModel.setGroupNameDraft("Whole home")
            viewModel.setGroupBuilderPlayerSelected("aa:bb", true)
            viewModel.setGroupBuilderPlayerSelected("cc:dd", true)
            viewModel.savePlayerGroup(viewModel.uiState.value.groupNameDraft)
            advanceUntilIdle()

            val savedGroup = viewModel.uiState.value.playerGroups.single()
            assertEquals("Whole home", savedGroup.name)
            assertEquals(listOf("aa:bb", "cc:dd"), savedGroup.playerIds)
            assertEquals("Player group saved.", viewModel.uiState.value.playerGroupMessage)

            viewModel.beginGroupBuilder(savedGroup)
            viewModel.setGroupBuilderPlayerSelected("ee:ff", true)
            viewModel.setGroupNameDraft("Whole home updated")
            viewModel.savePlayerGroup(
                viewModel.uiState.value.groupNameDraft,
                groupId = savedGroup.id
            )
            advanceUntilIdle()

            val updatedGroup = viewModel.uiState.value.playerGroups.single()
            assertEquals(savedGroup.id, updatedGroup.id)
            assertEquals("Whole home updated", updatedGroup.name)
            assertEquals(listOf("aa:bb", "cc:dd", "ee:ff"), updatedGroup.playerIds)
            assertEquals("Player group updated.", viewModel.uiState.value.playerGroupMessage)
            assertTrue(liveGroup.isEmpty())

            viewModel.selectPlayerGroup(updatedGroup.id)
            advanceUntilIdle()

            assertEquals(listOf("aa:bb", "cc:dd", "ee:ff"), viewModel.uiState.value.selectedPlayerIds)
            assertEquals("aa:bb", activePlayerStore.activePlayer.value?.playerId)

            viewModel.removePlayerGroup(savedGroup.id)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.playerGroups.isEmpty())
            assertEquals("Player group deleted.", viewModel.uiState.value.playerGroupMessage)
            assertEquals(setOf("aa:bb", "cc:dd", "ee:ff"), liveGroup)
        }
    }

    @Test
    fun unavailableAndInvalidSavedGroupsReportErrors() {
        runViewModelTest {
            val players = listOf(
                LmsPlayer("aa:bb", "Living Room", "Squeezebox", true, true),
                LmsPlayer("cc:dd", "Kitchen", "Squeezebox", true, true),
                LmsPlayer("ee:ff", "Bedroom", "Squeezebox", false, false)
            )
            val noRepositoryViewModel = ConnectionViewModel(
                LmsConnectionGateway { LmsServerInfo("9.2.0", players.size) },
                LmsPlayerDirectory { players },
                object : TestPlayerRemoteGateway() {}
            )
            noRepositoryViewModel.onHostChanged("lms.local")
            noRepositoryViewModel.testConnection()
            advanceUntilIdle()
            noRepositoryViewModel.savePlayerGroup("Whole home")
            assertEquals(
                "Saved player groups are not available.",
                noRepositoryViewModel.uiState.value.playerGroupErrorMessage
            )

            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { LmsServerInfo("9.2.0", players.size) },
                LmsPlayerDirectory { players },
                object : TestPlayerRemoteGateway() {},
                playerGroupRepository = FakePlayerGroupRepository()
            )
            viewModel.onHostChanged("lms.local")
            viewModel.testConnection()
            advanceUntilIdle()

            viewModel.savePlayerGroup(" ")
            assertEquals("Enter a name for this player group.", viewModel.uiState.value.playerGroupErrorMessage)
            viewModel.savePlayerGroup("Whole home", listOf("aa:bb"))
            assertEquals(
                "Select at least two connected players to save a group.",
                viewModel.uiState.value.playerGroupErrorMessage
            )
            viewModel.savePlayerGroup("Whole home", listOf("aa:bb", "ee:ff"))
            assertEquals(
                "Only connected players can be saved in a group.",
                viewModel.uiState.value.playerGroupErrorMessage
            )
            viewModel.selectPlayerGroup("missing")
            assertEquals(
                "This saved player group is no longer available.",
                viewModel.uiState.value.playerGroupErrorMessage
            )
            viewModel.savePlayerGroup(
                "Missing group",
                listOf("aa:bb", "cc:dd"),
                groupId = "missing"
            )
            advanceUntilIdle()
            assertEquals(
                "This saved player group no longer exists.",
                viewModel.uiState.value.playerGroupErrorMessage
            )
            viewModel.removePlayerGroup("missing")
            advanceUntilIdle()
            assertEquals(
                "This saved player group no longer exists.",
                viewModel.uiState.value.playerGroupErrorMessage
            )
        }
    }

    @Test
    fun restoringLegacyLastSessionPreservesItsCurrentSyncGroupAndLeader() {
        runViewModelTest {
            val players = listOf(
                LmsPlayer("aa:bb", "Living Room", "Squeezebox", true, true),
                LmsPlayer("cc:dd", "Kitchen", "Squeezebox", true, true)
            )
            val liveGroup = linkedSetOf("cc:dd", "aa:bb")
            var syncCommandCount = 0
            var unsyncCommandCount = 0
            val remoteGateway = object : TestPlayerRemoteGateway() {
                override suspend fun getSyncGroupMembers(
                    connection: LmsServerConnection,
                    playerId: String
                ) = liveGroup.toList()

                override suspend fun syncPlayers(
                    connection: LmsServerConnection,
                    leaderPlayerId: String,
                    memberPlayerId: String
                ) {
                    syncCommandCount++
                }

                override suspend fun unsyncPlayer(connection: LmsServerConnection, playerId: String) {
                    unsyncCommandCount++
                }
            }
            val activePlayerStore = LmsActivePlayerStore()
            val viewModel = ConnectionViewModel(
                LmsConnectionGateway { LmsServerInfo("9.2.0", players.size) },
                LmsPlayerDirectory { players },
                remoteGateway,
                activePlayerStore
            )
            viewModel.onHostChanged("lms.local")
            viewModel.testConnection()
            advanceUntilIdle()
            var restored = false
            var failed = false

            viewModel.restorePlayerSelection(
                playerId = "aa:bb",
                playerIds = emptyList(),
                onApplied = { restored = true },
                onFailure = { failed = true }
            )
            advanceUntilIdle()

            assertTrue(restored)
            assertFalse(failed)
            assertEquals(0, syncCommandCount)
            assertEquals(0, unsyncCommandCount)
            assertEquals("aa:bb", activePlayerStore.activePlayer.value?.playerId)
            assertEquals(setOf("aa:bb", "cc:dd"), activePlayerStore.activePlayer.value?.selectedPlayerIds?.toSet())
        }
    }

    private abstract class TestPlayerRemoteGateway : LmsPlayerRemoteGateway {
        override suspend fun getPlayerStatus(
            connection: LmsServerConnection,
            playerId: String
        ): LmsPlayerStatus = error("Unexpected player status request")

        override suspend fun getSyncGroupMembers(
            connection: LmsServerConnection,
            playerId: String
        ): List<String> = listOf(playerId)

        override suspend fun syncPlayers(
            connection: LmsServerConnection,
            leaderPlayerId: String,
            memberPlayerId: String
        ) = Unit

        override suspend fun unsyncPlayer(connection: LmsServerConnection, playerId: String) = Unit

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
        ): Unit = error("Unexpected player command")

        override suspend fun getPlayerQueue(
            connection: LmsServerConnection,
            playerId: String,
            offset: Int,
            limit: Int
        ) = LmsPlayerQueuePage(emptyList(), offset, 0, null)

        override suspend fun addQueueItem(
            connection: LmsServerConnection,
            playerId: String,
            item: LmsLibraryItem,
            position: LmsQueueAddPosition
        ) = Unit

        override suspend fun removeQueueItem(
            connection: LmsServerConnection,
            playerId: String,
            index: Int
        ) = Unit

        override suspend fun moveQueueItem(
            connection: LmsServerConnection,
            playerId: String,
            fromIndex: Int,
            toIndex: Int
        ) = Unit
    }

    private fun unusedPlayerRemoteGateway() = object : TestPlayerRemoteGateway() {
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

    private fun connectedViewModel(remoteGateway: LmsPlayerRemoteGateway) = ConnectionViewModel(
        LmsConnectionGateway { LmsServerInfo("9.2.0", 1) },
        LmsPlayerDirectory {
            listOf(LmsPlayer("aa:bb", "Living Room", "Squeezebox", true, true))
        },
        remoteGateway
    ).apply {
        onHostChanged("lms.local")
        testConnection()
    }

    private class FakeSavedServerRepository(
        private val saveFailure: Exception? = null
    ) : SavedServerRepository {
        private val connections = mutableMapOf<String, LmsServerConnection>()
        private val profiles = MutableStateFlow<List<SavedServerProfile>>(emptyList())

        override fun observeServers(): Flow<List<SavedServerProfile>> = profiles

        override suspend fun save(connection: LmsServerConnection): SavedServerProfile {
            saveFailure?.let { throw it }
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

    private class FakePlayerGroupRepository : PlayerGroupRepository {
        private val groups = MutableStateFlow<List<SavedLmsPlayerGroup>>(emptyList())

        override fun observeGroups(endpointKey: String): Flow<List<SavedLmsPlayerGroup>> =
            groups.map { savedGroups -> savedGroups.filter { it.endpointKey == endpointKey } }

        override suspend fun saveGroup(
            endpointKey: String,
            name: String,
            playerIds: List<String>
        ): SavedLmsPlayerGroup {
            val group = SavedLmsPlayerGroup(
                id = "group-${groups.value.size + 1}",
                endpointKey = endpointKey,
                name = name,
                playerIds = playerIds
            )
            groups.value = groups.value + group
            return group
        }

        override suspend fun updateGroup(
            endpointKey: String,
            groupId: String,
            name: String,
            playerIds: List<String>
        ): Boolean {
            val group = groups.value.firstOrNull {
                it.id == groupId && it.endpointKey == endpointKey
            } ?: return false
            groups.value = groups.value.map {
                if (it.id == groupId) group.copy(name = name, playerIds = playerIds) else it
            }
            return true
        }

        override suspend fun removeGroup(endpointKey: String, groupId: String): Boolean {
            val group = groups.value.firstOrNull {
                it.id == groupId && it.endpointKey == endpointKey
            } ?: return false
            groups.value = groups.value - group
            return true
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