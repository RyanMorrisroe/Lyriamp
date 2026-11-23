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
            assertNull(viewModel.uiState.value.serverProfileId)
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

    private abstract class TestPlayerRemoteGateway : LmsPlayerRemoteGateway {
        override suspend fun getPlayerStatus(
            connection: LmsServerConnection,
            playerId: String
        ): LmsPlayerStatus = error("Unexpected player status request")

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