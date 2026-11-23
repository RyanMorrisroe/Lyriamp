package com.ryanmorrisroe.lyriamp.feature.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ryanmorrisroe.lyriamp.data.lms.LmsConnectionGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayerStore
import com.ryanmorrisroe.lyriamp.data.lms.LmsHttpException
import com.ryanmorrisroe.lyriamp.data.lms.LmsLibraryItem
import com.ryanmorrisroe.lyriamp.data.lms.LmsProtocolException
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerCommand
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerMixerState
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerPlaybackOptions
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerQueuePage
import com.ryanmorrisroe.lyriamp.data.lms.LmsRepeatMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerDirectory
import com.ryanmorrisroe.lyriamp.data.lms.LmsQueueAddPosition
import com.ryanmorrisroe.lyriamp.data.lms.LmsQueueItem
import com.ryanmorrisroe.lyriamp.data.lms.LmsShuffleMode
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerRemoteGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayerStatus
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerConnection
import com.ryanmorrisroe.lyriamp.data.lms.LmsServerInfo
import com.ryanmorrisroe.lyriamp.data.lms.SavedLmsPlayerGroup
import com.ryanmorrisroe.lyriamp.data.lms.endpointKey
import com.ryanmorrisroe.lyriamp.data.persistence.PlayerGroupRepository
import com.ryanmorrisroe.lyriamp.data.persistence.SavedServerProfile
import com.ryanmorrisroe.lyriamp.data.persistence.SavedServerRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectionUiState(
    val host: String = "",
    val port: String = "9000",
    val useHttps: Boolean = false,
    val username: String = "",
    val password: String = "",
    val isConnecting: Boolean = false,
    val serverInfo: LmsServerInfo? = null,
    val players: List<LmsPlayer> = emptyList(),
    val activeConnection: LmsServerConnection? = null,
    val serverProfileId: String? = null,
    val selectedPlayerId: String? = null,
    val selectedPlayerIds: List<String> = emptyList(),
    val playerStatusesById: Map<String, LmsPlayerStatus> = emptyMap(),
    val playerStatusErrorIds: Set<String> = emptySet(),
    val playerGroups: List<SavedLmsPlayerGroup> = emptyList(),
    val groupBuilderPlayerIds: List<String> = emptyList(),
    val groupNameDraft: String = "",
    val isApplyingPlayerSelection: Boolean = false,
    val isSavingPlayerGroup: Boolean = false,
    val removingPlayerGroupId: String? = null,
    val playerGroupMessage: String? = null,
    val playerGroupErrorMessage: String? = null,
    val playerStatus: LmsPlayerStatus? = null,
    val playerMixerState: LmsPlayerMixerState? = null,
    val playerPlaybackOptions: LmsPlayerPlaybackOptions? = null,
    val playerQueue: List<LmsQueueItem> = emptyList(),
    val playerQueueOffset: Int = 0,
    val playerQueueTotalCount: Int = 0,
    val playerQueueCurrentIndex: Int? = null,
    val isLoadingPlayerStatus: Boolean = false,
    val isLoadingMixerState: Boolean = false,
    val isLoadingPlaybackOptions: Boolean = false,
    val isLoadingPlayerQueue: Boolean = false,
    val isSendingPlayerCommand: Boolean = false,
    val isMutatingPlayerQueue: Boolean = false,
    val playerErrorMessage: String? = null,
    val playerMixerErrorMessage: String? = null,
    val playerPlaybackOptionsErrorMessage: String? = null,
    val playerQueueErrorMessage: String? = null,
    val powerUpdatingPlayerId: String? = null,
    val playerPowerErrorMessage: String? = null,
    val errorMessage: String? = null,
    val savedServers: List<SavedServerProfile> = emptyList(),
    val isSavingServer: Boolean = false,
    val isLoadingSavedServer: Boolean = false,
    val removingSavedServerId: String? = null,
    val savedServerMessage: String? = null,
    val savedServerErrorMessage: String? = null
)

class ConnectionViewModel(
    private val gateway: LmsConnectionGateway,
    private val playerDirectory: LmsPlayerDirectory,
    private val playerRemoteGateway: LmsPlayerRemoteGateway,
    private val activePlayerStore: LmsActivePlayerStore? = null,
    private val savedServerRepository: SavedServerRepository? = null,
    private val playerGroupRepository: PlayerGroupRepository? = null
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ConnectionUiState())
    val uiState = mutableUiState.asStateFlow()
    private var groupObservationJob: Job? = null
    private var playerSubtitleJob: Job? = null
    private var playerSelectionJob: Job? = null

    init {
        savedServerRepository?.let { repository ->
            viewModelScope.launch {
                repository.observeServers().collect { profiles ->
                    mutableUiState.update { it.copy(savedServers = profiles) }
                }
            }
        }
    }

    fun onHostChanged(host: String) = updateInput { copy(host = host) }

    fun onPortChanged(port: String) = updateInput { copy(port = port) }

    fun onHttpsChanged(useHttps: Boolean) = updateInput { copy(useHttps = useHttps) }

    fun onUsernameChanged(username: String) = updateInput { copy(username = username) }

    fun onPasswordChanged(password: String) = updateInput { copy(password = password) }

    fun onLocalNetworkPermissionDenied() {
        mutableUiState.update {
            it.copy(errorMessage = "Local network access is required to contact your LMS server.")
        }
    }

    fun saveServer() {
        val repository = savedServerRepository ?: return
        val state = mutableUiState.value
        if (state.isSavingServer) return
        val port = state.port.toIntOrNull()
        if (port == null) {
            mutableUiState.update {
                it.copy(savedServerErrorMessage = "Enter a valid port.", savedServerMessage = null)
            }
            return
        }
        val connection = LmsServerConnection(
            host = state.host,
            port = port,
            useHttps = state.useHttps,
            username = state.username.takeIf(String::isNotBlank),
            password = state.password.takeIf(String::isNotBlank)
        )
        try {
            connection.jsonRpcUrl()
        } catch (exception: IllegalArgumentException) {
            mutableUiState.update {
                it.copy(
                    savedServerErrorMessage = exception.message,
                    savedServerMessage = null
                )
            }
            return
        }

        mutableUiState.update {
            it.copy(
                isSavingServer = true,
                savedServerMessage = null,
                savedServerErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val savedProfile = repository.save(connection)
                mutableUiState.update {
                    it.copy(
                        isSavingServer = false,
                        serverProfileId = savedProfile.id,
                        savedServerMessage = "Server saved."
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(
                        isSavingServer = false,
                        savedServerErrorMessage = "Could not save this server.",
                        savedServerMessage = null
                    )
                }
            }
        }
    }

    fun loadSavedServer(id: String) {
        val repository = savedServerRepository ?: return
        if (mutableUiState.value.isLoadingSavedServer) return
        mutableUiState.update {
            it.copy(
                isLoadingSavedServer = true,
                savedServerMessage = null,
                savedServerErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val connection = repository.getConnection(id)
                if (connection == null) {
                    mutableUiState.update {
                        it.copy(
                            isLoadingSavedServer = false,
                            savedServerErrorMessage = "Saved server could not be found."
                        )
                    }
                    return@launch
                }
                groupObservationJob?.cancel()
                playerSubtitleJob?.cancel()
                playerSelectionJob?.cancel()
                activePlayerStore?.clear()
                mutableUiState.update {
                    it.copy(
                        host = connection.host,
                        port = connection.port.toString(),
                        useHttps = connection.useHttps,
                        username = connection.username.orEmpty(),
                        password = connection.password.orEmpty(),
                        serverProfileId = id,
                        isLoadingSavedServer = false,
                        serverInfo = null,
                        players = emptyList(),
                        activeConnection = null,
                        selectedPlayerId = null,
                        selectedPlayerIds = emptyList(),
                        playerStatusesById = emptyMap(),
                        playerStatusErrorIds = emptySet(),
                        playerGroups = emptyList(),
                        groupBuilderPlayerIds = emptyList(),
                        isApplyingPlayerSelection = false,
                        isSavingPlayerGroup = false,
                        removingPlayerGroupId = null,
                        playerGroupMessage = null,
                        playerGroupErrorMessage = null,
                        playerStatus = null,
                        playerMixerState = null,
                        playerPlaybackOptions = null,
                        playerQueue = emptyList(),
                        playerQueueOffset = 0,
                        playerQueueTotalCount = 0,
                        playerQueueCurrentIndex = null,
                        isLoadingPlayerStatus = false,
                        isLoadingMixerState = false,
                        isLoadingPlaybackOptions = false,
                        isLoadingPlayerQueue = false,
                        isSendingPlayerCommand = false,
                        isMutatingPlayerQueue = false,
                        playerErrorMessage = null,
                        playerMixerErrorMessage = null,
                        playerPlaybackOptionsErrorMessage = null,
                        playerQueueErrorMessage = null,
                        powerUpdatingPlayerId = null,
                        playerPowerErrorMessage = null,
                        errorMessage = null,
                        savedServerMessage = null,
                        savedServerErrorMessage = null
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(
                        isLoadingSavedServer = false,
                        savedServerErrorMessage = "Could not load this server's saved credentials."
                    )
                }
            }
        }
    }

    fun removeSavedServer(id: String) {
        val repository = savedServerRepository ?: return
        if (mutableUiState.value.removingSavedServerId != null) return
        mutableUiState.update {
            it.copy(
                removingSavedServerId = id,
                savedServerMessage = null,
                savedServerErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                repository.remove(id)
                mutableUiState.update {
                    it.copy(
                        removingSavedServerId = null,
                        serverProfileId = it.serverProfileId?.takeUnless { profileId -> profileId == id }
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(
                        removingSavedServerId = null,
                        savedServerErrorMessage = "Could not remove this server."
                    )
                }
            }
        }
    }

    fun selectPlayer(playerId: String, onApplied: (() -> Unit)? = null) {
        applyPlayerSelection(listOf(playerId), onApplied)
    }

    fun restorePlayerSelection(
        playerId: String,
        playerIds: List<String>,
        onApplied: () -> Unit,
        onFailure: () -> Unit
    ) {
        val state = mutableUiState.value
        val connection = state.activeConnection ?: return
        if (playerIds.isNotEmpty()) {
            if (playerId !in playerIds) {
                mutableUiState.update {
                    it.copy(playerGroupErrorMessage = "The saved player selection is invalid.")
                }
                onFailure()
                return
            }
            applyPlayerSelection(
                playerIds,
                onApplied = onApplied,
                onFailure = onFailure,
                preferredLeaderId = playerId
            )
            return
        }
        playerSelectionJob = viewModelScope.launch {
            try {
                val liveGroup = playerRemoteGateway.getSyncGroupMembers(connection, playerId)
                if (mutableUiState.value.activeConnection != connection) return@launch
                applyPlayerSelection(
                    liveGroup.ifEmpty { listOf(playerId) },
                    onApplied = onApplied,
                    onFailure = onFailure,
                    preferredLeaderId = playerId
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableUiState.update {
                    if (it.activeConnection == connection) {
                        it.copy(playerGroupErrorMessage = "Could not restore the current player group.")
                    } else {
                        it
                    }
                }
                if (mutableUiState.value.activeConnection == connection) onFailure()
            }
        }
    }

    fun selectPlayerGroup(groupId: String, onApplied: (() -> Unit)? = null) {
        val group = mutableUiState.value.playerGroups.firstOrNull { it.id == groupId }
        val connection = mutableUiState.value.activeConnection
        if (group == null || connection == null || group.endpointKey != connection.endpointKey()) {
            mutableUiState.update {
                it.copy(playerGroupErrorMessage = "This saved player group is no longer available.")
            }
            return
        }
        applyPlayerSelection(group.playerIds, onApplied)
    }

    fun addPlayerToCurrentSelection(playerId: String) {
        val state = mutableUiState.value
        if (state.selectedPlayerIds.contains(playerId)) return
        applyPlayerSelection(state.selectedPlayerIds + playerId)
    }

    fun removePlayerFromCurrentSelection(playerId: String) {
        val state = mutableUiState.value
        if (playerId !in state.selectedPlayerIds) return
        applyPlayerSelection(state.selectedPlayerIds - playerId)
    }

    fun beginGroupBuilder(group: SavedLmsPlayerGroup? = null) {
        mutableUiState.update {
            it.copy(
                groupBuilderPlayerIds = group?.playerIds ?: it.selectedPlayerIds,
                groupNameDraft = group?.name ?: "Room group",
                playerGroupErrorMessage = null,
                playerGroupMessage = null
            )
        }
    }

    fun setGroupBuilderPlayerSelected(playerId: String, selected: Boolean) {
        val state = mutableUiState.value
        if (state.isApplyingPlayerSelection) return
        val player = state.players.firstOrNull { it.id == playerId } ?: return
        if (selected && !player.connected) return
        val ids = state.groupBuilderPlayerIds
        if (selected == (player.id in ids)) return
        mutableUiState.update {
            it.copy(
                groupBuilderPlayerIds = if (selected) ids + player.id else ids - player.id,
                playerGroupErrorMessage = null
            )
        }
    }

    fun setGroupNameDraft(name: String) {
        mutableUiState.update { it.copy(groupNameDraft = name.take(MAX_GROUP_NAME_LENGTH)) }
    }

    fun applyGroupBuilder(onApplied: (() -> Unit)? = null) {
        applyPlayerSelection(mutableUiState.value.groupBuilderPlayerIds, onApplied)
    }

    fun savePlayerGroup(
        name: String,
        playerIds: List<String>? = null,
        groupId: String? = null
    ) {
        val repository = playerGroupRepository ?: run {
            mutableUiState.update {
                it.copy(playerGroupErrorMessage = "Saved player groups are not available.")
            }
            return
        }
        val state = mutableUiState.value
        val connection = state.activeConnection ?: return
        val ids = (playerIds ?: state.groupBuilderPlayerIds).distinct()
        if (state.isSavingPlayerGroup) return
        if (name.isBlank()) {
            mutableUiState.update {
                it.copy(playerGroupErrorMessage = "Enter a name for this player group.")
            }
            return
        }
        if (ids.size < 2) {
            mutableUiState.update {
                it.copy(playerGroupErrorMessage = "Select at least two connected players to save a group.")
            }
            return
        }
        if (ids.any { id -> state.players.none { it.id == id && it.connected } }) {
            mutableUiState.update {
                it.copy(playerGroupErrorMessage = "Only connected players can be saved in a group.")
            }
            return
        }
        mutableUiState.update {
            it.copy(
                isSavingPlayerGroup = true,
                playerGroupMessage = null,
                playerGroupErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val endpointKey = connection.endpointKey()
                val saved = if (groupId == null) {
                    repository.saveGroup(endpointKey, name, ids)
                    true
                } else {
                    repository.updateGroup(endpointKey, groupId, name, ids)
                }
                if (!saved) {
                    mutableUiState.update {
                        if (it.activeConnection == connection) {
                            it.copy(
                                isSavingPlayerGroup = false,
                                playerGroupErrorMessage = "This saved player group no longer exists."
                            )
                        } else {
                            it
                        }
                    }
                    return@launch
                }
                mutableUiState.update {
                    if (it.activeConnection == connection) {
                        it.copy(
                            isSavingPlayerGroup = false,
                            playerGroupMessage = if (groupId == null) {
                                "Player group saved."
                            } else {
                                "Player group updated."
                            },
                            playerGroupErrorMessage = null
                        )
                    } else {
                        it
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableUiState.update {
                    if (it.activeConnection == connection) {
                        it.copy(
                            isSavingPlayerGroup = false,
                            playerGroupErrorMessage = "Could not save this player group."
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun removePlayerGroup(groupId: String) {
        val repository = playerGroupRepository ?: run {
            mutableUiState.update {
                it.copy(playerGroupErrorMessage = "Saved player groups are not available.")
            }
            return
        }
        val connection = mutableUiState.value.activeConnection ?: return
        if (mutableUiState.value.removingPlayerGroupId != null) return
        mutableUiState.update {
            it.copy(
                removingPlayerGroupId = groupId,
                playerGroupMessage = null,
                playerGroupErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val removed = repository.removeGroup(connection.endpointKey(), groupId)
                mutableUiState.update {
                    if (it.activeConnection == connection) {
                        it.copy(
                            removingPlayerGroupId = null,
                            playerGroupMessage = if (removed) "Player group deleted." else null,
                            playerGroupErrorMessage = if (removed) null else "This saved player group no longer exists."
                        )
                    } else {
                        it
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableUiState.update {
                    if (it.activeConnection == connection) {
                        it.copy(
                            removingPlayerGroupId = null,
                            playerGroupErrorMessage = "Could not delete this player group."
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun applyPlayerSelection(
        requestedPlayerIds: List<String>,
        onApplied: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null,
        preferredLeaderId: String? = null
    ) {
        val state = mutableUiState.value
        val connection = state.activeConnection ?: return
        if (state.isApplyingPlayerSelection) return
        val playerIds = requestedPlayerIds.distinct()
        if (playerIds.any { id -> state.players.none { it.id == id && it.connected } }) {
            mutableUiState.update {
                it.copy(
                    playerGroupErrorMessage = "Only connected players can be added to the selection."
                )
            }
            onFailure?.invoke()
            return
        }
        val leaderId = playerIds.firstOrNull { it == preferredLeaderId }
            ?: playerIds.firstOrNull { it == state.selectedPlayerId }
            ?: playerIds.firstOrNull()
        val leader = state.players.firstOrNull { it.id == leaderId }
        mutableUiState.update {
            it.copy(
                isApplyingPlayerSelection = true,
                playerGroupMessage = null,
                playerGroupErrorMessage = null
            )
        }
        playerSelectionJob = viewModelScope.launch {
            var selectionApplied = false
            try {
                reconcilePlayerSelection(connection, state.selectedPlayerIds, playerIds, leaderId)
                if (mutableUiState.value.activeConnection != connection) return@launch
                if (leader == null) {
                    activePlayerStore?.clear()
                    mutableUiState.update {
                        it.copy(
                            selectedPlayerId = null,
                            selectedPlayerIds = emptyList(),
                            isApplyingPlayerSelection = false,
                            isSavingPlayerGroup = false,
                            removingPlayerGroupId = null,
                            playerGroupMessage = null,
                            playerGroupErrorMessage = null,
                            playerStatus = null,
                            playerMixerState = null,
                            playerPlaybackOptions = null,
                            playerQueue = emptyList(),
                            playerQueueOffset = 0,
                            playerQueueTotalCount = 0,
                            playerQueueCurrentIndex = null,
                            isLoadingPlayerStatus = false,
                            isLoadingMixerState = false,
                            isLoadingPlaybackOptions = false,
                            isLoadingPlayerQueue = false
                        )
                    }
                } else {
                    activePlayerStore?.select(
                        LmsActivePlayer(connection, leader.id, leader.name, playerIds)
                    )
                    mutableUiState.update {
                        it.copy(
                            selectedPlayerId = leader.id,
                            selectedPlayerIds = playerIds,
                            isApplyingPlayerSelection = false
                        )
                    }
                    loadSelectedPlayerDetails(connection, leader.id)
                }
                selectionApplied = true
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableUiState.update {
                    if (it.activeConnection == connection) {
                        it.copy(
                            isApplyingPlayerSelection = false,
                            playerGroupErrorMessage =
                                "Could not apply this selection. LMS may have partially changed its sync groups; try again or verify them on the server."
                        )
                    } else {
                        it
                    }
                }
            }
            if (selectionApplied) onApplied?.invoke()
            else if (mutableUiState.value.activeConnection == connection) onFailure?.invoke()
        }
    }

    private suspend fun reconcilePlayerSelection(
        connection: LmsServerConnection,
        previousPlayerIds: List<String>,
        selectedPlayerIds: List<String>,
        leaderPlayerId: String?
    ) {
        val observedGroups = (previousPlayerIds + selectedPlayerIds).distinct().associateWith { playerId ->
            playerRemoteGateway.getSyncGroupMembers(connection, playerId)
                .filter(String::isNotBlank)
                .toSet() + playerId
        }
        val selectedGroup = selectedPlayerIds.toSet()
        val isAlreadySelected = selectedPlayerIds.isNotEmpty() &&
            selectedPlayerIds.all { observedGroups[it] == selectedGroup }
        val groupsToUnsync = observedGroups.values
            .filter { group -> group.size > 1 && group.any { it !in selectedGroup } }
            .distinct()
        groupsToUnsync.forEach { group ->
            group.forEach { playerId -> playerRemoteGateway.unsyncPlayer(connection, playerId) }
        }
        if (selectedPlayerIds.size > 1 && !isAlreadySelected) {
            val leaderId = requireNotNull(leaderPlayerId)
            val existingLeaderGroup = observedGroups[leaderId].orEmpty()
                .takeIf { group -> group.all { it in selectedGroup } }
                .orEmpty()
            selectedPlayerIds.filterNot { it == leaderId || it in existingLeaderGroup }.forEach { memberId ->
                playerRemoteGateway.syncPlayers(connection, leaderId, memberId)
            }
        }
        if (selectedPlayerIds.isNotEmpty()) {
            val leaderId = requireNotNull(leaderPlayerId)
            val actualGroup = playerRemoteGateway.getSyncGroupMembers(connection, leaderId).toSet() + leaderId
            if (actualGroup != selectedGroup) {
                throw LmsProtocolException("LMS did not apply the requested player group.")
            }
        }
    }

    private fun loadSelectedPlayerDetails(connection: LmsServerConnection, playerId: String) {
        mutableUiState.update {
            it.copy(
                playerStatus = null,
                playerMixerState = null,
                playerPlaybackOptions = null,
                playerQueue = emptyList(),
                playerQueueOffset = 0,
                playerQueueTotalCount = 0,
                playerQueueCurrentIndex = null,
                isLoadingPlayerStatus = true,
                isLoadingMixerState = true,
                isLoadingPlaybackOptions = true,
                isLoadingPlayerQueue = true,
                isSendingPlayerCommand = false,
                isMutatingPlayerQueue = false,
                playerErrorMessage = null,
                playerMixerErrorMessage = null,
                playerPlaybackOptionsErrorMessage = null,
                playerQueueErrorMessage = null
            )
        }
        viewModelScope.launch {
            var status: LmsPlayerStatus? = null
            var mixerState: LmsPlayerMixerState? = null
            var playbackOptions: LmsPlayerPlaybackOptions? = null
            var queuePage: LmsPlayerQueuePage? = null
            var statusFailed = false
            var mixerStateFailed = false
            var playbackOptionsFailed = false
            var queueFailed = false
            try {
                status = playerRemoteGateway.getPlayerStatus(connection, playerId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                statusFailed = true
            }
            try {
                mixerState = playerRemoteGateway.getPlayerMixerState(connection, playerId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mixerStateFailed = true
            }
            try {
                playbackOptions = playerRemoteGateway.getPlayerPlaybackOptions(connection, playerId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                playbackOptionsFailed = true
            }
            try {
                queuePage = playerRemoteGateway.getPlayerQueue(
                    connection,
                    playerId,
                    offset = status?.playlistIndex?.coerceAtLeast(0) ?: 0,
                    limit = QUEUE_PAGE_SIZE
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                queueFailed = true
            }
            mutableUiState.update {
                if (it.selectedPlayerId == playerId && it.activeConnection == connection) {
                    it.copy(
                        isLoadingPlayerStatus = false,
                        playerStatus = status,
                        playerStatusesById = if (status == null) it.playerStatusesById else {
                            it.playerStatusesById + (playerId to status)
                        },
                        playerStatusErrorIds = if (statusFailed) {
                            it.playerStatusErrorIds + playerId
                        } else {
                            it.playerStatusErrorIds - playerId
                        },
                        playerErrorMessage = if (statusFailed) {
                            "Could not read this player's status."
                        } else {
                            null
                        },
                        isLoadingMixerState = false,
                        playerMixerState = mixerState,
                        playerMixerErrorMessage = if (mixerStateFailed) {
                            "Could not read this player's volume."
                        } else {
                            null
                        },
                        isLoadingPlaybackOptions = false,
                        playerPlaybackOptions = playbackOptions,
                        playerPlaybackOptionsErrorMessage = if (playbackOptionsFailed) {
                            "Could not read this player's shuffle and repeat settings."
                        } else {
                            null
                        },
                        isLoadingPlayerQueue = false,
                        playerQueue = queuePage?.items.orEmpty(),
                        playerQueueOffset = queuePage?.offset ?: 0,
                        playerQueueTotalCount = queuePage?.totalCount ?: 0,
                        playerQueueCurrentIndex = queuePage?.currentIndex,
                        playerQueueErrorMessage = if (queueFailed) {
                            "Could not load this player's queue."
                        } else {
                            null
                        }
                    )
                } else {
                    it
                }
            }
        }
    }

    fun refreshSelectedPlayer() {
        mutableUiState.value.selectedPlayerId?.let(::selectPlayer)
    }

    suspend fun refreshPlayerStatus() {
        val currentState = mutableUiState.value
        val connection = currentState.activeConnection ?: return
        val playerId = currentState.selectedPlayerId ?: return
        if (currentState.isSendingPlayerCommand || currentState.isLoadingPlayerStatus) return

        try {
            val status = playerRemoteGateway.getPlayerStatus(connection, playerId)
            val queueChanged = currentState.playerStatus?.let { previous ->
                previous.playlistIndex != status.playlistIndex ||
                    previous.trackId != status.trackId
            } == true
            mutableUiState.update {
                if (it.selectedPlayerId == playerId && it.activeConnection == connection) {
                    it.copy(
                        playerStatus = status,
                        playerStatusesById = it.playerStatusesById + (playerId to status),
                        playerStatusErrorIds = it.playerStatusErrorIds - playerId,
                        playerErrorMessage = null
                    )
                } else {
                    it
                }
            }
            if (queueChanged) refreshPlayerQueue()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            mutableUiState.update {
                if (it.selectedPlayerId == playerId && it.activeConnection == connection) {
                    it.copy(
                        playerStatusErrorIds = it.playerStatusErrorIds + playerId,
                        playerErrorMessage = "Could not read this player's status."
                    )
                } else {
                    it
                }
            }
        }
    }

    suspend fun refreshPlayerQueue() {
        val state = mutableUiState.value
        val connection = state.activeConnection ?: return
        val playerId = state.selectedPlayerId ?: return
        if (state.isLoadingPlayerQueue || state.isMutatingPlayerQueue) return
        val offset = state.playerStatus?.playlistIndex?.coerceAtLeast(0)
            ?: state.playerQueueCurrentIndex?.coerceAtLeast(0)
            ?: 0
        loadPlayerQueuePage(connection, playerId, offset, append = false)
    }

    fun loadMorePlayerQueue() {
        val state = mutableUiState.value
        val connection = state.activeConnection ?: return
        val playerId = state.selectedPlayerId ?: return
        if (state.isLoadingPlayerQueue || state.isMutatingPlayerQueue) return
        val offset = state.playerQueueOffset + state.playerQueue.size
        if (offset >= state.playerQueueTotalCount) return
        viewModelScope.launch {
            loadPlayerQueuePage(connection, playerId, offset, append = true)
        }
    }

    fun addPlayerQueueItem(item: LmsLibraryItem, position: LmsQueueAddPosition) {
        if (item.isContainer) return
        mutatePlayerQueue { connection, playerId ->
            playerRemoteGateway.addQueueItem(connection, playerId, item, position)
        }
    }

    fun removePlayerQueueItem(index: Int) {
        val state = mutableUiState.value
        val currentIndex = state.playerQueueCurrentIndex ?: state.playerStatus?.playlistIndex ?: -1
        if (index <= currentIndex || state.playerQueue.none { it.index == index }) return
        mutatePlayerQueue { connection, playerId ->
            playerRemoteGateway.removeQueueItem(connection, playerId, index)
        }
    }

    fun movePlayerQueueItem(fromIndex: Int, toIndex: Int) {
        val state = mutableUiState.value
        val currentIndex = state.playerQueueCurrentIndex ?: state.playerStatus?.playlistIndex ?: -1
        if (fromIndex <= currentIndex || toIndex <= currentIndex) return
        if (state.playerQueue.none { it.index == fromIndex } ||
            state.playerQueue.none { it.index == toIndex }
        ) return
        mutatePlayerQueue { connection, playerId ->
            playerRemoteGateway.moveQueueItem(connection, playerId, fromIndex, toIndex)
        }
    }

    private suspend fun loadPlayerQueuePage(
        connection: LmsServerConnection,
        playerId: String,
        offset: Int,
        append: Boolean
    ) {
        mutableUiState.update {
            if (it.activeConnection == connection && it.selectedPlayerId == playerId &&
                !it.isMutatingPlayerQueue
            ) {
                it.copy(isLoadingPlayerQueue = true, playerQueueErrorMessage = null)
            } else {
                it
            }
        }
        try {
            var page = playerRemoteGateway.getPlayerQueue(
                connection,
                playerId,
                offset,
                QUEUE_PAGE_SIZE
            )
            if (page.offset != offset) {
                throw LmsProtocolException("LMS returned a queue page with an unexpected offset.")
            }
            var effectiveAppend = append
            if (append && page.totalCount < offset) {
                page = playerRemoteGateway.getPlayerQueue(
                    connection,
                    playerId,
                    offset = 0,
                    limit = QUEUE_PAGE_SIZE
                )
                effectiveAppend = false
            }
            mutableUiState.update {
                if (it.activeConnection == connection && it.selectedPlayerId == playerId) {
                    if (effectiveAppend && it.playerQueue.size != offset) {
                        it.copy(
                            isLoadingPlayerQueue = false,
                            playerQueueErrorMessage = "The queue changed while loading. Refresh it to continue."
                        )
                    } else {
                        it.copy(
                            isLoadingPlayerQueue = false,
                            playerQueueOffset = if (effectiveAppend) it.playerQueueOffset else page.offset,
                            playerQueue = if (effectiveAppend) it.playerQueue + page.items else page.items,
                            playerQueueTotalCount = page.totalCount,
                            playerQueueCurrentIndex = page.currentIndex,
                            playerQueueErrorMessage = null
                        )
                    }
                } else {
                    it
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            mutableUiState.update {
                if (it.activeConnection == connection && it.selectedPlayerId == playerId) {
                    it.copy(
                        isLoadingPlayerQueue = false,
                        playerQueueErrorMessage = "Could not load this player's queue."
                    )
                } else {
                    it
                }
            }
        } finally {
            mutableUiState.update {
                if (it.activeConnection == connection && it.selectedPlayerId == playerId) {
                    it.copy(isLoadingPlayerQueue = false)
                } else {
                    it
                }
            }
        }
    }

    private fun mutatePlayerQueue(
        mutation: suspend (LmsServerConnection, String) -> Unit
    ) {
        val state = mutableUiState.value
        val connection = state.activeConnection
        val playerId = state.selectedPlayerId
        if (connection == null || playerId == null) {
            mutableUiState.update {
                it.copy(playerQueueErrorMessage = "Select a player before changing the queue.")
            }
            return
        }
        if (state.isMutatingPlayerQueue || state.isLoadingPlayerQueue) return
        var started = false
        mutableUiState.update {
            if (it.activeConnection == connection && it.selectedPlayerId == playerId &&
                !it.isMutatingPlayerQueue && !it.isLoadingPlayerQueue
            ) {
                started = true
                it.copy(
                    isMutatingPlayerQueue = true,
                    isLoadingPlayerQueue = true,
                    playerQueueErrorMessage = null
                )
            } else {
                it
            }
        }
        if (!started) return
        val refreshOffset = state.playerStatus?.playlistIndex?.coerceAtLeast(0)
            ?: state.playerQueueCurrentIndex?.coerceAtLeast(0)
            ?: 0
        viewModelScope.launch {
            var mutationCompleted = false
            try {
                mutation(connection, playerId)
                mutationCompleted = true
                val page = playerRemoteGateway.getPlayerQueue(
                    connection,
                    playerId,
                    offset = refreshOffset,
                    limit = QUEUE_PAGE_SIZE
                )
                mutableUiState.update {
                    if (it.activeConnection == connection && it.selectedPlayerId == playerId) {
                        it.copy(
                            playerQueueOffset = page.offset,
                            playerQueue = page.items,
                            playerQueueTotalCount = page.totalCount,
                            playerQueueCurrentIndex = page.currentIndex,
                            playerQueueErrorMessage = null
                        )
                    } else {
                        it
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableUiState.update {
                    if (it.activeConnection == connection && it.selectedPlayerId == playerId) {
                        it.copy(
                            playerQueueErrorMessage = if (mutationCompleted) {
                                "The queue changed, but could not be refreshed."
                            } else {
                                "Could not update this player's queue."
                            }
                        )
                    } else {
                        it
                    }
                }
            } finally {
                mutableUiState.update {
                    if (it.activeConnection == connection && it.selectedPlayerId == playerId) {
                        it.copy(
                            isLoadingPlayerQueue = false,
                            isMutatingPlayerQueue = false
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun sendPlayerCommand(command: LmsPlayerCommand) {
        val currentState = mutableUiState.value
        val connection = currentState.activeConnection ?: return
        val playerId = currentState.selectedPlayerId ?: return
        if (currentState.isSendingPlayerCommand) return
        val updatesMixer = command is LmsPlayerCommand.SetVolume ||
            command is LmsPlayerCommand.SetMuted
        val updatesPlaybackOptions = command is LmsPlayerCommand.SetShuffleMode ||
            command is LmsPlayerCommand.SetRepeatMode
        val updatesQueue = command !is LmsPlayerCommand.SetVolume &&
            command !is LmsPlayerCommand.SetMuted &&
            command !is LmsPlayerCommand.SetPower

        mutableUiState.update {
            it.copy(
                isSendingPlayerCommand = true,
                isLoadingMixerState = updatesMixer,
                isLoadingPlaybackOptions = updatesPlaybackOptions,
                playerErrorMessage = null,
                playerMixerErrorMessage = null,
                playerPlaybackOptionsErrorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                playerRemoteGateway.sendCommand(connection, playerId, command)
                val status = playerRemoteGateway.getPlayerStatus(connection, playerId)
                val mixerState = if (updatesMixer) {
                    playerRemoteGateway.getPlayerMixerState(connection, playerId)
                } else {
                    null
                }
                val playbackOptions = if (updatesPlaybackOptions) {
                    playerRemoteGateway.getPlayerPlaybackOptions(connection, playerId)
                } else {
                    null
                }
                mutableUiState.update {
                    if (it.selectedPlayerId == playerId && it.activeConnection == connection) {
                        it.copy(
                            isSendingPlayerCommand = false,
                            playerStatus = status,
                            playerStatusesById = it.playerStatusesById + (playerId to status),
                            playerStatusErrorIds = it.playerStatusErrorIds - playerId,
                            playerMixerState = mixerState ?: it.playerMixerState,
                            isLoadingMixerState = false,
                            playerPlaybackOptions = playbackOptions ?: it.playerPlaybackOptions,
                            isLoadingPlaybackOptions = false,
                            playerErrorMessage = null
                        )
                    } else {
                        it
                    }
                }
                if (updatesQueue) refreshPlayerQueue()
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableUiState.update {
                    if (it.selectedPlayerId == playerId && it.activeConnection == connection) {
                        it.copy(
                            isSendingPlayerCommand = false,
                            isLoadingMixerState = false,
                            isLoadingPlaybackOptions = false,
                            playerErrorMessage = "The player command could not be completed.",
                            playerMixerErrorMessage = if (updatesMixer) {
                                "Could not refresh this player's volume."
                            } else {
                                it.playerMixerErrorMessage
                            },
                            playerPlaybackOptionsErrorMessage = if (updatesPlaybackOptions) {
                                "Could not refresh this player's shuffle and repeat settings."
                            } else {
                                it.playerPlaybackOptionsErrorMessage
                            }
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun setPlayerPower(playerId: String, poweredOn: Boolean) {
        val currentState = mutableUiState.value
        val connection = currentState.activeConnection ?: return
        val player = currentState.players.firstOrNull { it.id == playerId } ?: return
        if (player.power == poweredOn || currentState.powerUpdatingPlayerId != null) return
        if (!poweredOn && !player.connected) return

        mutableUiState.update {
            it.copy(powerUpdatingPlayerId = playerId, playerPowerErrorMessage = null)
        }
        viewModelScope.launch {
            try {
                playerRemoteGateway.sendCommand(
                    connection,
                    playerId,
                    LmsPlayerCommand.SetPower(poweredOn)
                )
                val players = playerDirectory.getPlayers(connection)
                val deselectPlayer = !poweredOn && currentState.selectedPlayerId == playerId
                if (deselectPlayer) activePlayerStore?.clear()
                mutableUiState.update {
                    if (it.activeConnection == connection) {
                        it.copy(
                            players = players,
                            powerUpdatingPlayerId = null,
                            selectedPlayerId = if (deselectPlayer) null else it.selectedPlayerId,
                            selectedPlayerIds = if (deselectPlayer) emptyList() else it.selectedPlayerIds,
                            playerStatus = if (deselectPlayer) null else it.playerStatus,
                            playerMixerState = if (deselectPlayer) null else it.playerMixerState,
                            playerPlaybackOptions = if (deselectPlayer) null else it.playerPlaybackOptions,
                            playerQueue = if (deselectPlayer) emptyList() else it.playerQueue,
                            playerQueueOffset = if (deselectPlayer) 0 else it.playerQueueOffset,
                            playerQueueTotalCount = if (deselectPlayer) 0 else it.playerQueueTotalCount,
                            playerQueueCurrentIndex = if (deselectPlayer) null else it.playerQueueCurrentIndex,
                            isLoadingPlayerQueue = if (deselectPlayer) false else it.isLoadingPlayerQueue,
                            isMutatingPlayerQueue = if (deselectPlayer) false else it.isMutatingPlayerQueue,
                            playerQueueErrorMessage = if (deselectPlayer) null else it.playerQueueErrorMessage,
                            playerPowerErrorMessage = null
                        )
                    } else {
                        it
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableUiState.update {
                    if (it.activeConnection == connection) {
                        it.copy(
                            powerUpdatingPlayerId = null,
                            playerPowerErrorMessage = "Could not change this player's power state."
                        )
                    } else {
                        it
                    }
                }
            }
        }
    }

    fun testConnection() {
        val currentState = mutableUiState.value
        if (currentState.isConnecting) return

        val port = currentState.port.toIntOrNull()
        if (port == null) {
            mutableUiState.update {
                it.copy(
                    serverInfo = null,
                    players = emptyList(),
                    activeConnection = null,
                    selectedPlayerId = null,
                    playerStatus = null,
                    playerQueue = emptyList(),
                    playerQueueOffset = 0,
                    playerQueueTotalCount = 0,
                    playerQueueCurrentIndex = null,
                    isLoadingPlayerQueue = false,
                    isMutatingPlayerQueue = false,
                    playerQueueErrorMessage = null,
                    errorMessage = "Enter a valid port."
                )
            }
            return
        }

        val connection = LmsServerConnection(
            host = currentState.host,
            port = port,
            useHttps = currentState.useHttps,
            username = currentState.username.takeIf(String::isNotBlank),
            password = currentState.password.takeIf(String::isNotBlank)
        )
        try {
            connection.jsonRpcUrl()
        } catch (exception: IllegalArgumentException) {
            mutableUiState.update {
                it.copy(
                    serverInfo = null,
                    players = emptyList(),
                    activeConnection = null,
                    selectedPlayerId = null,
                    playerStatus = null,
                    playerQueue = emptyList(),
                    playerQueueOffset = 0,
                    playerQueueTotalCount = 0,
                    playerQueueCurrentIndex = null,
                    isLoadingPlayerQueue = false,
                    isMutatingPlayerQueue = false,
                    playerQueueErrorMessage = null,
                    errorMessage = exception.message
                )
            }
            return
        }

        startConnection(connection)
    }

    suspend fun connectSavedServer(id: String): Boolean {
        val repository = savedServerRepository ?: return false
        if (mutableUiState.value.isConnecting || mutableUiState.value.isLoadingSavedServer) return false
        mutableUiState.update {
            it.copy(
                isLoadingSavedServer = true,
                savedServerMessage = null,
                savedServerErrorMessage = null
            )
        }
        val connection = try {
            repository.getConnection(id)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            mutableUiState.update {
                it.copy(
                    isLoadingSavedServer = false,
                    savedServerErrorMessage = "Could not load this server's saved credentials."
                )
            }
            return false
        }
        if (connection == null) {
            mutableUiState.update {
                it.copy(
                    isLoadingSavedServer = false,
                    savedServerErrorMessage = "Saved server could not be found."
                )
            }
            return false
        }
        mutableUiState.update {
            it.copy(
                host = connection.host,
                port = connection.port.toString(),
                useHttps = connection.useHttps,
                username = connection.username.orEmpty(),
                password = connection.password.orEmpty(),
                serverProfileId = id,
                isLoadingSavedServer = false
            )
        }
        return connectToServer(connection)
    }

    private fun startConnection(connection: LmsServerConnection) {
        prepareConnection()
        viewModelScope.launch { performConnection(connection) }
    }

    private suspend fun connectToServer(connection: LmsServerConnection): Boolean {
        prepareConnection()
        return performConnection(connection)
    }

    private fun prepareConnection() {
        groupObservationJob?.cancel()
        playerSubtitleJob?.cancel()
        playerSelectionJob?.cancel()
        activePlayerStore?.clear()
        mutableUiState.update {
            it.copy(
                isConnecting = true,
                serverInfo = null,
                players = emptyList(),
                activeConnection = null,
                serverProfileId = null,
                selectedPlayerId = null,
                selectedPlayerIds = emptyList(),
                playerStatusesById = emptyMap(),
                playerStatusErrorIds = emptySet(),
                playerGroups = emptyList(),
                groupBuilderPlayerIds = emptyList(),
                isApplyingPlayerSelection = false,
                isSavingPlayerGroup = false,
                removingPlayerGroupId = null,
                playerGroupMessage = null,
                playerGroupErrorMessage = null,
                playerStatus = null,
                playerMixerState = null,
                playerPlaybackOptions = null,
                playerQueue = emptyList(),
                playerQueueOffset = 0,
                playerQueueTotalCount = 0,
                playerQueueCurrentIndex = null,
                isLoadingPlayerStatus = false,
                isLoadingMixerState = false,
                isLoadingPlaybackOptions = false,
                isLoadingPlayerQueue = false,
                isSendingPlayerCommand = false,
                isMutatingPlayerQueue = false,
                playerErrorMessage = null,
                playerMixerErrorMessage = null,
                playerPlaybackOptionsErrorMessage = null,
                playerQueueErrorMessage = null,
                powerUpdatingPlayerId = null,
                playerPowerErrorMessage = null,
                errorMessage = null,
                savedServerErrorMessage = null
            )
        }
    }

    private suspend fun performConnection(connection: LmsServerConnection): Boolean {
        try {
            val serverInfo = gateway.testConnection(connection)
            val players = playerDirectory.getPlayers(connection)
            var profileId: String? = null
            var saveError: String? = null
            try {
                profileId = savedServerRepository?.save(connection)?.id
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                saveError = "Connected, but the server profile could not be saved."
            }
            mutableUiState.update {
                it.copy(
                    isConnecting = false,
                    serverInfo = serverInfo,
                    players = players,
                    activeConnection = connection,
                    serverProfileId = profileId,
                    savedServerErrorMessage = saveError,
                    errorMessage = null
                )
            }
            observePlayerGroups(connection)
            loadPlayerSubtitles(connection, players)
            return true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: LmsHttpException) {
            val message = if (exception.statusCode == 401) {
                "Authentication failed. Check your credentials."
            } else {
                "The server returned HTTP ${exception.statusCode}."
            }
            return failConnection(message)
        } catch (_: LmsProtocolException) {
            return failConnection("This does not appear to be a compatible LMS server.")
        } catch (_: Exception) {
            return failConnection("Could not reach the server. Check the address and network.")
        }
    }

    private fun observePlayerGroups(connection: LmsServerConnection) {
        val repository = playerGroupRepository ?: return
        groupObservationJob?.cancel()
        val endpointKey = try {
            connection.endpointKey()
        } catch (_: IllegalArgumentException) {
            mutableUiState.update {
                it.copy(playerGroupErrorMessage = "Could not identify this server for saved groups.")
            }
            return
        }
        groupObservationJob = viewModelScope.launch {
            try {
                repository.observeGroups(endpointKey).collect { groups ->
                    mutableUiState.update {
                        if (it.activeConnection == connection) it.copy(playerGroups = groups) else it
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                mutableUiState.update {
                    if (it.activeConnection == connection) {
                        it.copy(playerGroupErrorMessage = "Could not load saved player groups.")
                    } else {
                        it
                    }
                }
            }
        }
    }

    private fun loadPlayerSubtitles(connection: LmsServerConnection, players: List<LmsPlayer>) {
        playerSubtitleJob?.cancel()
        mutableUiState.update {
            it.copy(playerStatusesById = emptyMap(), playerStatusErrorIds = emptySet())
        }
        playerSubtitleJob = viewModelScope.launch {
            players.filter(LmsPlayer::connected).forEach { player ->
                try {
                    val status = playerRemoteGateway.getPlayerStatus(connection, player.id)
                    mutableUiState.update {
                        if (it.activeConnection == connection) {
                            it.copy(
                                playerStatusesById = it.playerStatusesById + (player.id to status),
                                playerStatusErrorIds = it.playerStatusErrorIds - player.id
                            )
                        } else {
                            it
                        }
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    mutableUiState.update {
                        if (it.activeConnection == connection) {
                            it.copy(playerStatusErrorIds = it.playerStatusErrorIds + player.id)
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    private fun failConnection(message: String): Boolean {
        mutableUiState.update {
            it.copy(
                isConnecting = false,
                serverInfo = null,
                players = emptyList(),
                activeConnection = null,
                serverProfileId = null,
                selectedPlayerId = null,
                selectedPlayerIds = emptyList(),
                playerStatusesById = emptyMap(),
                playerStatusErrorIds = emptySet(),
                playerGroups = emptyList(),
                groupBuilderPlayerIds = emptyList(),
                isApplyingPlayerSelection = false,
                isSavingPlayerGroup = false,
                removingPlayerGroupId = null,
                playerGroupMessage = null,
                playerGroupErrorMessage = null,
                playerStatus = null,
                playerMixerState = null,
                playerPlaybackOptions = null,
                playerQueue = emptyList(),
                playerQueueOffset = 0,
                playerQueueTotalCount = 0,
                playerQueueCurrentIndex = null,
                isLoadingPlayerStatus = false,
                isLoadingMixerState = false,
                isLoadingPlaybackOptions = false,
                isLoadingPlayerQueue = false,
                isSendingPlayerCommand = false,
                isMutatingPlayerQueue = false,
                playerQueueErrorMessage = null,
                errorMessage = message
            )
        }
        return false
    }

    private inline fun updateInput(crossinline transform: ConnectionUiState.() -> ConnectionUiState) {
        groupObservationJob?.cancel()
        playerSubtitleJob?.cancel()
        playerSelectionJob?.cancel()
        activePlayerStore?.clear()
        mutableUiState.update {
            it.transform().copy(
                serverInfo = null,
                players = emptyList(),
                activeConnection = null,
                selectedPlayerId = null,
                selectedPlayerIds = emptyList(),
                playerStatusesById = emptyMap(),
                playerStatusErrorIds = emptySet(),
                playerGroups = emptyList(),
                groupBuilderPlayerIds = emptyList(),
                isApplyingPlayerSelection = false,
                isSavingPlayerGroup = false,
                removingPlayerGroupId = null,
                playerGroupMessage = null,
                playerGroupErrorMessage = null,
                playerStatus = null,
                playerMixerState = null,
                playerPlaybackOptions = null,
                playerQueue = emptyList(),
                playerQueueOffset = 0,
                playerQueueTotalCount = 0,
                playerQueueCurrentIndex = null,
                isLoadingPlayerStatus = false,
                isLoadingMixerState = false,
                isLoadingPlaybackOptions = false,
                isLoadingPlayerQueue = false,
                isSendingPlayerCommand = false,
                isMutatingPlayerQueue = false,
                playerErrorMessage = null,
                playerMixerErrorMessage = null,
                playerPlaybackOptionsErrorMessage = null,
                playerQueueErrorMessage = null,
                powerUpdatingPlayerId = null,
                playerPowerErrorMessage = null,
                errorMessage = null,
                savedServerMessage = null,
                savedServerErrorMessage = null
            )
        }
    }

    private companion object {
        const val QUEUE_PAGE_SIZE = 100
        const val MAX_GROUP_NAME_LENGTH = 32
    }

    class Factory(
        private val gateway: LmsConnectionGateway,
        private val playerDirectory: LmsPlayerDirectory,
        private val playerRemoteGateway: LmsPlayerRemoteGateway,
        private val activePlayerStore: LmsActivePlayerStore? = null,
        private val savedServerRepository: SavedServerRepository? = null,
        private val playerGroupRepository: PlayerGroupRepository? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(ConnectionViewModel::class.java)) {
                throw IllegalArgumentException("Unsupported ViewModel: ${modelClass.name}")
            }
            return ConnectionViewModel(
                gateway,
                playerDirectory,
                playerRemoteGateway,
                activePlayerStore,
                savedServerRepository,
                playerGroupRepository
            ) as T
        }
    }
}