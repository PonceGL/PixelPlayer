package com.theveloper.pixelplay.data.download

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.data.github.GitHubAnnouncementPropertiesService
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.mockk
import java.nio.file.Files
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    private fun repositoryWithTempDataStore(scope: CoroutineScope) =
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

    /** Never touches the network: stubs [GitHubAnnouncementPropertiesService.fetchProperties]. */
    private fun fakePropertiesService(result: Result<Properties> = Result.success(Properties())) =
        mockk<GitHubAnnouncementPropertiesService> {
            coEvery { fetchProperties(any(), any(), any(), any()) } returns result
        }

    @Test
    fun `isEnabled starts at the build default before the preference is ever touched`() = runTest(UnconfinedTestDispatcher()) {
        val gate = DownloadsFeatureGate(
            preferences = repositoryWithTempDataStore(backgroundScope),
            propertiesService = fakePropertiesService(),
            appScope = backgroundScope,
        )

        assertEquals(BuildConfig.DOWNLOADS_ENABLED_BY_DEFAULT, gate.isEnabled.first())
    }

    @Test
    fun `isEnabled reflects the user choice as soon as the preference is written`() = runTest(UnconfinedTestDispatcher()) {
        val preferences = repositoryWithTempDataStore(backgroundScope)
        val gate = DownloadsFeatureGate(
            preferences = preferences,
            propertiesService = fakePropertiesService(),
            appScope = backgroundScope,
        )

        // The user explicitly picks the opposite of whatever this build defaults to,
        // so the assertion only passes if the preference — not the default — won.
        val opposite = !BuildConfig.DOWNLOADS_ENABLED_BY_DEFAULT
        preferences.setDownloadsEnabled(opposite)
        assertEquals(opposite, gate.isEnabled.first())
    }

    @Test
    fun `a preference explicitly set to false is not the same as never touched`() = runTest(UnconfinedTestDispatcher()) {
        val preferences = repositoryWithTempDataStore(backgroundScope)

        assertEquals(null, preferences.downloadsEnabledPreferenceFlow.first())

        preferences.setDownloadsEnabled(false)

        assertEquals(false, preferences.downloadsEnabledPreferenceFlow.first())
        assertTrue(preferences.downloadsEnabledPreferenceFlow.first() != null)
    }

    // ─── Remote kill switch (F1.1b) ────────────────────────────────────────────────

    @Test
    fun `a successful fetch that says kill turns isEnabled off even with the user on`() = runTest(UnconfinedTestDispatcher()) {
        val preferences = repositoryWithTempDataStore(backgroundScope)
        preferences.setDownloadsEnabled(true)
        val killProperties = Properties().apply { setProperty("downloads_kill_switch", "true") }
        val gate = DownloadsFeatureGate(
            preferences = preferences,
            propertiesService = fakePropertiesService(Result.success(killProperties)),
            appScope = backgroundScope,
        )

        assertEquals(true, preferences.downloadsKillSwitchLastKnownFlow.first())
        assertFalse(gate.isEnabled.first())
    }

    @Test
    fun `a successful fetch that no longer says kill clears a previous kill`() = runTest(UnconfinedTestDispatcher()) {
        val preferences = repositoryWithTempDataStore(backgroundScope)
        preferences.setDownloadsEnabled(true)
        preferences.setDownloadsKillSwitchLastKnown(true) // the maintainer had killed it before
        val gate = DownloadsFeatureGate(
            preferences = preferences,
            propertiesService = fakePropertiesService(Result.success(Properties())), // now: no key
            appScope = backgroundScope,
        )

        assertEquals(false, preferences.downloadsKillSwitchLastKnownFlow.first())
        assertTrue(gate.isEnabled.first())
    }

    @Test
    fun `a failed fetch never overwrites a previously known kill value — sticky, not fail-open`() = runTest(UnconfinedTestDispatcher()) {
        val preferences = repositoryWithTempDataStore(backgroundScope)
        preferences.setDownloadsEnabled(true)
        preferences.setDownloadsKillSwitchLastKnown(true)
        val gate = DownloadsFeatureGate(
            preferences = preferences,
            propertiesService = fakePropertiesService(Result.failure(RuntimeException("network down"))),
            appScope = backgroundScope,
        )

        // Still killed: a transient failure must not un-kill a feature the maintainer killed.
        assertEquals(true, preferences.downloadsKillSwitchLastKnownFlow.first())
        assertFalse(gate.isEnabled.first())
    }

    @Test
    fun `a failed fetch on a device that never fetched successfully never kills`() = runTest(UnconfinedTestDispatcher()) {
        val preferences = repositoryWithTempDataStore(backgroundScope)
        preferences.setDownloadsEnabled(true)
        val gate = DownloadsFeatureGate(
            preferences = preferences,
            propertiesService = fakePropertiesService(Result.failure(RuntimeException("no network"))),
            appScope = backgroundScope,
        )

        assertEquals(null, preferences.downloadsKillSwitchLastKnownFlow.first())
        assertTrue(gate.isEnabled.first())
    }
}
