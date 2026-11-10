package com.remote.androidtv.protocol

import java.io.IOException

/** One infrared transmission: carrier frequency and alternating on/off times in microseconds. */
class IrSignal(val frequencyHz: Int, val pattern: IntArray)

/**
 * Infrared codes of the TV brands whose remotes all share one code set.
 * Used with the phone's IR emitter, for TVs that are not on Wi-Fi.
 */
enum class IrCodeSet(val label: String, private val codes: Map<RemoteKey, Int>, private val encode: (Int) -> IrSignal) {
    SAMSUNG(
        "Samsung",
        mapOf(
            RemoteKey.POWER to 0xE0E040BF.toInt(),
            RemoteKey.INPUT to 0xE0E0807F.toInt(),
            RemoteKey.VOLUME_UP to 0xE0E0E01F.toInt(),
            RemoteKey.VOLUME_DOWN to 0xE0E0D02F.toInt(),
            RemoteKey.MUTE to 0xE0E0F00F.toInt(),
            RemoteKey.CHANNEL_UP to 0xE0E048B7.toInt(),
            RemoteKey.CHANNEL_DOWN to 0xE0E008F7.toInt(),
            RemoteKey.SETTINGS to 0xE0E058A7.toInt(),
            RemoteKey.MENU to 0xE0E0D22D.toInt(),
            RemoteKey.HOME to 0xE0E09E61.toInt(),
            RemoteKey.UP to 0xE0E006F9.toInt(),
            RemoteKey.DOWN to 0xE0E08679.toInt(),
            RemoteKey.LEFT to 0xE0E0A659.toInt(),
            RemoteKey.RIGHT to 0xE0E046B9.toInt(),
            RemoteKey.OK to 0xE0E016E9.toInt(),
            RemoteKey.BACK to 0xE0E01AE5.toInt(),
            RemoteKey.REWIND to 0xE0E0A25D.toInt(),
            RemoteKey.PLAY_PAUSE to 0xE0E0E21D.toInt(),
            RemoteKey.FAST_FORWARD to 0xE0E012ED.toInt(),
        ),
        { IrEncoder.pulseDistance(it, headerMark = 4500, headerSpace = 4500) },
    ),
    LG(
        "LG",
        mapOf(
            RemoteKey.POWER to 0x20DF10EF,
            RemoteKey.INPUT to 0x20DFD02F,
            RemoteKey.VOLUME_UP to 0x20DF40BF,
            RemoteKey.VOLUME_DOWN to 0x20DFC03F,
            RemoteKey.MUTE to 0x20DF906F,
            RemoteKey.CHANNEL_UP to 0x20DF00FF,
            RemoteKey.CHANNEL_DOWN to 0x20DF807F,
            RemoteKey.SETTINGS to 0x20DFC23D,
            RemoteKey.MENU to 0x20DFA25D,
            RemoteKey.HOME to 0x20DF3EC1,
            RemoteKey.UP to 0x20DF02FD,
            RemoteKey.DOWN to 0x20DF827D,
            RemoteKey.LEFT to 0x20DFE01F,
            RemoteKey.RIGHT to 0x20DF609F,
            RemoteKey.OK to 0x20DF22DD,
            RemoteKey.BACK to 0x20DF14EB,
            RemoteKey.REWIND to 0x20DFF10E,
            RemoteKey.PLAY_PAUSE to 0x20DF0DF2,
            RemoteKey.FAST_FORWARD to 0x20DF718E,
        ),
        { IrEncoder.pulseDistance(it, headerMark = 9000, headerSpace = 4500) },
    ),
    SONY(
        "Sony",
        mapOf(
            RemoteKey.POWER to 0xA90,
            RemoteKey.INPUT to 0xA50,
            RemoteKey.VOLUME_UP to 0x490,
            RemoteKey.VOLUME_DOWN to 0xC90,
            RemoteKey.MUTE to 0x290,
            RemoteKey.CHANNEL_UP to 0x090,
            RemoteKey.CHANNEL_DOWN to 0x890,
            RemoteKey.HOME to 0x070,
            RemoteKey.SETTINGS to 0x070,
            RemoteKey.MENU to 0x36E9,
            RemoteKey.UP to 0x2F0,
            RemoteKey.DOWN to 0xAF0,
            RemoteKey.LEFT to 0x2D0,
            RemoteKey.RIGHT to 0xCD0,
            RemoteKey.OK to 0xA70,
            RemoteKey.BACK to 0x62E9,
            RemoteKey.REWIND to 0x6CE9,
            RemoteKey.PLAY_PAUSE to 0x2CE9,
            RemoteKey.FAST_FORWARD to 0x1CE9,
        ),
        // Sony codes above 12 bits are the 15-bit kind.
        { IrEncoder.sony(it, bits = if (it > 0xFFF) 15 else 12) },
    );

    /** The pause code, for brands whose remotes have separate play and pause buttons. */
    private val pauseCode: Int?
        get() = when (this) {
            SAMSUNG -> 0xE0E052AD.toInt()
            LG -> 0x20DF5DA2
            SONY -> 0x4CE9
        }

    fun signal(key: RemoteKey): IrSignal? = codes[key]?.let(encode)
    fun pauseSignal(): IrSignal? = pauseCode?.let(encode)
}

object IrEncoder {
    /**
     * NEC-style frame (used by LG, Samsung and many others): header, then 32 bits sent
     * most significant first, each a short mark followed by a short (0) or long (1) space.
     */
    fun pulseDistance(code: Int, headerMark: Int, headerSpace: Int): IrSignal {
        val p = ArrayList<Int>(68)
        p += headerMark
        p += headerSpace
        for (bit in 31 downTo 0) {
            p += 560
            p += if ((code ushr bit) and 1 == 1) 1690 else 560
        }
        p += 560
        p += 40_000 // quiet gap, so two presses in a row stay separate
        return IrSignal(38_000, p.toIntArray())
    }

    /** Sony SIRC: the bit is in the length of the mark, and a frame must be sent three times. */
    fun sony(code: Int, bits: Int): IrSignal {
        val frame = ArrayList<Int>()
        frame += 2400
        frame += 600
        for (bit in bits - 1 downTo 0) {
            frame += if ((code ushr bit) and 1 == 1) 1200 else 600
            frame += 600
        }
        // Frames start every 45 ms: stretch the last space to fill the rest.
        frame[frame.size - 1] = 45_000 - frame.dropLast(1).sum()
        return IrSignal(40_000, (frame + frame + frame).toIntArray())
    }
}

/** A TV controlled through an infrared emitter. [transmit] sends one signal. */
class IrController(
    private val codeSet: IrCodeSet,
    private val transmit: (IrSignal) -> Unit,
) : TvController {
    private var playing = false
    private var closed = false

    /** Infrared is one-way: we are "connected" as long as the emitter is there. */
    override val isConnected: Boolean get() = !closed
    override val supportsHold: Boolean get() = false

    override fun press(key: RemoteKey, action: KeyAction) {
        if (action == KeyAction.RELEASE) return
        if (closed) throw IOException("Not connected")
        val signal = if (key == RemoteKey.PLAY_PAUSE) {
            playing = !playing
            if (playing) codeSet.signal(key) else codeSet.pauseSignal() ?: codeSet.signal(key)
        } else {
            codeSet.signal(key)
        }
        signal?.let(transmit)
    }

    /** Apps cannot be opened over infrared. */
    override fun launch(app: TvApp) {}

    override fun close() {
        closed = true
    }
}
