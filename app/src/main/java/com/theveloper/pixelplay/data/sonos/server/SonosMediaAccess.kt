package com.theveloper.pixelplay.data.sonos.server

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal sealed interface AccessDecision {
    data object Allowed : AccessDecision
    data object InvalidToken : AccessDecision
    data object NotPublished : AccessDecision
}

/**
 * Decides which requests the media server may answer for a speaker: the caller must know the token
 * and ask for a song that was published. Nothing else on the device is reachable.
 */
internal class SonosMediaAccess(private val identity: () -> SonosServerIdentity) {

    private val published: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Adds to what was published: the queue on the speaker keeps pointing at earlier songs. */
    fun publish(songIds: Collection<String>) {
        published += songIds.filter { it.isNotBlank() }
    }

    fun clear() = published.clear()

    fun authorize(providedToken: String?, songId: String?): AccessDecision {
        // Before anything is published there is nothing to protect, and the identity must not be created
        // just because someone on the network knocked.
        if (published.isEmpty()) return AccessDecision.InvalidToken
        if (providedToken == null || !tokensMatch(providedToken, identity().token)) return AccessDecision.InvalidToken
        return if (songId != null && songId in published) AccessDecision.Allowed else AccessDecision.NotPublished
    }

    /** False for ids that no address can carry. */
    fun servable(songId: String): Boolean = songId.isNotBlank() && songId != "." && songId != ".."

    /** True when [token] is the one this server hands out, so an address carrying it was made by this app. */
    fun isOwnToken(token: String?): Boolean = !token.isNullOrEmpty() && tokensMatch(token, identity().token)

    fun songUrl(baseUrl: String, songId: String, revision: String): String = urlFor("song", baseUrl, songId, revision)

    fun artUrl(baseUrl: String, songId: String, revision: String): String = urlFor("art", baseUrl, songId, revision)

    private fun urlFor(kind: String, baseUrl: String, songId: String, revision: String): String {
        val base = requireNotNull(baseUrl.toHttpUrlOrNull()) { "Invalid media server address" }
        // "." and ".." would be resolved by the URL instead of reaching the route as an id.
        require(servable(songId)) { "A song id that cannot travel in an address" }
        return base.newBuilder()
            .encodedPath("/")
            .addPathSegment("sonos")
            .addPathSegment(kind)
            .addPathSegment(songId)
            .addQueryParameter("v", revision)
            .addQueryParameter(TOKEN_PARAMETER, identity().token)
            .build()
            .toString()
    }

    private fun tokensMatch(provided: String, expected: String): Boolean =
        MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    companion object {
        const val TOKEN_PARAMETER = "auth"
    }
}

private val TOKEN_IN_TEXT = Regex("([?&]${SonosMediaAccess.TOKEN_PARAMETER}=)[^&\\s#\"']+")

/** Hides the token of every media URL in [text], so it can be logged or reported. */
internal fun redactToken(text: String): String = TOKEN_IN_TEXT.replace(text, "$1<redacted>")
