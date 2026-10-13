package com.theveloper.pixelplay.data.sonos.session

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.sonos.model.TransportState
import org.junit.jupiter.api.Test

class ReattachPlannerTest {

    private val saved = SavedPlayback(trackNumber = 7, positionMs = 60_000L, durationMs = 200_000L, savedAtMs = 1_000_000L)

    private fun plan(
        state: TransportState,
        queueLength: Int = 11,
        playback: SavedPlayback? = saved,
        nowMs: Long = 1_005_000L,
    ) = ReattachPlanner.plan(state, queueLength, playback, nowMs)

    @Test
    fun `a speaker that is playing is left alone`() {
        assertThat(plan(TransportState.PLAYING)).isEqualTo(ReattachAction.LeaveAsIs)
        assertThat(plan(TransportState.TRANSITIONING)).isEqualTo(ReattachAction.LeaveAsIs)
    }

    @Test
    fun `a paused speaker only needs play`() {
        assertThat(plan(TransportState.PAUSED_PLAYBACK)).isEqualTo(ReattachAction.Resume)
    }

    @Test
    fun `a stopped speaker resumes where the phone left off`() {
        assertThat(plan(TransportState.STOPPED)).isEqualTo(ReattachAction.SeekAndPlay(trackNumber = 7, positionMs = 60_000L))
    }

    @Test
    fun `a track that must have ended moves on to the next one`() {
        val action = plan(TransportState.STOPPED, nowMs = saved.savedAtMs + 200_000L)

        assertThat(action).isEqualTo(ReattachAction.SeekAndPlay(trackNumber = 8, positionMs = 0L))
    }

    @Test
    fun `after the last track the queue starts over`() {
        val last = saved.copy(trackNumber = 11)

        val action = plan(TransportState.STOPPED, playback = last, nowMs = last.savedAtMs + 200_000L)

        assertThat(action).isEqualTo(ReattachAction.SeekAndPlay(trackNumber = 1, positionMs = 0L))
    }

    @Test
    fun `the finished margin is inclusive`() {
        val exactlyAtMargin = saved.savedAtMs + (saved.durationMs - saved.positionMs - ReattachPlanner.FINISHED_MARGIN_MS)

        assertThat(plan(TransportState.STOPPED, nowMs = exactlyAtMargin))
            .isEqualTo(ReattachAction.SeekAndPlay(trackNumber = 8, positionMs = 0L))
        assertThat(plan(TransportState.STOPPED, nowMs = exactlyAtMargin - 1))
            .isEqualTo(ReattachAction.SeekAndPlay(trackNumber = 7, positionMs = 60_000L))
    }

    @Test
    fun `an unknown duration never counts as finished`() {
        val unknown = saved.copy(durationMs = 0L)

        assertThat(plan(TransportState.STOPPED, playback = unknown, nowMs = unknown.savedAtMs + 10_000_000L))
            .isEqualTo(ReattachAction.SeekAndPlay(trackNumber = 7, positionMs = 60_000L))
    }

    @Test
    fun `a clock behind the save time counts as no time elapsed`() {
        assertThat(plan(TransportState.STOPPED, nowMs = saved.savedAtMs - 5_000L))
            .isEqualTo(ReattachAction.SeekAndPlay(trackNumber = 7, positionMs = 60_000L))
    }

    @Test
    fun `a saved track outside the queue falls back to the first track`() {
        assertThat(plan(TransportState.STOPPED, queueLength = 5))
            .isEqualTo(ReattachAction.SeekAndPlay(trackNumber = 1, positionMs = 0L))
    }

    @Test
    fun `nothing saved or an empty queue means there is nothing to resume`() {
        assertThat(plan(TransportState.STOPPED, playback = null)).isEqualTo(ReattachAction.LeaveAsIs)
        assertThat(plan(TransportState.STOPPED, queueLength = 0)).isEqualTo(ReattachAction.LeaveAsIs)
    }

    @Test
    fun `states that say nothing about our queue are left alone`() {
        assertThat(plan(TransportState.NO_MEDIA_PRESENT)).isEqualTo(ReattachAction.LeaveAsIs)
        assertThat(plan(TransportState.UNKNOWN)).isEqualTo(ReattachAction.LeaveAsIs)
    }
}
