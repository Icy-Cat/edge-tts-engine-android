package io.github.icycat.edgetts.protocol

// 本文件是 rany2/edge-tts 7.3.1 drm.py 中 v2 摘要的修改移植。
// 来源与 GNU LGPLv3 only 见 third_party/edge-tts/NOTICE.md。

/**
 * Sec-MS-GEC v2，移植自 rany2/edge-tts 的 `generate_sec_ms_gec_v2`（drm.py）。
 * 这不是标准 SHA-256：摘要常量、初始状态和 CS64 混合都按上游实现逐项对应。
 * 正确性以 [EdgeProtocolTest] 里的固定时间戳向量为准。
 */
internal object SecMsGec {
    private const val U32 = 0xFFFFFFFFL
    private const val WIN_EPOCH_SECONDS = 11644473600L

    private val SHA256_INITIAL = longArrayOf(
        0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
        0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L,
    )
    private val SHA256_STATE = longArrayOf(
        0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
        0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L,
    )
    private val SHA256_SCHEDULE = longArrayOf(
        0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
        0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
        0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
        0xD258B060L, 0x72766564L,
    )
    private val SHA256_ROUND_CONSTANTS = longArrayOf(
        0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L, 0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
        0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L, 0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
        0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL, 0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
        0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L, 0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
        0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L, 0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
        0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L, 0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
        0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L, 0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
        0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L, 0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
    )
    private const val SHA256_ADJUSTMENT = 0xC9DC06A7L
    private val SHA256_BLOCK_CONSTANTS = LongArray(64) { i ->
        if (i == 14) SHA256_ADJUSTMENT else SHA256_ROUND_CONSTANTS[i]
    }
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80L
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0L

    private val CS64_COEFFICIENTS = arrayOf(
        0xE82E91B1L to 0xB32700D0L,
        0x44DE24C6L to 0x88DFF51DL,
        0x3BEB65BCL to 0x29514038L,
        0xC89C5933L to 0xB328CBDFL,
        0x8D1EC211L to 0xD405B4D5L,
        0x314EF5D5L to 0x26E55F3CL,
        0xE92491B1L to 0x607801C6L,
        0xF0A5B9D5L to 0x82B2C1FBL,
        0x06064CF3L to 0xFA51A3DAL,
        0xDA79FA60L to 0x207CC464L,
        0xBED20560L to 0x3C3C74F3L,
        0x89F9C9F8L to 0xD2D14B61L,
        0x35FA38C6L to 0xBB04A2C1L,
        0xCB49E3C6L to 0xC5626167L,
    )
    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000L

    fun token(unixSeconds: Double): String {
        val material = (windowsFileTicks(unixSeconds) + EdgeProtocol.TRUSTED_CLIENT_TOKEN)
            .toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(material)
        val (csA, csB) = cs64Digest(material, digest)
        return hexLe32(csA) + hexLe32(csB) + "-" + digest.toHex()
    }

    /** 与上游 `f"{ticks:.0f}"` 在这些时间戳上一致：先落到 5 分钟窗口，再换成 100ns Windows file time。 */
    internal fun windowsFileTicks(unixSeconds: Double): String {
        var secs = Math.floor(unixSeconds).toLong() + WIN_EPOCH_SECONDS
        secs -= Math.floorMod(secs, 300L)
        return (secs * 10_000_000L).toString()
    }

    private fun u32(v: Long): Long = v and U32

    private fun repeatWord(word: Long): Long = u32(word) * 0x1_0000_0001L

    private fun sumRot(rot: Long): Long = (rot ushr 6) xor (rot ushr 11) xor (rot ushr 25)

    private fun majRot(rot: Long): Long = (rot ushr 2) xor (rot ushr 13) xor (rot ushr 22)

    private fun sigma0(value: Long): Long {
        val v = u32(value)
        return u32((v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3))
    }

    private fun sigma1(value: Long): Long {
        val v = u32(value)
        return u32((v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10))
    }

    private fun rol16(value: Long): Long {
        val v = u32(value)
        return u32((v shl 16) or (v ushr 16))
    }

    private fun sha256Rounds(state: LongArray, words: List<Long>, constants: LongArray, firstRound: Int): LongArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (index in firstRound until 64) {
            val t1 = h + sumRot(repeatWord(e)) + (g xor (e and (f xor g))) + constants[index] + words[index]
            val t2 = majRot(repeatWord(a)) + ((a and b) or (c and (a or b)))
            h = g
            g = f
            f = e
            e = u32(d + t1)
            d = c
            c = b
            b = a
            a = u32(t1 + t2)
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expandSchedule(words: MutableList<Long>, firstWord: Int) {
        for (index in firstWord until 64) {
            val x = words[index - 2]
            val y = words[index - 15]
            val s1 = (x ushr 17) xor (x shl 15) xor (x ushr 19) xor (x shl 13) xor (x ushr 10)
            val s0 = (y ushr 7) xor (y shl 25) xor (y ushr 18) xor (y shl 14) xor (y ushr 3)
            words += u32(s1 + words[index - 7] + s0 + words[index - 16])
        }
    }

    private fun sha256Init(prefix: ByteArray): LongArray {
        val words = MutableList(16) { 0L }
        words[14] = be(prefix, 0, 3)
        words[15] = be(prefix, 3, 4)
        for (index in 16 until 30) {
            var value = SHA256_SCHEDULE[index - 16]
            if (index - 2 >= 14) {
                val s = sigma1(words[index - 2])
                value += if (index == 16) s xor SHA256_SIGMA1_ADJUSTMENT else s
            }
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                val s = sigma0(words[index - 15])
                value += if (index == 29) s xor SHA256_SIGMA0_ADJUSTMENT else s
            }
            words += u32(value)
        }
        words += u32(
            sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] +
                SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14],
        )
        expandSchedule(words, 31)
        val working = sha256Rounds(SHA256_STATE, words, SHA256_BLOCK_CONSTANTS, 14)
        return LongArray(8) { i -> u32(SHA256_INITIAL[i] + working[i]) }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Token material is shorter than 7 bytes" }
        val length = 57 + data.size
        val zeros = Math.floorMod(55 - length, 64)
        val body = data.copyOfRange(7, data.size)
        val padded = ByteArray(body.size + 1 + zeros + 8)
        body.copyInto(padded)
        padded[body.size] = 0x80.toByte()
        val bitLen = length.toLong() * 8L
        for (i in 0 until 8) padded[padded.size - 1 - i] = (bitLen ushr (8 * i)).toByte()
        var state = sha256Init(data.copyOfRange(0, 7))
        var offset = 0
        while (offset < padded.size) {
            val words = MutableList(16) { i -> be(padded, offset + i * 4, 4) }
            expandSchedule(words, 16)
            val working = sha256Rounds(state, words, SHA256_ROUND_CONSTANTS, 0)
            state = LongArray(8) { i -> u32(state[i] + working[i]) }
            offset += 64
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            val v = state[i]
            out[i * 4] = (v ushr 24).toByte()
            out[i * 4 + 1] = (v ushr 16).toByte()
            out[i * 4 + 2] = (v ushr 8).toByte()
            out[i * 4 + 3] = v.toByte()
        }
        return out
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
        var keyA = readLe32(digest, 0) or 1L
        var keyB = readLe32(digest, 4) or 1L
        keyA = u32(keyA)
        keyB = u32(keyB)
        val factors = longArrayOf(u32(keyA * CS64_FACTOR), u32(keyB * CS64_FACTOR))
        val terms = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        CS64_COEFFICIENTS.forEachIndexed { index, (coefficient, offset) ->
            terms += u32(coefficient * factors[index % 2])
            offsets += offset
        }
        wordsLe4Even(byteArrayOf(0) + data).forEachIndexed { index, word ->
            var term = word * (if (index % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (index % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factors[0]
                offset += CS64_ADJUSTMENT1
            }
            terms += u32(term)
            offsets += u32(offset)
        }
        var swapAcc = 0L
        var swapLast = 0L
        var state = 0L
        var acc = 0L
        var index = 0
        while (index < terms.size) {
            val x = terms[index]
            val y = terms[index + 1]
            var t = rol16(u32(offsets[index] + x))
            var q = u32(t * 0x76F50F34L)
            swapAcc = u32(swapAcc + u32(rol16(q) * 0xC3EFEA97L + q))
            t = rol16(u32(offsets[index + 1] + y))
            q = u32(t * 0x1881CE3CL)
            swapLast = u32(rol16(q) * 0x35BD1EC9L + q)
            swapAcc = u32(swapAcc + swapLast)

            state = u32(state * keyA + x)
            state = u32(rol16(state) * 0xCF98B111L)
            state = u32(rol16(state) * 0x87085B9FL)
            state = u32(rol16(state) * 0x12CEB96DL)
            state = u32(rol16(state) * 0x257E1D83L)
            acc += state
            t = u32(state * keyB + y)
            t = u32(rol16(t) * 0xA27416F5L)
            t = u32(rol16(t) * 0xD38396FFL)
            state = u32(rol16(u32(rol16(t) * 0x7C932B89L)) * 0xBFA49F69L)
            acc = u32(acc + state)
            index += 2
        }
        return u32(swapLast xor state) to u32(swapAcc xor acc)
    }

    private fun wordsLe4Even(data: ByteArray): LongArray {
        val pad = (4 - data.size % 4) % 4
        val padded = if (pad == 0) data else data + ByteArray(pad)
        if (padded.isEmpty()) return LongArray(0)
        val words = ArrayList<Long>(padded.size / 4 + 1)
        var i = 0
        while (i < padded.size) {
            words += (padded[i].toLong() and 0xFF) or
                ((padded[i + 1].toLong() and 0xFF) shl 8) or
                ((padded[i + 2].toLong() and 0xFF) shl 16) or
                ((padded[i + 3].toLong() and 0xFF) shl 24)
            i += 4
        }
        if (words.size % 2 != 0) words += 0L
        return words.toLongArray()
    }

    private fun be(data: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        for (i in 0 until len) v = (v shl 8) or (data[off + i].toLong() and 0xFF)
        return v
    }

    private fun readLe32(data: ByteArray, off: Int): Long =
        (data[off].toLong() and 0xFF) or
            ((data[off + 1].toLong() and 0xFF) shl 8) or
            ((data[off + 2].toLong() and 0xFF) shl 16) or
            ((data[off + 3].toLong() and 0xFF) shl 24)

    private fun hexLe32(v: Long): String = String.format(
        java.util.Locale.ROOT,
        "%02X%02X%02X%02X",
        (v and 0xFF).toInt(),
        ((v ushr 8) and 0xFF).toInt(),
        ((v ushr 16) and 0xFF).toInt(),
        ((v ushr 24) and 0xFF).toInt(),
    )

    private fun ByteArray.toHex(): String =
        joinToString("") { String.format(java.util.Locale.ROOT, "%02X", it) }
}
