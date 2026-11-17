package io.github.icycat.edgetts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopGateTest {
    @Test fun stopAfterOpenCancelsThatRunOnly() {
        val gate = StopGate()
        val first = gate.open()
        assertFalse(gate.isStopped(first))
        gate.stop()
        assertTrue(gate.isStopped(first))
        val second = gate.open()
        assertFalse(gate.isStopped(second))
        assertTrue(gate.isStopped(first))
    }

    @Test fun stopBeforeOpenDoesNotCancelTheNextRun() {
        val gate = StopGate()
        gate.stop()
        val token = gate.open()
        assertFalse(gate.isStopped(token))
    }
}
