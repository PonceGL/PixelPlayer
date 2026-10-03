package com.theveloper.pixelplay.data.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArtworkGrantLedgerTest {

    @Test
    fun firstGrantForAPackageIsNeeded() {
        val ledger = ArtworkGrantLedger()

        assertThat(ledger.needsGrant("controller.a", "content://x/song/1")).isTrue()
    }

    @Test
    fun aGrantThatWasNeverConfirmedIsStillNeeded() {
        val ledger = ArtworkGrantLedger()
        ledger.needsGrant("controller.a", "content://x/song/1")

        assertThat(ledger.needsGrant("controller.a", "content://x/song/1")).isTrue()
    }

    @Test
    fun aConfirmedGrantIsNotRepeated() {
        val ledger = ArtworkGrantLedger()
        ledger.markGranted("controller.a", "content://x/song/1")

        assertThat(ledger.needsGrant("controller.a", "content://x/song/1")).isFalse()
    }

    @Test
    fun aNewUriForTheSamePackageIsGranted() {
        val ledger = ArtworkGrantLedger()
        ledger.markGranted("controller.a", "content://x/song/1")

        assertThat(ledger.needsGrant("controller.a", "content://x/song/2")).isTrue()
    }

    @Test
    fun packagesAreTrackedIndependently() {
        val ledger = ArtworkGrantLedger()
        ledger.markGranted("controller.a", "content://x/song/1")

        assertThat(ledger.needsGrant("controller.b", "content://x/song/1")).isTrue()
    }

    @Test
    fun forgettingAPackageGrantsAgain() {
        val ledger = ArtworkGrantLedger()
        ledger.markGranted("controller.a", "content://x/song/1")
        ledger.forget("controller.a")

        assertThat(ledger.needsGrant("controller.a", "content://x/song/1")).isTrue()
    }
}
