package com.theveloper.pixelplay.data.sonos.session

import com.theveloper.pixelplay.data.sonos.model.TrackMetadata
import com.theveloper.pixelplay.data.sonos.net.SonosResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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
        scope.launch { for (request in requests) run(request) }
    }

    fun submit(queue: List<T>) {
        requests.trySend(Request(generations.incrementAndGet(), queue))
    }

    private suspend fun run(request: Request<T>) {
        val wanted = request.queue.filter { trackOf(it) != null }
        publish(wanted.map(idOf))
        val plan = QueueEditPlanner.plan(speaker.map(idOf), wanted.map(idOf), playingIndex())
        for (edit in plan) {
            if (generations.get() != request.generation) return
            val result = execute(edit, wanted)
            if (result is SonosResult.Err) {
                onFinished(result)
                return
            }
        }
        if (generations.get() == request.generation) onFinished(SonosResult.Ok(Unit))
    }

    private suspend fun execute(edit: QueueEdit, wanted: List<T>): SonosResult<Unit> = when (edit) {
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
            commander.insertTracks(songs.map { checkNotNull(trackOf(it)) }, edit.position) { inserted ->
                speaker = QueueListEdits.add(speaker, base + done, songs.subList(done, inserted))
                done = inserted
            }
        }

        QueueEdit.Reload -> {
            val result = reload(wanted, wanted.map { checkNotNull(trackOf(it)) })
            if (result is SonosResult.Ok) speaker = wanted
            result
        }
    }
}
