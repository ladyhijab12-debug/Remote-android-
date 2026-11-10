package com.remote.androidtv.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrRemoteTest {

    /** Reads a pulse-distance frame back into its 32-bit code. */
    private fun decode(signal: IrSignal): Int {
        var code = 0
        for (i in 0 until 32) {
            assertEquals(560, signal.pattern[2 + i * 2])
            code = (code shl 1) or (if (signal.pattern[3 + i * 2] > 1000) 1 else 0)
        }
        return code
    }

    @Test fun necFrame() {
        val s = IrCodeSet.LG.signal(RemoteKey.POWER)!!
        assertEquals(38_000, s.frequencyHz)
        assertEquals(68, s.pattern.size) // header, 32 bits, stop mark and gap
        assertEquals(9000, s.pattern[0])
        assertEquals(4500, s.pattern[1])
        assertEquals(0x20DF10EF, decode(s))
    }

    @Test fun samsungFrame() {
        val s = IrCodeSet.SAMSUNG.signal(RemoteKey.VOLUME_UP)!!
        assertEquals(4500, s.pattern[0])
        assertEquals(4500, s.pattern[1])
        assertEquals(0xE0E0E01F.toInt(), decode(s))
    }

    /** In these code sets a command byte is always followed by its complement. */
    @Test fun commandsCarryTheirComplement() {
        for (set in listOf(IrCodeSet.SAMSUNG, IrCodeSet.LG)) {
            for (key in RemoteKey.values()) {
                val code = decode(set.signal(key) ?: continue)
                assertEquals("$set $key", 0xFF, ((code ushr 8) and 0xFF) xor (code and 0xFF))
            }
            val pause = decode(set.pauseSignal()!!)
            assertEquals("$set pause", 0xFF, ((pause ushr 8) and 0xFF) xor (pause and 0xFF))
        }
    }

    @Test fun sonyFrameIsSentThreeTimes() {
        val s = IrCodeSet.SONY.signal(RemoteKey.POWER)!! // 0xA90 = 1010 1001 0000
        assertEquals(40_000, s.frequencyHz)
        assertEquals(3 * 26, s.pattern.size)
        assertArrayEquals(intArrayOf(2400, 600, 1200, 600, 600, 600, 1200, 600, 600, 600), s.pattern.copyOf(10))
        assertEquals(45_000, s.pattern.copyOf(26).sum())
        assertArrayEquals(s.pattern.copyOf(26), s.pattern.copyOfRange(26, 52))
        // 15-bit codes
        assertEquals(3 * 32, IrCodeSet.SONY.signal(RemoteKey.BACK)!!.pattern.size)
    }

    @Test fun everyKeyHasACodeAndTimesArePositive() {
        for (set in IrCodeSet.values()) for (key in RemoteKey.values()) {
            val s = set.signal(key)
            assertNotNull("$set $key", s)
            assertTrue("$set $key", s!!.pattern.all { it > 0 })
        }
    }

    @Test fun controllerTransmitsAndAlternatesPlayPause() {
        val sent = mutableListOf<IrSignal>()
        val c = IrController(IrCodeSet.LG) { sent += it }
        assertTrue(c.isConnected)
        c.press(RemoteKey.MUTE)
        c.press(RemoteKey.MUTE, KeyAction.RELEASE)
        assertEquals(listOf(0x20DF906F), sent.map(::decode))
        c.press(RemoteKey.PLAY_PAUSE)
        c.press(RemoteKey.PLAY_PAUSE)
        assertEquals(listOf(0x20DF0DF2, 0x20DF5DA2), sent.drop(1).map(::decode))
    }
}
