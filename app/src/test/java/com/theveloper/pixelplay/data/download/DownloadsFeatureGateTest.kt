package com.theveloper.pixelplay.data.download

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DownloadsFeatureGateTest {

    // ─── The pure rule (PLAN.md §F1 · 4.4) ──────────────────────────────────────
    //
    // Every combination of the three inputs, so nobody can quietly turn this into a
    // `buildDefault && userChoice` without a test failing. `null` and `false` for
    // remoteKill are asserted to behave identically: only a confirmed `true` kills.

    private data class Case(
        val buildDefault: Boolean,
        val userChoice: Boolean?,
        val remoteKill: Boolean?,
        val expected: Boolean,
    )

    private val cases = listOf(
        // buildDefault = false
        Case(buildDefault = false, userChoice = null, remoteKill = null, expected = false),
        Case(buildDefault = false, userChoice = null, remoteKill = false, expected = false),
        Case(buildDefault = false, userChoice = true, remoteKill = null, expected = true),
        Case(buildDefault = false, userChoice = true, remoteKill = false, expected = true),
        Case(buildDefault = false, userChoice = false, remoteKill = null, expected = false),
        Case(buildDefault = false, userChoice = false, remoteKill = false, expected = false),
        // buildDefault = true
        Case(buildDefault = true, userChoice = null, remoteKill = null, expected = true),
        Case(buildDefault = true, userChoice = null, remoteKill = false, expected = true),
        Case(buildDefault = true, userChoice = false, remoteKill = null, expected = false),
        Case(buildDefault = true, userChoice = false, remoteKill = false, expected = false),
        Case(buildDefault = true, userChoice = true, remoteKill = null, expected = true),
        Case(buildDefault = true, userChoice = true, remoteKill = false, expected = true),
        // A successfully-read kill always wins, whatever the other two say
        Case(buildDefault = false, userChoice = null, remoteKill = true, expected = false),
        Case(buildDefault = false, userChoice = true, remoteKill = true, expected = false),
        Case(buildDefault = true, userChoice = null, remoteKill = true, expected = false),
        Case(buildDefault = true, userChoice = true, remoteKill = true, expected = false),
        // A failed remote fetch (modelled as null) never kills, even with the user on
        Case(buildDefault = false, userChoice = true, remoteKill = null, expected = true),
    )

    @Test
    fun `resolveDownloadsEnabled matches the full truth table`() {
        cases.forEach { case ->
            val actual = resolveDownloadsEnabled(
                buildDefault = case.buildDefault,
                userChoice = case.userChoice,
                remoteKill = case.remoteKill,
            )
            assertEquals(case.expected, actual, "$case")
        }
    }

    @Test
    fun `resolveDownloadsEnabled treats a null and a false remoteKill identically`() {
        listOf(true, false).forEach { buildDefault ->
            listOf(true, false, null).forEach { userChoice ->
                val withNull = resolveDownloadsEnabled(buildDefault, userChoice, remoteKill = null)
                val withFalse = resolveDownloadsEnabled(buildDefault, userChoice, remoteKill = false)
                assertEquals(
                    withNull,
                    withFalse,
                    "buildDefault=$buildDefault userChoice=$userChoice"
                )
            }
        }
    }

    @Test
    fun `a successfully read kill switch overrides every other input`() {
        listOf(true, false).forEach { buildDefault ->
            listOf(true, false, null).forEach { userChoice ->
                assertFalse(
                    resolveDownloadsEnabled(buildDefault, userChoice, remoteKill = true),
                    "buildDefault=$buildDefault userChoice=$userChoice"
                )
            }
        }
    }

    // ─── The reactive gate ───────────────────────────────────────────────────────

    private fun repositoryWithTempDataStore(scope: kotlinx.coroutines.CoroutineScope) =
        UserPreferencesRepository(
            dataStore = PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = {
                    Files.createTempDirectory("downloads-feature-gate-test")
                        .resolve("settings.preferences_pb")
                        .toFile()
                }
            ),
            json = Json
        )

    @Test
    fun `isEnabled starts at the build default before the preference is ever touched`() = runTest {
        val gate = DownloadsFeatureGate(
            preferences = repositoryWithTempDataStore(backgroundScope),
            appScope = backgroundScope,
        )

        assertEquals(BuildConfig.DOWNLOADS_ENABLED_BY_DEFAULT, gate.isEnabled.first())
    }

    @Test
    fun `isEnabled reflects the user choice as soon as the preference is written`() = runTest {
        val preferences = repositoryWithTempDataStore(backgroundScope)
        val gate = DownloadsFeatureGate(preferences = preferences, appScope = backgroundScope)

        // The user explicitly picks the opposite of whatever this build defaults to,
        // so the assertion only passes if the preference — not the default — won.
        val opposite = !BuildConfig.DOWNLOADS_ENABLED_BY_DEFAULT
        preferences.setDownloadsEnabled(opposite)

        assertEquals(opposite, gate.isEnabled.first())
    }

    @Test
    fun `a preference explicitly set to false is not the same as never touched`() = runTest {
        val preferences = repositoryWithTempDataStore(backgroundScope)

        assertEquals(null, preferences.downloadsEnabledPreferenceFlow.first())

        preferences.setDownloadsEnabled(false)

        assertEquals(false, preferences.downloadsEnabledPreferenceFlow.first())
        assertTrue(preferences.downloadsEnabledPreferenceFlow.first() != null)
    }
}
