package com.theveloper.pixelplay.presentation.viewmodel

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits for a Cast session to finish giving the music back to the phone: [returns] counts those returns and
 * [before] is its value when the session was asked to end. False if it did not happen within [timeoutMs].
 */
internal suspend fun awaitCastReturn(returns: StateFlow<Int>, before: Int, timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) { returns.first { it > before } } != null
