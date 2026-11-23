package com.ryanmorrisroe.lyriamp.feature.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.ryanmorrisroe.lyriamp.data.lms.LmsConnectionGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItem
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerCommand
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerDirectory
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerMixerState
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerPlaybackOptions
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerQueuePage
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerRemoteGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerStatus
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlaybackMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsQueueAddPosition
import com.ryanmorrisroe.lyriamp.data.lms.LmsQueueItem
import com.ryanmorrisroe.lyriamp.data.lms.LmsRepeatMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerInfo
import com.ryanmorrisroe.lyriamp.data.lms.LmsShuffleMode
import com.ryanmorrisroe.lyriamp.feature.connection.ConnectionViewModel
import com.ryanmorrisroe.lyriamp.ui.theme.LyriampTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NowPlayingScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsCurrentPlayerAndQueueMetadataWithLibraryActions() {
        val remote = FakePlayerRemoteGateway()
        val viewModel = connectedViewModel(compose, remote)
        var selectedPlayer = false
        var openedArtist: String? = null
        var openedAlbum: String? = null

        compose.setContent {
            LyriampTheme(dynamicColor = false) {
                NowPlayingScreen(
                    player = null,
                    connectionViewModel = viewModel,
                    onBack = {},
                    onChoosePlayer = { selectedPlayer = true },
                    onAddToQueue = {},
                    onOpenQueue = {},
                    onOpenArtist = { openedArtist = it },
                    onOpenAlbum = { openedAlbum = it }
                )
            }
        }

        compose.onNodeWithContentDescription("Back").assertExists()
        compose.onNodeWithText("Living Room").performClick()
        compose.onNodeWithContentDescription("Previous").assertExists()
        compose.onNodeWithContentDescription("Pause").assertExists()
        compose.onNodeWithContentDescription("Next").assertExists()
        compose.onNodeWithText("Shuffle · Songs").assertExists()
        compose.onNodeWithText("Repeat · Playlist").assertExists()

        compose.onNodeWithText("Queue Artist").performClick()
        compose.onNodeWithText("Queue Album").performClick()
        compose.onNodeWithContentDescription("Remove Next Track from queue")
            .performScrollTo()
            .assertExists()
        compose.runOnIdle {
            assertEquals(true, selectedPlayer)
            assertEquals("Queue Artist", openedArtist)
            assertEquals("Queue Album", openedAlbum)
        }
    }

    @Test
    fun fullQueueUsesLongPressDragAndXRemoval() {
        val remote = FakePlayerRemoteGateway()
        val viewModel = connectedViewModel(compose, remote)

        compose.setContent {
            LyriampTheme(dynamicColor = false) {
                PlayerQueueScreen(
                    connectionViewModel = viewModel,
                    onBack = {},
                    onAddToQueue = {}
                )
            }
        }

        compose.onNodeWithContentDescription("Back").assertExists()
        compose.onNodeWithContentDescription("Remove Next Track from queue").assertExists()
        compose.onNodeWithText("Up").assertDoesNotExist()
        compose.onNodeWithText("Down").assertDoesNotExist()
        val dragHandleBounds = compose.onNodeWithContentDescription(
            "Drag Next Track to reorder"
        ).fetchSemanticsNode().boundsInRoot
        val artworkBounds = compose.onNodeWithTag("queue-artwork-1")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(dragHandleBounds.right <= artworkBounds.left)
        compose.onNodeWithContentDescription("Drag Next Track to reorder")
            .performTouchInput {
                down(center)
                moveBy(Offset(0f, 100f), delayMillis = 400)
                up()
            }

        compose.waitUntil(5_000) { remote.moves.isNotEmpty() }
        assertEquals(listOf(1 to 2), remote.moves.toList())
    }

    private fun connectedViewModel(
        compose: ComposeContentTestRule,
        remote: FakePlayerRemoteGateway
    ): ConnectionViewModel {
        val viewModel = ConnectionViewModel(
            LmsConnectionGateway { LmsServerInfo("test", 1) },
            LmsPlayerDirectory {
                listOf(LmsPlayer("player-1", "Living Room", "Test", true, true))
            },
            remote
        )
        viewModel.onHostChanged("lms.local")
        viewModel.testConnection()
        compose.waitUntil(5_000) { viewModel.uiState.value.activeConnection != null }
        viewModel.selectPlayer("player-1")
        compose.waitUntil(5_000) { !viewModel.uiState.value.isLoadingPlayerQueue }
        return viewModel
    }

    private class FakePlayerRemoteGateway : LmsPlayerRemoteGateway {
        private val queue = mutableListOf(
            LmsQueueItem(0, "current", "Current Track", "Queue Artist", "Queue Album", 120.0, null, null),
            LmsQueueItem(1, "next", "Next Track", "Next Artist", "Next Album", 130.0, null, null),
            LmsQueueItem(2, "last", "Last Track", "Last Artist", "Last Album", 140.0, null, null)
        )
        val moves = CopyOnWriteArrayList<Pair<Int, Int>>()

        override suspend fun getPlayerStatus(
            connection: LmsServerConnection,
            playerId: String
        ) = LmsPlayerStatus(
            mode = LmsPlaybackMode.PLAYING,
            title = "Current Track",
            artist = null,
            album = null,
            positionSeconds = 12.0,
            durationSeconds = 120.0,
            canSeek = true,
            playlistIndex = 0,
            trackId = "current"
        )

        override suspend fun getSyncGroupMembers(
            connection: LmsServerConnection,
            playerId: String
        ) = listOf(playerId)

        override suspend fun syncPlayers(
            connection: LmsServerConnection,
            leaderPlayerId: String,
            memberPlayerId: String
        ) = Unit

        override suspend fun unsyncPlayer(connection: LmsServerConnection, playerId: String) = Unit

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
                0
            )
        }

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
        ) {
            queue.removeAt(index)
        }

        override suspend fun moveQueueItem(
            connection: LmsServerConnection,
            playerId: String,
            fromIndex: Int,
            toIndex: Int
        ) {
            moves += fromIndex to toIndex
            queue.add(toIndex, queue.removeAt(fromIndex))
        }

        override suspend fun getPlayerMixerState(
            connection: LmsServerConnection,
            playerId: String
        ) = LmsPlayerMixerState(50, false)

        override suspend fun getPlayerPlaybackOptions(
            connection: LmsServerConnection,
            playerId: String
        ) = LmsPlayerPlaybackOptions(LmsShuffleMode.SONGS, LmsRepeatMode.PLAYLIST)

        override suspend fun sendCommand(
            connection: LmsServerConnection,
            playerId: String,
            command: LmsPlayerCommand
        ) = Unit
    }
}
