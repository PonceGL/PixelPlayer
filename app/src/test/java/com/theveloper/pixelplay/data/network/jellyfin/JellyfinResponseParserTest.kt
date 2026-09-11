package com.theveloper.pixelplay.data.network.jellyfin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JellyfinResponseParserTest {

    private fun songJson(mediaSource: JSONObject? = JSONObject()): JSONObject {
        val json = JSONObject().apply {
            put("Id", "item-1")
            put("Name", "Song")
        }
        if (mediaSource != null) {
            json.put("MediaSources", JSONArray().put(mediaSource))
        }
        return json
    }

    @Test
    fun `parseSong reads a reported size`() {
        val song = JellyfinResponseParser.parseSong(songJson(JSONObject().put("Size", 4_200_000L)))

        assertEquals(4_200_000L, song.size)
    }

    /**
     * `JSONObject.optLong("Size")` with no default defaults to `0`, not `null`, when the key
     * is absent — indistinguishable from a genuine zero-byte file unless guarded. The sibling
     * `JellyfinCloudDownloadSource.toRemoteItemInfo()` already guards this exact field with a
     * `-1L` sentinel; this is the same hazard in the sync path.
     */
    @Test
    fun `parseSong leaves size null when the media source doesn't report one`() {
        val song = JellyfinResponseParser.parseSong(songJson(JSONObject()))

        assertNull(song.size)
    }

    @Test
    fun `parseSong leaves size null when there are no media sources at all`() {
        val song = JellyfinResponseParser.parseSong(songJson(mediaSource = null))

        assertNull(song.size)
    }
}
