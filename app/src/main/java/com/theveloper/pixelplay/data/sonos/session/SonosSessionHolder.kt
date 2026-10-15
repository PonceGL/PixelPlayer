package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.model.SonosDevice
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the user needs to see about a speaker session: nothing, being connected, connected, or why it failed. */
internal sealed interface SonosConnection {
    data object Idle : SonosConnection
    data class Connecting(val device: SonosDevice) : SonosConnection
    data class Active(val device: SonosDevice) : SonosConnection
    data class Failed(val device: SonosDevice, val reason: SonosFailure) : SonosConnection
}

/** The connection as the rest of the app reads it. Only the session controller writes it. */
@Singleton
internal class SonosSessionHolder @Inject constructor() {
    private val mutableState = MutableStateFlow<SonosConnection>(SonosConnection.Idle)
    val state: StateFlow<SonosConnection> = mutableState.asStateFlow()

    fun set(connection: SonosConnection) {
        mutableState.value = connection
    }
}
