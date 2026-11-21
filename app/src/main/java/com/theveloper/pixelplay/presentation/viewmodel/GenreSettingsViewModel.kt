package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.worker.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GenreSettingsUiState(
    val genreDelimiters: List<String> = UserPreferencesRepository.DEFAULT_GENRE_DELIMITERS,
    val wordDelimiters: List<String> = UserPreferencesRepository.DEFAULT_GENRE_WORD_DELIMITERS,
    val rescanRequired: Boolean = false,
    val isResyncing: Boolean = false
)

@HiltViewModel
class GenreSettingsViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val syncManager: SyncManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(GenreSettingsUiState())
    val uiState: StateFlow<GenreSettingsUiState> = _uiState.asStateFlow()

    val isSyncing: StateFlow<Boolean> = syncManager.isSyncing
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    init {
        viewModelScope.launch {
            userPreferencesRepository.genreDelimitersFlow.collect { delimiters ->
                _uiState.update { it.copy(genreDelimiters = delimiters) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.genreWordDelimitersFlow.collect { delimiters ->
                _uiState.update { it.copy(wordDelimiters = delimiters) }
            }
        }

        viewModelScope.launch {
            userPreferencesRepository.genreSettingsRescanRequiredFlow.collect { required ->
                _uiState.update { it.copy(rescanRequired = required) }
            }
        }

        viewModelScope.launch {
            syncManager.isSyncing.collect { syncing ->
                _uiState.update { it.copy(isResyncing = syncing) }
            }
        }
    }

    fun addDelimiter(delimiter: String): Boolean {
        val trimmed = delimiter.trim()
        if (trimmed.isEmpty()) return false

        val current = _uiState.value.genreDelimiters
        if (current.contains(trimmed)) return false

        viewModelScope.launch {
            userPreferencesRepository.setGenreDelimiters(current + trimmed)
        }
        return true
    }

    fun removeDelimiter(delimiter: String) {
        val current = _uiState.value.genreDelimiters
        if (current.size <= 1) return // Keep at least one delimiter

        viewModelScope.launch {
            userPreferencesRepository.setGenreDelimiters(current - delimiter)
        }
    }

    fun resetDelimitersToDefault() {
        viewModelScope.launch {
            userPreferencesRepository.resetGenreDelimitersToDefault()
        }
    }

    fun addWordDelimiter(delimiter: String): Boolean {
        val trimmed = delimiter.trim()
        if (trimmed.isEmpty()) return false

        val current = _uiState.value.wordDelimiters
        if (current.any { it.equals(trimmed, ignoreCase = true) }) return false

        viewModelScope.launch {
            userPreferencesRepository.setGenreWordDelimiters(current + trimmed)
        }
        return true
    }

    fun removeWordDelimiter(delimiter: String) {
        val current = _uiState.value.wordDelimiters
        viewModelScope.launch {
            userPreferencesRepository.setGenreWordDelimiters(current - delimiter)
        }
    }

    fun resetWordDelimitersToDefault() {
        viewModelScope.launch {
            userPreferencesRepository.resetGenreWordDelimitersToDefault()
        }
    }

    fun rescanLibrary() {
        viewModelScope.launch {
            syncManager.fullSync()
        }
    }
}
