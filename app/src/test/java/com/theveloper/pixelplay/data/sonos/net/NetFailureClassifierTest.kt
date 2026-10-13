package com.theveloper.pixelplay.data.sonos.net

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownServiceException
import org.junit.jupiter.api.Test

class NetFailureClassifierTest {

    @Test
    fun `a socket timeout is a timeout`() {
        assertThat(NetFailureClassifier.classify(SocketTimeoutException("timeout")))
            .isEqualTo(SonosFailure.Timeout)
    }

    @Test
    fun `a call that ran out of time is a timeout`() {
        assertThat(NetFailureClassifier.classify(InterruptedIOException("timeout")))
            .isEqualTo(SonosFailure.Timeout)
    }

    @Test
    fun `a refused connection on the jvm is reported as refused`() {
        assertThat(NetFailureClassifier.classify(ConnectException("Connection refused")))
            .isEqualTo(SonosFailure.ConnectionRefused)
    }

    @Test
    fun `a refused connection is reported as refused`() {
        val e = ConnectException("failed to connect to /192.168.1.10 (port 1400): connect failed: ECONNREFUSED (Connection refused)")

        assertThat(NetFailureClassifier.classify(e)).isEqualTo(SonosFailure.ConnectionRefused)
    }

    @Test
    fun `operation not permitted means the local network permission is missing`() {
        assertThat(NetFailureClassifier.classify(IOException("EPERM (Operation not permitted)")))
            .isEqualTo(SonosFailure.LocalNetworkDenied)
        assertThat(NetFailureClassifier.classify(SocketException("sendto failed: EPERM (Operation not permitted)")))
            .isEqualTo(SonosFailure.LocalNetworkDenied)
    }

    @Test
    fun `unreachable network or host is reported as unreachable`() {
        assertThat(NetFailureClassifier.classify(SocketException("sendto failed: ENETUNREACH (Network is unreachable)")))
            .isEqualTo(SonosFailure.NetworkUnreachable)
        assertThat(NetFailureClassifier.classify(IOException("Network is unreachable")))
            .isEqualTo(SonosFailure.NetworkUnreachable)
        assertThat(NetFailureClassifier.classify(NoRouteToHostException("No route to host")))
            .isEqualTo(SonosFailure.NetworkUnreachable)
        assertThat(NetFailureClassifier.classify(ConnectException("connect failed: EHOSTUNREACH (No route to host)")))
            .isEqualTo(SonosFailure.NetworkUnreachable)
    }

    @Test
    fun `blocked cleartext traffic is a configuration failure`() {
        val e = UnknownServiceException("CLEARTEXT communication to 192.168.1.10 not permitted by network security policy")

        assertThat(NetFailureClassifier.classify(e)).isEqualTo(SonosFailure.CleartextBlocked)
    }

    @Test
    fun `any other io failure keeps its message`() {
        assertThat(NetFailureClassifier.classify(IOException("boom"))).isEqualTo(SonosFailure.Network("boom"))
    }

    @Test
    fun `an io failure without message has an empty detail`() {
        assertThat(NetFailureClassifier.classify(IOException())).isEqualTo(SonosFailure.Network(""))
    }
}
