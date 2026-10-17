package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.model.TrackMetadata
import com.theveloper.pixelplay.data.sonos.net.SonosFailure
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import com.theveloper.pixelplay.data.sonos.net.safeName
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the speaker's queue equal to the queue the user wants. [submit] says what the queue should be now; the editor
 * works out the fewest changes ([QueueEditPlanner]) and sends them one after another, leaving the song that plays
 * alone. A newer queue overtakes the one in progress: the rest of the old plan is not sent and the new one is planned
 * from what the speaker really has. [speaker] is what it is known to have, also when a plan stopped half way.
 *
 * @param T one song of the queue
 * @param trackOf what the speaker needs to know about a song, or null for one it cannot get (it is left out)
 * @param playingIndex the place in [speaker] of the song that plays, or null when that is not known
 * @param publish told every song id about to be on the speaker, before any command: the media server only serves those
 * @param reload loads the queue from scratch when nothing of what is on the speaker can be kept
 * @param onFinished told when the speaker has the queue asked for (Ok), or why it stopped short
 */
internal class SonosQueueEditor<T>(
    private val commander: SonosCommander,
    scope: CoroutineScope,
    initial: List<T>,
    private val idOf: (T) -> String,
    private val trackOf: (T) -> TrackMetadata?,
    private val playingIndex: () -> Int?,
    private val publish: (List<String>) -> Unit,
    private val reload: suspend (items: List<T>, tracks: List<TrackMetadata>) -> SonosResult<Unit>,
    private val onFinished: (SonosResult<Unit>) -> Unit,
) {

    @Volatile
    var speaker: List<T> = initial
        private set

    private class Request<T>(val generation: Long, val queue: List<T>)

    private val generations = AtomicLong()
    private val requests = Channel<Request<T>>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) {
                try {
                    run(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // One request that cannot be done must not end the editor: the next one may.
                    Timber.tag(LOG_TAG).e("A queue edit failed: %s", error.javaClass.simpleName)
                    onFinished(SonosResult.Err(SonosFailure.Network(error.javaClass.simpleName)))
                }
            }
        }
    }

    fun submit(queue: List<T>) {
        requests.trySend(Request(generations.incrementAndGet(), queue))
    }

    private suspend fun run(request: Request<T>) {
        // What the speaker needs to know about each song is asked once: the answer can change while the plan runs.
        val wanted = request.queue.mapNotNull { song -> trackOf(song)?.let { song to it } }
        publish(wanted.map { idOf(it.first) })
        val playing = playingIndex()
        val plan = QueueEditPlanner.plan(speaker.map(idOf), wanted.map { idOf(it.first) }, playing)
        val startedAt = System.nanoTime()
        Timber.tag(LOG_TAG).d("queue edit: on speaker=%d wanted=%d playing=%s plan=%s", speaker.size, wanted.size, playing, plan.joinToString { describe(it) })
        for (edit in plan) {
            if (generations.get() != request.generation) return
            val result = execute(edit, wanted)
            if (result is SonosResult.Err) {
                Timber.tag(LOG_TAG).w("queue edit stopped after %d ms: %s", (System.nanoTime() - startedAt) / 1_000_000, result.failure.safeName())
                onFinished(result)
                return
            }
        }
        if (generations.get() == request.generation) {
            Timber.tag(LOG_TAG).d("queue edit done in %d ms", (System.nanoTime() - startedAt) / 1_000_000)
            onFinished(SonosResult.Ok(Unit))
        }
    }

    /** What an edit does, with counts and places only: no titles and no addresses. */
    private fun describe(edit: QueueEdit): String = when (edit) {
        is QueueEdit.Remove -> "remove(${edit.first}, ${edit.count})"
        is QueueEdit.Move -> "move(${edit.first}, ${edit.count}, before ${edit.before})"
        is QueueEdit.Insert -> "insert(${edit.targetIndices.size} at ${edit.position})"
        QueueEdit.Reload -> "reload"
    }

    private suspend fun execute(edit: QueueEdit, wanted: List<Pair<T, TrackMetadata>>): SonosResult<Unit> = when (edit) {
        is QueueEdit.Remove -> commander.removeTracks(edit.first, edit.count).also {
            if (it is SonosResult.Ok) speaker = QueueListEdits.remove(speaker, edit.first - 1, edit.first - 1 + edit.count)
        }

        is QueueEdit.Move -> commander.moveTracks(edit.first, edit.count, edit.before).also {
            if (it is SonosResult.Ok) {
                val from = edit.first - 1
                val newIndex = if (edit.before - 1 > from) edit.before - 1 - edit.count else edit.before - 1
                speaker = QueueListEdits.move(speaker, from, from + edit.count, newIndex)
            }
        }

        is QueueEdit.Insert -> {
            val songs = edit.targetIndices.map { wanted[it] }
            val base = if (edit.position == 0) speaker.size else edit.position - 1
            var done = 0
            commander.insertTracks(songs.map { it.second }, edit.position) { inserted ->
                speaker = QueueListEdits.add(speaker, base + done, songs.subList(done, inserted).map { it.first })
                done = inserted
            }
        }

        QueueEdit.Reload -> {
            val result = reload(wanted.map { it.first }, wanted.map { it.second })
            if (result is SonosResult.Ok) speaker = wanted.map { it.first }
            result
        }
    }

    private companion object {
        const val LOG_TAG = "SonosSession"
    }
}
