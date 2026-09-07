package com.theveloper.pixelplay.data.download

import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.data.github.GitHubAnnouncementPropertiesService
import com.theveloper.pixelplay.data.github.booleanFlag
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val REMOTE_CONFIG_OWNER = "PixelPlayerHQ"
private const val REMOTE_CONFIG_REPO = "PixelPlayer"
private const val REMOTE_CONFIG_BRANCH = "master"
private const val REMOTE_CONFIG_PATH = "remote-config/feature-flags.properties"
private const val KILL_SWITCH_KEY = "downloads_kill_switch"

/**
 * Whether cloud downloads are enabled, given the three independent inputs that can decide
 * it. Pure and total: every combination of inputs has exactly one outcome, and none of the
 * eight rows below is derivable from the other seven — this is the function a `&&` would
 * quietly get wrong.
 *
 * - [buildDefault] is the preference's *default value*, not an AND gate. If it were a gate,
 *   a release user could never opt in, and a `personal` build could never opt out.
 * - [userChoice] is `null` when the user has never touched the switch. Once touched, their
 *   choice wins over [buildDefault] forever.
 * - [remoteKill] is `true` only when a remote kill switch was read *successfully* and says
 *   to kill. Anything else — `false`, or `null` because the fetch failed, the key was
 *   absent, or the value was unreadable — must never disable the feature. A failed read is
 *   not a signal; only a confirmed `true` is.
 *
 * | buildDefault | userChoice | remoteKill | result |
 * |---|---|---|---|
 * | false | null | null/false | off |
 * | false | true | null/false | on |
 * | false | false | null/false | off |
 * | true | null | null/false | on |
 * | true | false | null/false | off |
 * | true | true | null/false | on |
 * | any | any | true | off |
 * | false | true | null (failed fetch) | on |
 */
fun resolveDownloadsEnabled(
    buildDefault: Boolean,
    userChoice: Boolean?,
    remoteKill: Boolean?,
): Boolean = if (remoteKill == true) false else (userChoice ?: buildDefault)

/**
 * Single source of truth for "are cloud downloads enabled right now". Every consumer reads
 * [isEnabled]; nobody else calls [resolveDownloadsEnabled] directly (`GEN-ARCH-04`).
 *
 * Exposes [StateFlow], never a mutable one (`AND-CONC-06`): nothing downstream can write
 * this state, only observe it.
 *
 * Started with [SharingStarted.Eagerly] and kept alive for the process' lifetime instead of
 * `WhileSubscribed` (`AND-CONC-09`, *"cuándo se puede romper"*): the foreground service and
 * the scheduler worker need an up-to-date read with no UI ever collecting it, and the owner
 * of this flow is this `@Singleton`, not a `ViewModel` — which is exactly the exception that
 * rule carves out.
 *
 * [remoteKill] comes from [UserPreferencesRepository.downloadsKillSwitchLastKnownFlow] — the
 * last **successfully** read value, sticky across a failed refetch or a cold start with no
 * network — combined with one refresh attempt fired at construction time
 * ([remoteKillSwitchRefreshJob]), fire-and-forget (`GEN-CONC-01`: owned by the injected
 * app scope, never `GlobalScope`). The refresh never blocks app startup and never
 * blocks [isEnabled] from emitting immediately with whatever was last known (`PLAN.md` §F1 ·
 * F1.1, case borde 5): a failed or slow fetch leaves the cached value exactly as it was,
 * which is what makes the kill switch trustworthy across a flaky connection instead of
 * accidentally un-killing itself the moment the network hiccups.
 */
@Singleton
class DownloadsFeatureGate @Inject constructor(
    private val preferences: UserPreferencesRepository,
    private val propertiesService: GitHubAnnouncementPropertiesService,
    @AppScope private val appScope: CoroutineScope,
) {
    val isEnabled: StateFlow<Boolean> = combine(
        preferences.downloadsEnabledPreferenceFlow,
        preferences.downloadsKillSwitchLastKnownFlow,
    ) { userChoice, remoteKill ->
        resolveDownloadsEnabled(
            buildDefault = BuildConfig.DOWNLOADS_ENABLED_BY_DEFAULT,
            userChoice = userChoice,
            remoteKill = remoteKill,
        )
    }.stateIn(
        scope = appScope,
        started = SharingStarted.Eagerly,
        initialValue = resolveDownloadsEnabled(
            buildDefault = BuildConfig.DOWNLOADS_ENABLED_BY_DEFAULT,
            userChoice = null,
            remoteKill = null,
        ),
    )

    /**
     * The job backing the one-shot startup refresh (documented on [refreshRemoteKillSwitch]).
     * `internal` purely so a test can `.join()` it deterministically instead of guessing how
     * long a background fetch takes — nothing in production code should ever read this.
     */
    internal val remoteKillSwitchRefreshJob: Job = appScope.launch { refreshRemoteKillSwitch() }

    /**
     * One best-effort read of the remote kill switch. On success, persists whatever it says
     * — `true` or `false` — as the new last-known value, so a maintainer turning the kill
     * switch back off is reflected too, not just turning it on. On any failure (network, a
     * non-2xx/404 status, an unreadable response), does nothing: [resolveDownloadsEnabled]
     * already treats "couldn't read it" as "don't kill", and overwriting a previously known
     * `true` with a guessed `false` here would defeat the point of a *sticky* kill switch.
     */
    private suspend fun refreshRemoteKillSwitch() {
        propertiesService.fetchProperties(
            owner = REMOTE_CONFIG_OWNER,
            repo = REMOTE_CONFIG_REPO,
            branch = REMOTE_CONFIG_BRANCH,
            configPath = REMOTE_CONFIG_PATH,
        ).onSuccess { properties ->
            preferences.setDownloadsKillSwitchLastKnown(properties.booleanFlag(KILL_SWITCH_KEY))
        }
    }
}
