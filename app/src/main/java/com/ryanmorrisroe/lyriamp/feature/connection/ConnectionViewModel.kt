package com.ryanmorrisroe.lyriamp.feature.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ryanmorrisroe.lyriamp.data.lms.LmsConnectionGateway
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayer
import com.ryanmorrisroe.lyriamp.data.lms.LmsActivePlayerStore
import com.ryanmorrisroe.lyriamp.data.lms.LmsHttpException
import com.ryanmorrisroe.lyriamp.data.lms.LmsProtocolException
import com.ryanmorrisroe.lyriamp.data.lms.LmsPlayer
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
import kotlinx.coroutines.CancellationException
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
    val selectedPlayerId: String? = null,
    val playerStatus: LmsPlayerStatus? = null,
    val playerMixerState: LmsPlayerMixerState? = null,
    val playerPlaybackOptions: LmsPlayerPlaybackOptions? = null,
    val isLoadingPlayerStatus: Boolean = false,
    val isLoadingMixerState: Boolean = false,
    val isLoadingPlaybackOptions: Boolean = false,
    val isSendingPlayerCommand: Boolean = false,
    val playerErrorMessage: String? = null,
    val playerMixerErrorMessage: String? = null,
    val playerPlaybackOptionsErrorMessage: String? = null,
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
    private val savedServerRepository: SavedServerRepository? = null
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ConnectionUiState())
    val uiState = mutableUiState.asStateFlow()

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
                repository.save(connection)
                mutableUiState.update {
                    it.copy(isSavingServer = false, savedServerMessage = "Server saved.")
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
                activePlayerStore?.clear()
                mutableUiState.update {
                    it.copy(
                        host = connection.host,
                        port = connection.port.toString(),
                        useHttps = connection.useHttps,
                        username = connection.username.orEmpty(),
                        password = connection.password.orEmpty(),
                        isLoadingSavedServer = false,
                        serverInfo = null,
                        players = emptyList(),
                        activeConnection = null,
                        selectedPlayerId = null,
                        playerStatus = null,
                        playerMixerState = null,
                        playerPlaybackOptions = null,
                        isLoadingPlayerStatus = false,
                        isLoadingMixerState = false,
                        isLoadingPlaybackOptions = false,
                        isSendingPlayerCommand = false,
                        playerErrorMessage = null,
                        playerMixerErrorMessage = null,
                        playerPlaybackOptionsErrorMessage = null,
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
                mutableUiState.update { it.copy(removingSavedServerId = null) }
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

    fun selectPlayer(playerId: String) {
        val currentState = mutableUiState.value
        val connection = currentState.activeConnection ?: return
        val player = currentState.players.firstOrNull { it.id == playerId } ?: return
        activePlayerStore?.select(LmsActivePlayer(connection, player.id, player.name))

        mutableUiState.update {
            it.copy(
                selectedPlayerId = playerId,
                playerStatus = null,
                playerMixerState = null,
                playerPlaybackOptions = null,
                isLoadingPlayerStatus = true,
                isLoadingMixerState = true,
                isLoadingPlaybackOptions = true,
                isSendingPlayerCommand = false,
                playerErrorMessage = null,
                playerMixerErrorMessage = null,
                playerPlaybackOptionsErrorMessage = null
            )
        }
        viewModelScope.launch {
            var status: LmsPlayerStatus? = null
            var mixerState: LmsPlayerMixerState? = null
            var playbackOptions: LmsPlayerPlaybackOptions? = null
            var statusFailed = false
            var mixerStateFailed = false
            var playbackOptionsFailed = false
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
            mutableUiState.update {
                if (it.selectedPlayerId == playerId && it.activeConnection == connection) {
                    it.copy(
                        isLoadingPlayerStatus = false,
                        playerStatus = status,
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
                        }
                    )
                } else {
                    it
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
                            playerStatus = if (deselectPlayer) null else it.playerStatus,
                            playerMixerState = if (deselectPlayer) null else it.playerMixerState,
                            playerPlaybackOptions = if (deselectPlayer) null else it.playerPlaybackOptions,
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
        activePlayerStore?.clear()

        val port = currentState.port.toIntOrNull()
        if (port == null) {
            mutableUiState.update {
                it.copy(
                    serverInfo = null,
                    players = emptyList(),
                    activeConnection = null,
                    selectedPlayerId = null,
                    playerStatus = null,
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
                    errorMessage = exception.message
                )
            }
            return
        }

        mutableUiState.update {
            it.copy(
                isConnecting = true,
                serverInfo = null,
                players = emptyList(),
                activeConnection = null,
                selectedPlayerId = null,
                playerStatus = null,
                playerMixerState = null,
                playerPlaybackOptions = null,
                isLoadingPlayerStatus = false,
                isLoadingMixerState = false,
                isLoadingPlaybackOptions = false,
                isSendingPlayerCommand = false,
                playerErrorMessage = null,
                playerMixerErrorMessage = null,
                playerPlaybackOptionsErrorMessage = null,
                powerUpdatingPlayerId = null,
                playerPowerErrorMessage = null,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            try {
                val serverInfo = gateway.testConnection(connection)
                val players = playerDirectory.getPlayers(connection)
                mutableUiState.update {
                    it.copy(
                        isConnecting = false,
                        serverInfo = serverInfo,
                        players = players,
                        activeConnection = connection,
                        errorMessage = null
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: LmsHttpException) {
                val message = if (exception.statusCode == 401) {
                    "Authentication failed. Check your credentials."
                } else {
                    "The server returned HTTP ${exception.statusCode}."
                }
                mutableUiState.update {
                    it.copy(
                        isConnecting = false,
                        serverInfo = null,
                        players = emptyList(),
                        activeConnection = null,
                        errorMessage = message
                    )
                }
            } catch (_: LmsProtocolException) {
                mutableUiState.update {
                    it.copy(
                        isConnecting = false,
                        serverInfo = null,
                        players = emptyList(),
                        activeConnection = null,
                        errorMessage = "This does not appear to be a compatible LMS server."
                    )
                }
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(
                        isConnecting = false,
                        serverInfo = null,
                        players = emptyList(),
                        activeConnection = null,
                        errorMessage = "Could not reach the server. Check the address and network."
                    )
                }
            }
        }
    }

    private inline fun updateInput(crossinline transform: ConnectionUiState.() -> ConnectionUiState) {
        activePlayerStore?.clear()
        mutableUiState.update {
            it.transform().copy(
                serverInfo = null,
                players = emptyList(),
                activeConnection = null,
                selectedPlayerId = null,
                playerStatus = null,
                playerMixerState = null,
                playerPlaybackOptions = null,
                isLoadingPlayerStatus = false,
                isLoadingMixerState = false,
                isLoadingPlaybackOptions = false,
                isSendingPlayerCommand = false,
                playerErrorMessage = null,
                playerMixerErrorMessage = null,
                playerPlaybackOptionsErrorMessage = null,
                powerUpdatingPlayerId = null,
                playerPowerErrorMessage = null,
                errorMessage = null,
                savedServerMessage = null,
                savedServerErrorMessage = null
            )
        }
    }

    class Factory(
        private val gateway: LmsConnectionGateway,
        private val playerDirectory: LmsPlayerDirectory,
        private val playerRemoteGateway: LmsPlayerRemoteGateway,
        private val activePlayerStore: LmsActivePlayerStore? = null,
        private val savedServerRepository: SavedServerRepository? = null
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
                savedServerRepository
            ) as T
        }
    }
}