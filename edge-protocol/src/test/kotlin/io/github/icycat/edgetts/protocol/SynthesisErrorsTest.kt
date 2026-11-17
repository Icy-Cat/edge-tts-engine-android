package io.github.icycat.edgetts.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class SynthesisErrorsTest {
    @Test fun retryPolicyMatchesNetworkFailuresOnly() {
        assertFalse(SynthesisErrors.isRetryable(EdgeException.HttpStatus(403, null, "no")))
        assertFalse(SynthesisErrors.isRetryable(EdgeException.HttpStatus(400, null, "bad")))
        assertTrue(SynthesisErrors.isRetryable(EdgeException.HttpStatus(429, null, "slow")))
        assertTrue(SynthesisErrors.isRetryable(EdgeException.HttpStatus(503, null, "down")))
        assertTrue(SynthesisErrors.isRetryable(EdgeException.WebSocketError("reset")))
        assertTrue(SynthesisErrors.isRetryable(SocketTimeoutException("t")))
        assertTrue(SynthesisErrors.isRetryable(IOException("io")))
        assertFalse(SynthesisErrors.isRetryable(EdgeException.NoAudioReceived()))
        assertFalse(SynthesisErrors.isRetryable(EdgeException.UnexpectedResponse("bad frame")))
        assertFalse(SynthesisErrors.isRetryable(EdgeException.UnknownResponse("path")))
        assertFalse(SynthesisErrors.isRetryable(EdgeException.SkewAdjustment("date")))
        assertFalse(SynthesisErrors.isRetryable(EdgeException.Cancelled()))
        assertFalse(SynthesisErrors.isRetryable(IllegalStateException("boom")))
    }

    @Test fun failureKinds() {
        assertEquals(SynthesisFailureKind.TIMEOUT, SynthesisErrors.failureKind(SocketTimeoutException("t")))
        assertEquals(
            SynthesisFailureKind.TIMEOUT,
            SynthesisErrors.failureKind(EdgeException.WebSocketError("idle", SocketTimeoutException("idle"))),
        )
        assertEquals(SynthesisFailureKind.NETWORK, SynthesisErrors.failureKind(EdgeException.HttpStatus(403, null, "x")))
        assertEquals(SynthesisFailureKind.SYNTHESIS, SynthesisErrors.failureKind(EdgeException.NoAudioReceived()))
        assertEquals(SynthesisFailureKind.SERVICE, SynthesisErrors.failureKind(EdgeException.SkewAdjustment("x")))
        assertEquals(SynthesisFailureKind.SERVICE, SynthesisErrors.failureKind(IllegalArgumentException("x")))
    }
}
