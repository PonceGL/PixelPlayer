package com.theveloper.pixelplay.data.sonos.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

private const val SONG_PATH = "/sonos/song/{songId}"
private const val ART_PATH = "/sonos/art/{songId}"
private const val COPY_BUFFER_BYTES = 64 * 1024
private val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

/**
 * Serves the original audio and cover of published songs to a Sonos speaker (and to the Sonos app
 * asking for covers). [log] receives method, path without query, and status only, never the token.
 */
internal fun Route.sonosMediaRoutes(
    access: SonosMediaAccess,
    source: SonosMediaSource,
    io: CoroutineDispatcher,
    log: (String) -> Unit,
) {
    get(SONG_PATH) { call.serveAudio(access, source, io, log, headOnly = false) }
    head(SONG_PATH) { call.serveAudio(access, source, io, log, headOnly = true) }
    get(ART_PATH) { call.serveArt(access, source, io, log, headOnly = false) }
    head(ART_PATH) { call.serveArt(access, source, io, log, headOnly = true) }
}

private suspend fun ApplicationCall.rejectUnlessAllowed(access: SonosMediaAccess, log: (String) -> Unit): String? {
    val songId = parameters["songId"]
    return when (access.authorize(request.queryParameters[SonosMediaAccess.TOKEN_PARAMETER], songId)) {
        AccessDecision.Allowed -> songId
        AccessDecision.InvalidToken -> {
            finish(HttpStatusCode.Unauthorized, log)
            null
        }
        AccessDecision.NotPublished -> {
            finish(HttpStatusCode.NotFound, log)
            null
        }
    }
}

private suspend fun ApplicationCall.finish(status: HttpStatusCode, log: (String) -> Unit) {
    log("${request.httpMethod.value} ${request.path()} ${status.value}")
    respond(status, status.description)
}

private suspend fun ApplicationCall.serveAudio(
    access: SonosMediaAccess,
    source: SonosMediaSource,
    io: CoroutineDispatcher,
    log: (String) -> Unit,
    headOnly: Boolean,
) {
    val songId = rejectUnlessAllowed(access, log) ?: return
    val payload = try {
        source.audio(songId)
    } catch (e: IOException) {
        finish(HttpStatusCode.InternalServerError, log)
        return
    } ?: return finish(HttpStatusCode.NotFound, log)

    val knownSize = payload.size >= 0
    if (knownSize) response.header(HttpHeaders.AcceptRanges, "bytes")
    payload.lastModifiedMs?.let { response.header(HttpHeaders.LastModified, HTTP_DATE.format(Instant.ofEpochMilli(it))) }

    when (val range = HttpRange.resolve(request.headers[HttpHeaders.Range], payload.size)) {
        RangeResult.NotSatisfiable -> {
            response.header(HttpHeaders.ContentRange, "bytes */${payload.size}")
            finish(HttpStatusCode.RequestedRangeNotSatisfiable, log)
        }
        is RangeResult.Partial -> {
            response.header(HttpHeaders.ContentRange, "bytes ${range.start}-${range.endInclusive}/${payload.size}")
            respondBody(HttpStatusCode.PartialContent, range.length, payload.mimeType, headOnly, io, log) {
                payload.open(range.start)
            }
        }
        RangeResult.Full -> respondBody(
            HttpStatusCode.OK,
            payload.size.takeIf { knownSize },
            payload.mimeType,
            headOnly,
            io,
            log,
        ) { payload.open(0) }
    }
}

private suspend fun ApplicationCall.serveArt(
    access: SonosMediaAccess,
    source: SonosMediaSource,
    io: CoroutineDispatcher,
    log: (String) -> Unit,
    headOnly: Boolean,
) {
    val songId = rejectUnlessAllowed(access, log) ?: return
    val payload = try {
        source.art(songId)
    } catch (e: IOException) {
        finish(HttpStatusCode.InternalServerError, log)
        return
    } ?: return finish(HttpStatusCode.NotFound, log)

    payload.lastModifiedMs?.let { response.header(HttpHeaders.LastModified, HTTP_DATE.format(Instant.ofEpochMilli(it))) }
    respondBody(HttpStatusCode.OK, payload.size, payload.mimeType, headOnly, io, log) { payload.open() }
}

private suspend fun ApplicationCall.respondBody(
    status: HttpStatusCode,
    length: Long?,
    mimeType: String,
    headOnly: Boolean,
    io: CoroutineDispatcher,
    log: (String) -> Unit,
    open: () -> InputStream,
) {
    log("${request.httpMethod.value} ${request.path()} ${status.value}")
    val type = contentTypeOf(mimeType)
    if (headOnly) {
        respond(object : OutgoingContent.NoContent() {
            override val status: HttpStatusCode = status
            override val contentLength: Long? = length
            override val contentType: ContentType = type
        })
        return
    }
    respond(object : OutgoingContent.WriteChannelContent() {
        override val status: HttpStatusCode = status
        override val contentLength: Long? = length
        override val contentType: ContentType = type

        override suspend fun writeTo(channel: ByteWriteChannel) {
            val stream = try {
                open()
            } catch (_: IOException) {
                return // The file vanished after the headers were decided; closing the response is all that is left.
            }
            stream.use { copy(it, channel, length ?: Long.MAX_VALUE, io) }
        }
    })
}

/** Copies up to [limit] bytes. A speaker closing the connection halfway ends the copy quietly. */
private suspend fun copy(stream: InputStream, channel: ByteWriteChannel, limit: Long, io: CoroutineDispatcher) {
    val buffer = ByteArray(COPY_BUFFER_BYTES)
    var remaining = limit
    try {
        while (remaining > 0) {
            val read = withContext(io) { stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()) }
            if (read < 0) break
            channel.writeFully(buffer, 0, read)
            remaining -= read
        }
    } catch (_: IOException) {
        // The speaker aborted the transfer, which it does routinely when it seeks.
    }
}

private fun contentTypeOf(mimeType: String): ContentType = try {
    ContentType.parse(mimeType)
} catch (_: io.ktor.http.BadContentTypeFormatException) {
    ContentType.Application.OctetStream
}
