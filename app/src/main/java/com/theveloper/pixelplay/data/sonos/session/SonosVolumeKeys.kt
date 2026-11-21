package com.theveloper.pixelplay.data.sonos.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.delay

internal sealed interface VolumeKeyAction {
    /** The key stays with the phone. */
    data object Phone : VolumeKeyAction

    /** The key changes the speaker's volume by [delta] points. */
    data class Change(val delta: Int) : VolumeKeyAction
}

/**
 * What a volume key does. Only a session that is on, with a volume to change, takes it; while a speaker is still being
 * connected, or after it failed, the phone keeps its keys.
 */
internal fun volumeKeyAction(connection: SonosConnection, volumeAttached: Boolean, up: Boolean): VolumeKeyAction =
    if (connection is SonosConnection.Active && volumeAttached) {
        VolumeKeyAction.Change(if (up) SonosVolumeKeys.STEP else -SonosVolumeKeys.STEP)
    } else {
        VolumeKeyAction.Phone
    }

/** True while the indicator of the volume must show: from a key, for [holdMs], and again from the next key. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun indicatorVisible(requests: Flow<Unit>, holdMs: Long): Flow<Boolean> =
    requests
        .flatMapLatest {
            flow {
                emit(true)
                delay(holdMs)
                emit(false)
            }
        }
        .onStart { emit(false) }

/** The volume keys of the phone, while the app is in front: they go to the speaker when one is playing. */
@Singleton
internal class SonosVolumeKeys @Inject constructor(
    private val bridge: SonosVolumeBridge,
    private val session: SonosSessionHolder,
) {

    private val mutableRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** One item every time a key was taken, for the indicator. */
    val requests: SharedFlow<Unit> = mutableRequests.asSharedFlow()

    /** True when the key was taken and the phone must not do its own volume. */
    fun onKey(up: Boolean): Boolean = when (val action = volumeKeyAction(session.state.value, bridge.isAttached.value, up)) {
        VolumeKeyAction.Phone -> false
        is VolumeKeyAction.Change -> bridge.adjust(action.delta).also { if (it) mutableRequests.tryEmit(Unit) }
    }

    companion object {
        /** Points of volume (out of 100) a key press changes. */
        const val STEP = 5

        /** How long the indicator stays after the last key. */
        const val INDICATOR_MS = 1_500L
    }
}
