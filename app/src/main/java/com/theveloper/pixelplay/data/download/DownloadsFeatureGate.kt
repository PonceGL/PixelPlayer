package com.theveloper.pixelplay.data.download

import com.theveloper.pixelplay.BuildConfig
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

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
 * The remote kill switch is not wired yet (F1.1b, needs `P.9`): until then this always
 * evaluates with `remoteKill = null`, which per [resolveDownloadsEnabled] can never disable
 * anyone.
 */
@Singleton
class DownloadsFeatureGate @Inject constructor(
    preferences: UserPreferencesRepository,
    @AppScope appScope: CoroutineScope,
) {
    val isEnabled: StateFlow<Boolean> = preferences.downloadsEnabledPreferenceFlow
        .map { userChoice -> resolveWithCurrentDefault(userChoice) }
        .stateIn(
            scope = appScope,
            started = SharingStarted.Eagerly,
            initialValue = resolveWithCurrentDefault(userChoice = null),
        )

    private fun resolveWithCurrentDefault(userChoice: Boolean?): Boolean =
        resolveDownloadsEnabled(
            buildDefault = BuildConfig.DOWNLOADS_ENABLED_BY_DEFAULT,
            userChoice = userChoice,
            remoteKill = null,
        )
}
