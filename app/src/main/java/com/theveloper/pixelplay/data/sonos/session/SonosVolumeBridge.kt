package com.theveloper.pixelplay.data.sonos.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Where the screens meet the volume of the speaker. The service owns the session; screens and the keys of the activity
 * only see this: the volume to show and the orders to give. It holds the control of the session that is on, if any.
 */
@Singleton
internal class SonosVolumeBridge @Inject constructor() {

    private val current = MutableStateFlow<SonosVolumeControl?>(null)

    /** Whether there is a speaker volume to show and to change. */
    val isAttached: StateFlow<Boolean> get() = attachedState

    private val attachedState = MutableStateFlow(false)

    /** The volume of the speaker as it has to be shown, null when there is none. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val volume: Flow<SonosVolume?> = current.flatMapLatest { it?.shown ?: flowOf(null) }

    fun attach(control: SonosVolumeControl) {
        current.value = control
        attachedState.value = true
    }

    /** Lets go of [control]; a newer one that has taken its place stays. */
    fun detach(control: SonosVolumeControl) {
        current.update { if (it === control) null else it }
        attachedState.value = current.value != null
    }

    /** False when there is no speaker volume to give the order to. */
    fun setLevel(level: Int): Boolean = current.value?.also { it.setLevel(level) } != null

    fun adjust(delta: Int): Boolean = current.value?.also { it.adjust(delta) } != null

    fun toggleMute(): Boolean = current.value?.also { it.toggleMute() } != null
}
