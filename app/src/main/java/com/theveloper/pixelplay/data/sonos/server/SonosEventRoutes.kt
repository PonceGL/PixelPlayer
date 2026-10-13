package com.theveloper.pixelplay.data.sonos.server

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray

private val NOTIFY = HttpMethod("NOTIFY")
private const val EVENTS_PATH = "/sonos/events/{key}"

/**
 * Receives the NOTIFY requests a Sonos speaker sends for its subscriptions. [log] never receives the
 * key (it is part of the secret callback URL) or the body.
 */
internal fun Route.sonosEventRoutes(
    router: SonosEventRouter,
    maxBodyBytes: Int = 256 * 1024,
    bodyTimeoutMs: Long = 5_000,
    log: (String) -> Unit = {},
) {
    route(EVENTS_PATH) {
        handle { call.receiveNotification(router, maxBodyBytes, bodyTimeoutMs, log) }
    }
}

private suspend fun ApplicationCall.receiveNotification(
    router: SonosEventRouter,
    maxBodyBytes: Int,
    bodyTimeoutMs: Long,
    log: (String) -> Unit,
) {
    suspend fun reply(status: HttpStatusCode) {
        log("${request.local.method.value} /sonos/events/* ${status.value}")
        respond(status, status.description)
    }

    if (request.local.method != NOTIFY) return reply(HttpStatusCode.MethodNotAllowed)
    val registration = router.find(parameters["key"]) ?: return reply(HttpStatusCode.NotFound)
    if (!sameHost(request.local.remoteAddress, registration.expectedHost)) return reply(HttpStatusCode.Forbidden)

    val declared = request.headers["Content-Length"]?.toLongOrNull()
    if (declared != null && declared > maxBodyBytes) return reply(HttpStatusCode.PayloadTooLarge)

    val bytes = withTimeoutOrNull(bodyTimeoutMs) {
        receiveChannel().readRemaining(maxBodyBytes + 1L).readByteArray()
    } ?: return reply(HttpStatusCode.RequestTimeout)
    if (bytes.size > maxBodyBytes) return reply(HttpStatusCode.PayloadTooLarge)

    val notification = SonosNotification(
        sid = request.headers["SID"]?.trim()?.takeIf { it.isNotEmpty() },
        seq = request.headers["SEQ"]?.trim()?.toLongOrNull(),
        body = bytes.toString(Charsets.UTF_8),
    )
    try {
        registration.sink.onNotification(notification)
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        // A faulty consumer must not take the media server down with it. Only the class is logged.
        log("NOTIFY /sonos/events/* sink failed: ${e.javaClass.simpleName}")
        return reply(HttpStatusCode.InternalServerError)
    }
    reply(HttpStatusCode.OK)
}

private fun sameHost(remote: String, expected: String): Boolean = remote.removePrefix("::ffff:") == expected.removePrefix("::ffff:")
