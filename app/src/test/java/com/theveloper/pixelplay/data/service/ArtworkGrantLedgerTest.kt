package com.theveloper.pixelplay.data.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ArtworkGrantLedgerTest {

    @Test
    fun firstGrantForAPackageIsNeeded() {
        val ledger = ArtworkGrantLedger()

        assertThat(ledger.shouldGrant("controller.a", "content://x/song/1")).isTrue()
    }

    @Test
    fun repeatingTheSameUriForTheSamePackageIsSkipped() {
        val ledger = ArtworkGrantLedger()
        ledger.shouldGrant("controller.a", "content://x/song/1")

        assertThat(ledger.shouldGrant("controller.a", "content://x/song/1")).isFalse()
    }

    @Test
    fun aNewUriForTheSamePackageIsGranted() {
        val ledger = ArtworkGrantLedger()
        ledger.shouldGrant("controller.a", "content://x/song/1")

        assertThat(ledger.shouldGrant("controller.a", "content://x/song/2")).isTrue()
    }

    @Test
    fun packagesAreTrackedIndependently() {
        val ledger = ArtworkGrantLedger()
        ledger.shouldGrant("controller.a", "content://x/song/1")

        assertThat(ledger.shouldGrant("controller.b", "content://x/song/1")).isTrue()
    }

    @Test
    fun forgettingAPackageGrantsAgain() {
        val ledger = ArtworkGrantLedger()
        ledger.shouldGrant("controller.a", "content://x/song/1")
        ledger.forget("controller.a")

        assertThat(ledger.shouldGrant("controller.a", "content://x/song/1")).isTrue()
    }
}
