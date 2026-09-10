package com.theveloper.pixelplay.data.download.storage

/**
 * Turns a `remoteId` into a filesystem-safe file name segment, for both storage backends.
 *
 * A Jellyfin `remoteId` is already a safe GUID, but this has to hold for sources that aren't
 * Jellyfin too — written generic: anything outside `[A-Za-z0-9._-]` becomes `_`, and the
 * result always carries a short suffix derived from the *original* `remoteId` — never a
 * counter, so it's deterministic and never needs a lookup against what's already on disk.
 *
 * The suffix does double duty: it's what makes two different ids that happen to sanitize to
 * the same string (e.g. `"a/b"` and `"a:b"`, both becoming `"a_b"`) end up with different file
 * names, and it's also where the byte budget gets spent when truncating for length, so a
 * truncated name never collides with another truncated name either.
 *
 * The whole result — sanitized body plus suffix — is capped to [MAX_FILENAME_BYTES], the
 * common filesystem limit for a single path segment. Because every character the sanitizer
 * can produce is ASCII, byte length and character length are the same number here, so no
 * multi-byte character ever gets cut in half.
 */
internal fun sanitizeRemoteIdForFilename(remoteId: String): String {
    val suffix = "_" + remoteId.hashCode().toUInt().toString(16)
    val sanitizedBudget = (MAX_FILENAME_BYTES - suffix.length).coerceAtLeast(1)
    val sanitizedBody = remoteId.replace(UNSAFE_FILENAME_CHARS, "_").take(sanitizedBudget)
    return sanitizedBody + suffix
}

private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]")
private const val MAX_FILENAME_BYTES = 255
