package com.theveloper.pixelplay.data.service.http

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.jupiter.api.Test

class MediaServerLeaseTest {

    private class FakeController : MediaServerController {
        val calls = CopyOnWriteArrayList<String>()
        var startFailure: RuntimeException? = null
        override fun start(deviceIpHint: String?) {
            startFailure?.let { throw it }
            calls += "start($deviceIpHint)"
        }
        override fun stop() { calls += "stop" }
    }

    private val controller = FakeController()
    private val lease = MediaServerLease(controller)

    @Test
    fun `an owner asks the server to start with its address hint`() {
        lease.acquire(ServerOwner.CAST, "192.168.1.30")

        assertThat(controller.calls).containsExactly("start(192.168.1.30)")
        assertThat(lease.isHeldBy(ServerOwner.CAST)).isTrue()
    }

    @Test
    fun `asking again starts again so a server that died on its own comes back`() {
        lease.acquire(ServerOwner.CAST, "192.168.1.30")
        lease.acquire(ServerOwner.CAST, "192.168.1.30")

        assertThat(controller.calls).containsExactly("start(192.168.1.30)", "start(192.168.1.30)")
        assertThat(lease.isHeldBy(ServerOwner.CAST)).isTrue()
    }

    @Test
    fun `a start that fails does not leave the owner holding the server`() {
        controller.startFailure = IllegalStateException("not allowed")

        val failure = runCatching { lease.acquire(ServerOwner.CAST) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(lease.isHeldBy(ServerOwner.CAST)).isFalse()
    }

    @Test
    fun `a failed start keeps an owner that already held the server`() {
        lease.acquire(ServerOwner.SONOS)
        controller.startFailure = IllegalStateException("not allowed")

        runCatching { lease.acquire(ServerOwner.SONOS) }

        assertThat(lease.isHeldBy(ServerOwner.SONOS)).isTrue()
    }

    @Test
    fun `releasing one owner while another is alive keeps the server running`() {
        lease.acquire(ServerOwner.SONOS)
        lease.acquire(ServerOwner.CAST)

        lease.release(ServerOwner.CAST)

        assertThat(controller.calls).doesNotContain("stop")
        assertThat(lease.isHeldBy(ServerOwner.SONOS)).isTrue()
        assertThat(lease.isHeldBy(ServerOwner.CAST)).isFalse()
    }

    @Test
    fun `releasing the last owner stops the server`() {
        lease.acquire(ServerOwner.CAST)
        lease.release(ServerOwner.CAST)

        assertThat(controller.calls).containsExactly("start(null)", "stop").inOrder()
        assertThat(lease.isHeldBy(ServerOwner.CAST)).isFalse()
    }

    @Test
    fun `releasing twice is harmless because stopping is idempotent`() {
        lease.acquire(ServerOwner.CAST)
        lease.release(ServerOwner.CAST)
        lease.release(ServerOwner.CAST)

        assertThat(controller.calls).containsExactly("start(null)", "stop", "stop").inOrder()
    }

    @Test
    fun `releasing with nobody holding the server still stops an orphaned one`() {
        lease.release(ServerOwner.CAST)

        assertThat(controller.calls).containsExactly("stop")
    }

    @Test
    fun `releasing an owner that is not held does not stop a server held by another`() {
        lease.acquire(ServerOwner.SONOS)

        lease.release(ServerOwner.CAST)

        assertThat(controller.calls).containsExactly("start(null)")
    }

    @Test
    fun `stopping for a restart stops the server and keeps the owners`() {
        lease.acquire(ServerOwner.CAST, "192.168.1.30")

        lease.stopForRestart()

        assertThat(controller.calls).containsExactly("start(192.168.1.30)", "stop").inOrder()
        assertThat(lease.isHeldBy(ServerOwner.CAST)).isTrue()
    }

    @Test
    fun `acquiring again after the server stopped starts it again`() {
        lease.acquire(ServerOwner.CAST)
        lease.release(ServerOwner.CAST)
        lease.acquire(ServerOwner.SONOS)

        assertThat(controller.calls).containsExactly("start(null)", "stop", "start(null)").inOrder()
    }

    @Test
    fun `many threads taking and releasing at once never break the bookkeeping`() {
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val threads = List(8) { index ->
            thread {
                ready.countDown()
                go.await()
                val owner = if (index % 2 == 0) ServerOwner.CAST else ServerOwner.SONOS
                repeat(200) {
                    lease.acquire(owner)
                    lease.release(owner)
                }
                lease.acquire(owner)
            }
        }
        ready.await()
        go.countDown()
        threads.forEach { it.join() }

        assertThat(lease.isHeldBy(ServerOwner.CAST)).isTrue()
        assertThat(lease.isHeldBy(ServerOwner.SONOS)).isTrue()
        val stops = controller.calls.count { it == "stop" }
        val starts = controller.calls.count { it.startsWith("start") }
        assertThat(starts).isAtLeast(stops)
    }
}
