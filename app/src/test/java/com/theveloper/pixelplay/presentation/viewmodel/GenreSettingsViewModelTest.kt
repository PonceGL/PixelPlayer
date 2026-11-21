package com.theveloper.pixelplay.presentation.viewmodel

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.theveloper.pixelplay.MainCoroutineExtension
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.worker.SyncManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.Files

/**
 * DataStore's writes hop through its own real (non-virtual-time) IO dispatcher internally, so
 * a fire-and-forget viewModelScope.launch (addDelimiter, removeDelimiter, ...) racing against
 * advanceUntilIdle() is flaky: nothing here guarantees the write has actually landed by the time
 * advanceUntilIdle() returns. Every assertion on a state change following a mutating call
 * suspend-waits on the actual uiState flow (awaitState) instead, which - like a real suspend
 * call - properly chains through that real dispatcher hop rather than racing it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MainCoroutineExtension::class)
class GenreSettingsViewModelTest {

    private fun TestScope.repository(): UserPreferencesRepository {
        val tempDir = Files.createTempDirectory("genre-settings-viewmodel-test")
        return UserPreferencesRepository(
            dataStore = PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
            ),
            json = Json
        )
    }

    private fun syncManager(isSyncing: Boolean = false): SyncManager {
        val manager = mockk<SyncManager>(relaxed = true)
        every { manager.isSyncing } returns flowOf(isSyncing)
        return manager
    }

    private suspend fun GenreSettingsViewModel.awaitState(
        predicate: (GenreSettingsUiState) -> Boolean
    ): GenreSettingsUiState = uiState.first(predicate)

    @Test
    fun `initial state reflects the repository defaults`() = runTest {
        val viewModel = GenreSettingsViewModel(repository(), syncManager())

        val state = viewModel.awaitState { it.genreDelimiters.isNotEmpty() }

        assertEquals(UserPreferencesRepository.DEFAULT_GENRE_DELIMITERS, state.genreDelimiters)
        assertEquals(UserPreferencesRepository.DEFAULT_GENRE_WORD_DELIMITERS, state.wordDelimiters)
        assertFalse(state.rescanRequired)
    }

    @Test
    fun `addDelimiter accepts a new delimiter and reflects it in state`() = runTest {
        val viewModel = GenreSettingsViewModel(repository(), syncManager())
        viewModel.awaitState { it.genreDelimiters.isNotEmpty() }

        val added = viewModel.addDelimiter(";")
        val state = viewModel.awaitState { it.genreDelimiters.contains(";") }

        assertTrue(added)
        assertTrue(state.rescanRequired)
    }

    @Test
    fun `addDelimiter rejects an exact duplicate`() = runTest {
        val viewModel = GenreSettingsViewModel(repository(), syncManager())
        viewModel.awaitState { it.genreDelimiters.isNotEmpty() }
        viewModel.addDelimiter(";")
        viewModel.awaitState { it.genreDelimiters.contains(";") }

        val addedAgain = viewModel.addDelimiter(";")

        assertFalse(addedAgain)
    }

    @Test
    fun `removeDelimiter refuses to remove the last remaining delimiter`() = runTest {
        val viewModel = GenreSettingsViewModel(repository(), syncManager())
        val initial = viewModel.awaitState { it.genreDelimiters.isNotEmpty() }
        val onlyDelimiter = initial.genreDelimiters.single()

        viewModel.removeDelimiter(onlyDelimiter)
        advanceUntilIdle()

        assertEquals(listOf(onlyDelimiter), viewModel.uiState.value.genreDelimiters)
    }

    @Test
    fun `resetDelimitersToDefault restores the default after a custom change`() = runTest {
        val viewModel = GenreSettingsViewModel(repository(), syncManager())
        viewModel.awaitState { it.genreDelimiters.isNotEmpty() }
        viewModel.addDelimiter(";")
        viewModel.awaitState { it.genreDelimiters.contains(";") }

        viewModel.resetDelimitersToDefault()
        val state = viewModel.awaitState { it.genreDelimiters == UserPreferencesRepository.DEFAULT_GENRE_DELIMITERS }

        assertEquals(UserPreferencesRepository.DEFAULT_GENRE_DELIMITERS, state.genreDelimiters)
    }

    @Test
    fun `addWordDelimiter rejects a case-insensitive duplicate`() = runTest {
        val viewModel = GenreSettingsViewModel(repository(), syncManager())
        viewModel.awaitState { it.genreDelimiters.isNotEmpty() }
        viewModel.addWordDelimiter("and")
        viewModel.awaitState { it.wordDelimiters.contains("and") }

        val addedAgain = viewModel.addWordDelimiter("AND")

        assertFalse(addedAgain)
        assertEquals(1, viewModel.uiState.value.wordDelimiters.size)
    }

    @Test
    fun `removeWordDelimiter can clear the last word delimiter`() = runTest {
        val viewModel = GenreSettingsViewModel(repository(), syncManager())
        viewModel.awaitState { it.genreDelimiters.isNotEmpty() }
        viewModel.addWordDelimiter("and")
        viewModel.awaitState { it.wordDelimiters.contains("and") }

        viewModel.removeWordDelimiter("and")
        val state = viewModel.awaitState { it.wordDelimiters.isEmpty() }

        assertTrue(state.wordDelimiters.isEmpty())
    }

    @Test
    fun `rescanLibrary delegates to the sync manager`() = runTest {
        val sync = syncManager()
        val viewModel = GenreSettingsViewModel(repository(), sync)
        viewModel.awaitState { it.genreDelimiters.isNotEmpty() }

        viewModel.rescanLibrary()
        advanceUntilIdle()

        coVerify { sync.fullSync() }
    }
}
