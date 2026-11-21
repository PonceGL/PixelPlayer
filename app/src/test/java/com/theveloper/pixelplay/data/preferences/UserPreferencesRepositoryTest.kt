package com.theveloper.pixelplay.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class UserPreferencesRepositoryTest {

    @Test
    fun `default artist delimiters avoid common characters inside artist names`() {
        assertEquals(listOf(";"), UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS)
    }

    @Test
    fun `artistDelimitersFlow normalizes stored legacy defaults`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setArtistDelimiters(listOf("/", ";", ",", "+", "&"))

            assertEquals(
                UserPreferencesRepository.DEFAULT_ARTIST_DELIMITERS,
                repository.artistDelimitersFlow.first()
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `default genre delimiter is a literal comma`() {
        assertEquals(listOf(","), UserPreferencesRepository.DEFAULT_GENRE_DELIMITERS)
    }

    @Test
    fun `default genre word delimiters is empty`() {
        assertEquals(emptyList<String>(), UserPreferencesRepository.DEFAULT_GENRE_WORD_DELIMITERS)
    }

    @Test
    fun `genreDelimitersFlow returns the default when nothing is stored`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            assertEquals(
                UserPreferencesRepository.DEFAULT_GENRE_DELIMITERS,
                repository.genreDelimitersFlow.first()
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `setGenreDelimiters persists custom delimiters and marks a rescan required`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            assertEquals(false, repository.genreSettingsRescanRequiredFlow.first())

            repository.setGenreDelimiters(listOf(";", "/"))

            assertEquals(listOf(";", "/"), repository.genreDelimitersFlow.first())
            assertTrue(repository.genreSettingsRescanRequiredFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `setGenreDelimiters with an empty list is a no-op`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setGenreDelimiters(emptyList())

            assertEquals(
                UserPreferencesRepository.DEFAULT_GENRE_DELIMITERS,
                repository.genreDelimitersFlow.first()
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `resetGenreDelimitersToDefault restores the default after a custom value`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setGenreDelimiters(listOf(";"))
            repository.resetGenreDelimitersToDefault()

            assertEquals(
                UserPreferencesRepository.DEFAULT_GENRE_DELIMITERS,
                repository.genreDelimitersFlow.first()
            )
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `setGenreWordDelimiters persists custom values and marks a rescan required`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setGenreWordDelimiters(listOf("and"))

            assertEquals(listOf("and"), repository.genreWordDelimitersFlow.first())
            assertTrue(repository.genreSettingsRescanRequiredFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clearGenreSettingsRescanRequired resets the flag back to false`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setGenreDelimiters(listOf(";"))
            assertTrue(repository.genreSettingsRescanRequiredFlow.first())

            repository.clearGenreSettingsRescanRequired()

            assertEquals(false, repository.genreSettingsRescanRequiredFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `clearPreferencesExceptKeys preserves initial setup completion`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setInitialSetupDone(true)
            repository.setNavBarStyle("compact")

            repository.clearPreferencesExceptKeys(emptySet())

            assertTrue(repository.initialSetupDoneFlow.first())
            assertEquals(NavBarStyle.DEFAULT, repository.navBarStyleFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `importPreferencesFromBackup clearExisting preserves initial setup completion`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setInitialSetupDone(true)
            repository.setNavBarStyle("compact")

            repository.importPreferencesFromBackup(
                entries = listOf(
                    PreferenceBackupEntry(
                        key = "nav_bar_style",
                        type = "string",
                        stringValue = "restored"
                    )
                ),
                clearExisting = true
            )

            assertTrue(repository.initialSetupDoneFlow.first())
            assertEquals("restored", repository.navBarStyleFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `navBarCornerRadiusFlow clamps values outside the supported UI range`() = runTest {
        val tempDir = Files.createTempDirectory("user-preferences-repository-test")
        try {
            val repository = UserPreferencesRepository(
                dataStore = PreferenceDataStoreFactory.create(
                    scope = backgroundScope,
                    produceFile = { tempDir.resolve("settings.preferences_pb").toFile() }
                ),
                json = Json
            )

            repository.setNavBarCornerRadius(-1)
            assertEquals(MIN_NAV_BAR_CORNER_RADIUS, repository.navBarCornerRadiusFlow.first())

            repository.setNavBarCornerRadius(999)
            assertEquals(MAX_NAV_BAR_CORNER_RADIUS, repository.navBarCornerRadiusFlow.first())

            repository.importPreferencesFromBackup(
                entries = listOf(
                    PreferenceBackupEntry(
                        key = "nav_bar_corner_radius",
                        type = "int",
                        intValue = -1
                    )
                ),
                clearExisting = false
            )
            assertEquals(MIN_NAV_BAR_CORNER_RADIUS, repository.navBarCornerRadiusFlow.first())
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
