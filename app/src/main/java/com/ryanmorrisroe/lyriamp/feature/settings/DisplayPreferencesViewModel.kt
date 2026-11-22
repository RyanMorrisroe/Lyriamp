package com.ryanmorrisroe.lyriamp.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ryanmorrisroe.lyriamp.data.persistence.Appearance
import com.ryanmorrisroe.lyriamp.data.persistence.DisplayPreferences
import com.ryanmorrisroe.lyriamp.data.persistence.DisplayPreferencesRepository
import com.ryanmorrisroe.lyriamp.data.persistence.LibraryLayout
import com.ryanmorrisroe.lyriamp.data.persistence.LibraryViewType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DisplayPreferencesState(
    val preferences: DisplayPreferences = DisplayPreferences(),
    val error: String? = null
)

class DisplayPreferencesViewModel(
    private val repository: DisplayPreferencesRepository
) : ViewModel() {
    private val mutableState = MutableStateFlow(DisplayPreferencesState())
    val state = mutableState.asStateFlow()
    private var observation: Job? = null

    init {
        retry()
    }

    fun retry() {
        observation?.cancel()
        observation = viewModelScope.launch {
            try {
                repository.observe().collect { preferences ->
                    mutableState.update { it.copy(preferences = preferences, error = null) }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.update { it.copy(error = "Could not read display settings: ${exception.message}") }
            }
        }
    }

    fun setAppearance(appearance: Appearance) = save {
        repository.setAppearance(appearance)
    }

    fun setLayout(type: LibraryViewType, layout: LibraryLayout) = save {
        repository.setLayout(type, layout)
    }

    private fun save(operation: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                operation()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                mutableState.update { it.copy(error = "Could not save display settings: ${exception.message}") }
            }
        }
    }

    class Factory(private val repository: DisplayPreferencesRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DisplayPreferencesViewModel::class.java))
            @Suppress("UNCHECKED_CAST")
            return DisplayPreferencesViewModel(repository) as T
        }
    }
}
