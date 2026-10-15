package com.theveloper.pixelplay.data.sonos.net

import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/** Suspends until the call ends; cancelling the coroutine cancels the call and frees its connection. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(Result.success(response)) { _, value, _ -> value.getOrNull()?.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resume(Result.failure(e))
        }
    })
}.getOrThrow()
