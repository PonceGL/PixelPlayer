package com.theveloper.pixelplay.data.service.http

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import org.junit.jupiter.api.Test

class MediaServerLeaseTest {

    private class FakeController : MediaServerController {
        val calls = CopyOnWriteArrayList<String>()
        override fun start(deviceIpHint: String?) { calls += "start($deviceIpHint)" }
        override fun stop() { calls += "stop" }
    }

    private val controller = FakeController()
    private val lease = MediaServerLease(controller)

    @Test
    fun `the first owner starts the server with its address hint`() {
        lease.acquire(ServerOwner.CAST, "192.168.1.30")

        assertThat(controller.calls).containsExactly("start(192.168.1.30)")
        assertThat(lease.isHeldBy(ServerOwner.CAST)).isTrue()
    }

    @Test
    fun `a second owner does not restart a running server`() {
        lease.acquire(ServerOwner.CAST, "192.168.1.30")
        lease.acquire(ServerOwner.SONOS, "192.168.1.10")

        assertThat(controller.calls).containsExactly("start(192.168.1.30)")
    }

    @Test
    fun `acquiring twice as the same owner does nothing`() {
        lease.acquire(ServerOwner.CAST)
        lease.acquire(ServerOwner.CAST)

        assertThat(controller.calls).containsExactly("start(null)")
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
    fun `releasing the last owner stops the server once`() {
        lease.acquire(ServerOwner.CAST)
        lease.release(ServerOwner.CAST)
        lease.release(ServerOwner.CAST)

        assertThat(controller.calls).containsExactly("start(null)", "stop").inOrder()
    }

    @Test
    fun `releasing without having acquired does nothing`() {
        lease.release(ServerOwner.SONOS)

        assertThat(controller.calls).isEmpty()
    }

    @Test
    fun `releasing an owner that is not held does not stop a server held by another`() {
        lease.acquire(ServerOwner.SONOS)

        lease.release(ServerOwner.CAST)

        assertThat(controller.calls).containsExactly("start(null)")
    }

    @Test
    fun `restart stops and starts again while someone holds the server`() {
        lease.acquire(ServerOwner.CAST, "192.168.1.30")

        lease.restart("192.168.5.30")

        assertThat(controller.calls).containsExactly("start(192.168.1.30)", "stop", "start(192.168.5.30)").inOrder()
        assertThat(lease.isHeldBy(ServerOwner.CAST)).isTrue()
    }

    @Test
    fun `restart with no owners does nothing`() {
        lease.restart("192.168.5.30")

        assertThat(controller.calls).isEmpty()
    }

    @Test
    fun `acquiring again after the server stopped starts it again`() {
        lease.acquire(ServerOwner.CAST)
        lease.release(ServerOwner.CAST)
        lease.acquire(ServerOwner.SONOS)

        assertThat(controller.calls).containsExactly("start(null)", "stop", "start(null)").inOrder()
    }

    @Test
    fun `many threads acquiring at once start the server a single time`() {
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val threads = List(8) { index ->
            thread {
                ready.countDown()
                go.await()
                lease.acquire(if (index % 2 == 0) ServerOwner.CAST else ServerOwner.SONOS)
            }
        }
        ready.await()
        go.countDown()
        threads.forEach { it.join() }

        assertThat(controller.calls).containsExactly("start(null)")
    }
}
