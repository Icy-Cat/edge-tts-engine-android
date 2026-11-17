package io.github.icycat.edgetts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class AtomicFileTest {
    @Test fun replaceLeavesACompleteFileAndNoTemp() {
        val dir = Files.createTempDirectory("edge-voices").toFile()
        val target = dir.resolve("voices.json")
        AtomicFile.write(target, "{\"a\":1}")
        AtomicFile.write(target, "{\"a\":2}")
        assertEquals("{\"a\":2}", target.readText())
        assertFalse(dir.resolve("voices.json.tmp").exists())
    }
}
