package com.theveloper.pixelplay.data.sonos.server

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SonosServerIdentityTest {

    private class FakeStore(
        var stored: StoredIdentity? = null,
        var saveWorks: Boolean = true,
    ) : SonosServerIdentityStore {
        var saves = 0
        override fun load(): StoredIdentity? = stored
        override fun save(token: String, port: Int?): Boolean {
            saves++
            if (!saveWorks) return false
            stored = StoredIdentity(token, port)
            return true
        }
    }

    private var counter = 0
    private fun provider(store: SonosServerIdentityStore) = SonosServerIdentityProvider(store) {
        counter++
        ByteArray(16) { (counter * 16 + it).toByte() }
    }

    @Test
    fun `the first run creates a 32 character hex token and saves it`() {
        val store = FakeStore()

        val identity = provider(store).current()

        assertThat(identity.token).matches("[0-9a-f]{32}")
        assertThat(identity.port).isNull()
        assertThat(store.stored?.token).isEqualTo(identity.token)
    }

    @Test
    fun `a new process finds the same token and port`() {
        val store = FakeStore()
        val first = provider(store)
        val token = first.current().token
        first.rememberPort(8123)

        val second = provider(store).current()

        assertThat(second.token).isEqualTo(token)
        assertThat(second.port).isEqualTo(8123)
    }

    @Test
    fun `the identity is read once and stays the same during a run`() {
        val store = FakeStore()
        val provider = provider(store)

        val first = provider.current()
        val second = provider.current()

        assertThat(second).isEqualTo(first)
        assertThat(store.saves).isEqualTo(1)
    }

    @Test
    fun `an invalid stored token is replaced`() {
        val invalid = listOf("", "abc", "ABCDEF0123456789ABCDEF0123456789", "zz".repeat(16), "a&b=" + "0".repeat(28), "0".repeat(33))
        for (bad in invalid) {
            val store = FakeStore(StoredIdentity(bad, 8080))

            val identity = provider(store).current()

            assertThat(identity.token).isNotEqualTo(bad)
            assertThat(identity.token).matches("[0-9a-f]{32}")
        }
    }

    @Test
    fun `a stored port outside the allowed range is dropped but the token is kept`() {
        val token = "0123456789abcdef0123456789abcdef"
        for (badPort in listOf(0, -1, 80, 1023, 65536, 70000)) {
            val identity = provider(FakeStore(StoredIdentity(token, badPort))).current()

            assertThat(identity.token).isEqualTo(token)
            assertThat(identity.port).isNull()
        }
    }

    @Test
    fun `ports at the edges of the range are accepted`() {
        val token = "0123456789abcdef0123456789abcdef"

        assertThat(provider(FakeStore(StoredIdentity(token, 1024))).current().port).isEqualTo(1024)
        assertThat(provider(FakeStore(StoredIdentity(token, 65535))).current().port).isEqualTo(65535)
    }

    @Test
    fun `remembering an invalid port changes nothing`() {
        val store = FakeStore()
        val provider = provider(store)
        provider.current()
        val savesBefore = store.saves

        provider.rememberPort(0)
        provider.rememberPort(99999)

        assertThat(provider.current().port).isNull()
        assertThat(store.saves).isEqualTo(savesBefore)
    }

    @Test
    fun `an unreadable store starts a fresh identity`() {
        val provider = provider(FakeStore(stored = null))

        assertThat(provider.current().token).matches("[0-9a-f]{32}")
    }

    @Test
    fun `a store that cannot save still lets the run continue with its token in memory`() {
        val store = FakeStore(saveWorks = false)
        val provider = provider(store)

        val first = provider.current()
        provider.rememberPort(8123)

        assertThat(provider.current().token).isEqualTo(first.token)
        assertThat(provider.current().port).isEqualTo(8123)
        assertThat(store.stored).isNull()
    }

    @Test
    fun `the port counts as changed when the saved one was taken`() {
        val token = "0123456789abcdef0123456789abcdef"
        val provider = provider(FakeStore(StoredIdentity(token, 8123)))
        provider.current()

        provider.rememberPort(8124)

        assertThat(provider.portChanged).isTrue()
        assertThat(provider.current().port).isEqualTo(8124)
    }

    @Test
    fun `the port does not count as changed when it is the same or there was none`() {
        val token = "0123456789abcdef0123456789abcdef"
        val same = provider(FakeStore(StoredIdentity(token, 8123)))
        same.current()
        same.rememberPort(8123)
        val fresh = provider(FakeStore())
        fresh.current()
        fresh.rememberPort(8123)

        assertThat(same.portChanged).isFalse()
        assertThat(fresh.portChanged).isFalse()
    }

    @Test
    fun `two fresh installs do not share a token`() {
        val a = provider(FakeStore()).current().token
        val b = provider(FakeStore()).current().token

        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `the token is never part of the identity text form`() {
        val identity = SonosServerIdentity("0123456789abcdef0123456789abcdef", 8080)

        assertThat(identity.toString()).doesNotContain("0123456789abcdef")
    }
}
