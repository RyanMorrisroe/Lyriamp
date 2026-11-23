package com.ryanmorrisroe.lyriamp.feature.connection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayerStore
import com.ryanmorrisroe.lyriamp.data.lms.LmsConnectionGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerCommand
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerDirectory
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItem
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerMixerState
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerPlaybackOptions
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerQueuePage
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerRemoteGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerStatus
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlaybackMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsQueueAddPosition
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerInfo
import com.ryanmorrisroe.lyriamp.data.lms.LmsRepeatMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsShuffleMode
import com.ryanmorrisroe.lyriamp.data.lms.SavedLmsPlayerGroup
import com.ryanmorrisroe.lyriamp.data.persistence.PlayerGroupRepository
import com.ryanmorrisroe.lyriamp.ui.theme.LyriampTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlayerSelectionScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun builderStagesPlayersUntilUseTheseRooms() {
        val remote = FakePlayerRemoteGateway()
        val viewModel = connectedViewModel(remote)
        var selectedForLibrary: LmsPlayer? = null
        showScreen(viewModel) { selectedForLibrary = it }

        compose.onNodeWithText("Build a room group").performClick()
        val dragHandle = compose.onNodeWithTag(
            "player-drag-handle-room-a",
            useUnmergedTree = true
        )
        val sourceBounds = dragHandle.fetchSemanticsNode().boundsInRoot
        val playerNameBounds = compose.onNodeWithText("Living Room")
            .fetchSemanticsNode().boundsInRoot
        assertTrue(sourceBounds.right <= playerNameBounds.left)
        val groupBounds = compose.onNodeWithText(
            "Drop players here to add them."
        ).fetchSemanticsNode().boundsInRoot
        dragHandle.performTouchInput {
            down(center)
            moveBy(groupBounds.center - sourceBounds.center, delayMillis = 100)
        }
        compose.onNodeWithTag("player-drag-preview").assertIsDisplayed()
        dragHandle.performTouchInput { up() }
        compose.waitUntil(5_000) {
            "room-a" in viewModel.uiState.value.groupBuilderPlayerIds
        }
        compose.onAllNodesWithText("Add").onFirst().performClick()

        compose.runOnIdle {
            assertTrue(viewModel.uiState.value.selectedPlayerIds.isEmpty())
            assertTrue(remote.syncCommands.isEmpty())
        }

        compose.onNodeWithText("Use these rooms").performClick()
        compose.waitUntil(5_000) {
            viewModel.uiState.value.selectedPlayerIds.size == 2 &&
                remote.syncCommands.size == 1
        }

        assertEquals(listOf("room-a", "room-b"), viewModel.uiState.value.selectedPlayerIds)
        assertEquals(listOf("room-a" to "room-b"), remote.syncCommands)
        assertEquals("Living Room", selectedForLibrary?.name)
    }

    @Test
    fun draggingAPlayerToCurrentSelectionAddsItImmediately() {
        val remote = FakePlayerRemoteGateway()
        val viewModel = connectedViewModel(remote)
        var selectedForLibrary: LmsPlayer? = null
        showScreen(viewModel) { selectedForLibrary = it }
        compose.onNodeWithText("Living Room").performClick()
        compose.waitUntil(5_000) {
            viewModel.uiState.value.selectedPlayerIds == listOf("room-a") &&
                selectedForLibrary?.id == "room-a"
        }
        selectedForLibrary = null
        val dragHandle = compose.onNodeWithTag(
            "player-drag-handle-room-b",
            useUnmergedTree = true
        )
        val sourceBounds = dragHandle.fetchSemanticsNode().boundsInRoot
        val playerNameBounds = compose.onNodeWithText(
            "Kitchen",
            useUnmergedTree = true
        ).fetchSemanticsNode().boundsInRoot
        assertTrue(sourceBounds.right <= playerNameBounds.left)
        val selectionBounds = compose.onNodeWithText("Current selection").fetchSemanticsNode().boundsInRoot
        val dragDelta = selectionBounds.center - sourceBounds.center

        dragHandle.performTouchInput {
            down(center)
            moveBy(dragDelta, delayMillis = 100)
        }
        compose.onNodeWithTag("player-drag-preview").assertIsDisplayed()
        dragHandle.performTouchInput { up() }

        compose.waitUntil(5_000) {
            viewModel.uiState.value.selectedPlayerIds.size == 2 &&
                remote.syncCommands.isNotEmpty()
        }
        compose.onNodeWithText("Song A · Artist A").assertExists()
        assertEquals(listOf("room-a", "room-b"), viewModel.uiState.value.selectedPlayerIds)
        assertEquals(listOf("room-a" to "room-b"), remote.syncCommands)
        assertNull(selectedForLibrary)
    }

    @Test
    fun savingPresetThenSelectingItNavigatesToLibrary() {
        val remote = FakePlayerRemoteGateway()
        val repository = FakePlayerGroupRepository()
        val viewModel = connectedViewModel(remote, repository)
        var selectedForLibrary: LmsPlayer? = null
        showScreen(viewModel) { selectedForLibrary = it }

        compose.onNodeWithText("Build a room group").performClick()
        compose.onAllNodesWithText("Add").onFirst().performClick()
        compose.onAllNodesWithText("Add").onFirst().performClick()
        compose.onNodeWithText("Save group").performClick()

        compose.waitUntil(5_000) { viewModel.uiState.value.playerGroups.size == 1 }
        assertEquals("Room group", viewModel.uiState.value.playerGroups.single().name)
        assertTrue(viewModel.uiState.value.selectedPlayerIds.isEmpty())
        assertTrue(remote.syncCommands.isEmpty())

        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Room group").performClick()
        compose.waitUntil(5_000) { selectedForLibrary?.id == "room-a" }

        assertEquals(listOf("room-a", "room-b"), viewModel.uiState.value.selectedPlayerIds)
        assertEquals(listOf("room-a" to "room-b"), remote.syncCommands)
        assertEquals("Living Room", selectedForLibrary?.name)
    }

    @Test
    fun editingSavedGroupUpdatesPresetWithoutApplyingIt() {
        val remote = FakePlayerRemoteGateway()
        val group = SavedLmsPlayerGroup(
            id = "whole-home",
            endpointKey = "http://lms.local:9000",
            name = "Whole home",
            playerIds = listOf("room-a", "room-b")
        )
        val repository = FakePlayerGroupRepository(listOf(group))
        val players = listOf(
            LmsPlayer("room-a", "Living Room", "Squeezebox", true, true),
            LmsPlayer("room-b", "Kitchen", "Squeezebox", true, true),
            LmsPlayer("room-c", "Bedroom", "Squeezebox", true, true)
        )
        val viewModel = connectedViewModel(remote, repository, players)
        showScreen(viewModel)
        compose.waitUntil(5_000) { viewModel.uiState.value.playerGroups.size == 1 }

        compose.onNodeWithText("Whole home").performTouchInput {
            down(center)
            advanceEventTime(700)
            up()
        }
        compose.onNodeWithText("Edit group").performClick()
        compose.onNodeWithText("Save changes").assertIsDisplayed()
        val groupNameField = compose.onNodeWithTag("group-name-input")
        groupNameField.performTextClearance()
        groupNameField.performTextInput("Renamed")
        compose.onAllNodesWithText("Add").onFirst().performClick()
        compose.onNodeWithText("Save changes").performClick()

        compose.waitUntil(5_000) {
            viewModel.uiState.value.playerGroups.singleOrNull()?.name == "Renamed"
        }
        val updatedGroup = viewModel.uiState.value.playerGroups.single()
        assertEquals(group.id, updatedGroup.id)
        assertEquals(listOf("room-a", "room-b", "room-c"), updatedGroup.playerIds)
        assertTrue(viewModel.uiState.value.selectedPlayerIds.isEmpty())
        assertTrue(remote.syncCommands.isEmpty())
    }

    @Test
    fun scrollingKeepsSelectionDropTargetAvailableForDrag() {
        val remote = FakePlayerRemoteGateway()
        val players = (0..9).map { index ->
            LmsPlayer("room-$index", "Room $index", "Squeezebox", true, true)
        }
        val viewModel = connectedViewModel(remote, players = players)
        var selectedForLibrary: LmsPlayer? = null
        showScreen(viewModel) { selectedForLibrary = it }

        compose.onNodeWithText("Room 0").performClick()
        compose.waitUntil(5_000) { selectedForLibrary?.id == "room-0" }
        selectedForLibrary = null

        val listBounds = compose.onNodeWithTag("player-selection-list")
            .fetchSemanticsNode().boundsInRoot
        repeat(3) {
            compose.onNodeWithTag("player-selection-list").performTouchInput {
                val start = Offset(listBounds.width / 2f, listBounds.height * 0.8f)
                val end = Offset(listBounds.width / 2f, listBounds.height * 0.25f)
                down(start)
                repeat(10) { step ->
                    val progress = (step + 1) / 10f
                    moveTo(
                        Offset(start.x, start.y + (end.y - start.y) * progress),
                        delayMillis = 16
                    )
                }
                up()
            }
        }
        compose.onNodeWithText("Room 9").assertIsDisplayed()
        compose.onNodeWithText("Current selection").assertIsDisplayed()
        val dragHandle = compose.onNodeWithTag(
            "player-drag-handle-room-9",
            useUnmergedTree = true
        )
        val sourceBounds = dragHandle.fetchSemanticsNode().boundsInRoot
        val selectionBounds = compose.onNodeWithText("Current selection").fetchSemanticsNode().boundsInRoot

        dragHandle.performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(selectionBounds.center - sourceBounds.center, delayMillis = 100)
            up()
        }
        compose.waitUntil(5_000) { viewModel.uiState.value.selectedPlayerIds.size == 2 }

        assertEquals(listOf("room-0", "room-9"), viewModel.uiState.value.selectedPlayerIds)
        assertNull(selectedForLibrary)
    }

    @Test
    fun longPressRevealsDeleteAndConfirmationOnlyRemovesThePreset() {
        val remote = FakePlayerRemoteGateway()
        val group = SavedLmsPlayerGroup(
            id = "whole-home",
            endpointKey = "http://lms.local:9000",
            name = "Whole home",
            playerIds = listOf("room-a", "room-b")
        )
        val repository = FakePlayerGroupRepository(listOf(group))
        val viewModel = connectedViewModel(remote, repository)
        showScreen(viewModel)

        compose.onNodeWithText("Whole home").performTouchInput {
            down(center)
            advanceEventTime(700)
            up()
        }
        compose.onNodeWithText("Delete group").assertExists()
        compose.onNodeWithText("Delete group").performClick()
        compose.onNodeWithText(
            "Delete \"Whole home\"? This removes the saved preset only; current playback and sync are unchanged."
        ).assertExists()
        compose.onAllNodesWithText("Delete group").onLast().performClick()

        compose.waitUntil(5_000) { viewModel.uiState.value.playerGroups.isEmpty() }
        assertTrue(remote.syncCommands.isEmpty())
    }

    private fun connectedViewModel(
        remote: FakePlayerRemoteGateway,
        playerGroupRepository: PlayerGroupRepository? = null,
        players: List<LmsPlayer> = listOf(
            LmsPlayer("room-a", "Living Room", "Squeezebox", true, true),
            LmsPlayer("room-b", "Kitchen", "Squeezebox", true, true)
        )
    ): ConnectionViewModel {
        return ConnectionViewModel(
            LmsConnectionGateway { LmsServerInfo("test", players.size) },
            LmsPlayerDirectory { players },
            remote,
            LmsActivePlayerStore(),
            playerGroupRepository = playerGroupRepository
        ).also {
            it.onHostChanged("lms.local")
            it.testConnection()
            compose.waitUntil(5_000) { it.uiState.value.activeConnection != null }
            compose.waitUntil(5_000) { it.uiState.value.playerStatusesById.size == players.size }
        }
    }

    private fun showScreen(
        viewModel: ConnectionViewModel,
        onPlayerSelected: (LmsPlayer) -> Unit = {}
    ) {
        compose.setContent {
            LyriampTheme(dynamicColor = false) {
                PlayerSelectionScreen(
                    viewModel = viewModel,
                    onPlayerSelected = onPlayerSelected,
                    onBack = {},
                    onChangeServer = {}
                )
            }
        }
    }

    private class FakePlayerRemoteGateway : LmsPlayerRemoteGateway {
        val syncCommands = mutableListOf<Pair<String, String>>()
        private val syncedPlayers = linkedSetOf<String>()

        override suspend fun getPlayerStatus(
            connection: LmsServerConnection,
            playerId: String
        ) = LmsPlayerStatus(
            mode = LmsPlaybackMode.PLAYING,
            title = if (playerId == "room-a") "Song A" else "Song B",
            artist = if (playerId == "room-a") "Artist A" else "Artist B",
            album = null,
            positionSeconds = 0.0,
            durationSeconds = 120.0,
            canSeek = true
        )

        override suspend fun getSyncGroupMembers(
            connection: LmsServerConnection,
            playerId: String
        ) = syncedPlayers.takeIf { playerId in it }?.toList() ?: listOf(playerId)

        override suspend fun syncPlayers(
            connection: LmsServerConnection,
            leaderPlayerId: String,
            memberPlayerId: String
        ) {
            syncCommands += leaderPlayerId to memberPlayerId
            syncedPlayers += leaderPlayerId
            syncedPlayers += memberPlayerId
        }

        override suspend fun unsyncPlayer(connection: LmsServerConnection, playerId: String) {
            syncedPlayers -= playerId
        }

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

    private class FakePlayerGroupRepository(
        initialGroups: List<SavedLmsPlayerGroup> = emptyList()
    ) : PlayerGroupRepository {
        private val groups = MutableStateFlow(initialGroups)

        override fun observeGroups(endpointKey: String): Flow<List<SavedLmsPlayerGroup>> = groups

        override suspend fun saveGroup(
            endpointKey: String,
            name: String,
            playerIds: List<String>
        ): SavedLmsPlayerGroup {
            val group = SavedLmsPlayerGroup("new-group", endpointKey, name, playerIds)
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
            if (groups.value.none { it.id == groupId && it.endpointKey == endpointKey }) return false
            groups.value = groups.value.filterNot { it.id == groupId }
            return true
        }
    }
}
