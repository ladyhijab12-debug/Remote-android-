package com.remote.androidtv.protocol

import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The TV did not accept our key: the user did not answer (or refused) the "Allow debugging?" dialog. */
class AdbNotAllowedException : IOException("TV did not allow debugging")

/**
 * Minimal ADB client over TCP (TV: Developer options › USB debugging + network debugging, port 5555).
 * Only what is needed to run shell commands: CNXN, AUTH, OPEN, WRTE, OKAY, CLSE.
 * Methods block: call them off the main thread.
 */
class AdbClient(
    private val host: String,
    private val key: AdbKey,
    private val keyName: String,
    private val port: Int = DEFAULT_PORT,
) : Closeable {

    private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream
    private var nextLocalId = 1

    val isConnected: Boolean get() = socket?.isClosed == false

    /**
     * Connects and authenticates. The first time, the TV shows "Allow debugging?":
     * [onWaitingForUser] fires and we wait up to [userTimeoutMs] for the user to accept.
     */
    fun connect(timeoutMs: Int = 5000, userTimeoutMs: Int = 60_000, onWaitingForUser: () -> Unit = {}) {
        close()
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            s.tcpNoDelay = true
            socket = s
            input = DataInputStream(s.getInputStream().buffered())
            output = s.getOutputStream()
            handshake(s, timeoutMs, userTimeoutMs, onWaitingForUser)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private fun handshake(s: Socket, timeoutMs: Int, userTimeoutMs: Int, onWaitingForUser: () -> Unit) {
        send(CNXN, VERSION, MAX_DATA, "host::\u0000".toByteArray())
        var sentSignature = false
        while (true) {
            val m = read()
            when (m.command) {
                CNXN -> { s.soTimeout = timeoutMs; return }
                AUTH -> {
                    if (m.arg0 != AUTH_TOKEN) throw IOException("Unexpected AUTH type ${m.arg0}")
                    if (!sentSignature) {
                        sentSignature = true
                        send(AUTH, AUTH_SIGNATURE, 0, key.sign(m.data))
                    } else {
                        // TV does not know our key yet: offer it; the TV asks the user.
                        send(AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyPayload(keyName))
                        onWaitingForUser()
                        s.soTimeout = userTimeoutMs
                        val answer = try { read() } catch (e: SocketTimeoutException) {
                            throw AdbNotAllowedException()
                        }
                        if (answer.command == CNXN) { s.soTimeout = timeoutMs; return }
                        throw AdbNotAllowedException()
                    }
                }
                else -> throw IOException("Unexpected ADB message 0x${m.command.toString(16)}")
            }
        }
    }

    /** Runs a shell command on the TV and returns everything it printed. */
    @Synchronized
    fun shell(command: String): String {
        val localId = nextLocalId++
        send(OPEN, localId, 0, "shell:$command\u0000".toByteArray())
        val out = StringBuilder()
        var remoteId = 0
        while (true) {
            val m = read()
            if (m.arg1 != localId) continue // belongs to an older stream
            when (m.command) {
                OKAY -> remoteId = m.arg0
                WRTE -> {
                    remoteId = m.arg0
                    out.append(String(m.data, Charsets.UTF_8))
                    send(OKAY, localId, remoteId)
                }
                CLSE -> {
                    if (remoteId != 0) send(CLSE, localId, remoteId)
                    return out.toString()
                }
            }
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1)
            .putInt(data.size).putInt(checksum(data)).putInt(command.inv())
            .array()
        output.write(header + data)
        output.flush()
    }

    private fun read(): Message {
        val header = ByteArray(HEADER_SIZE)
        input.readFully(header)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        b.int // checksum: TCP already guarantees integrity
        if (b.int != command.inv()) throw IOException("Bad ADB header")
        if (length < 0 || length > MAX_DATA_ACCEPTED) throw IOException("ADB message too large: $length")
        val data = ByteArray(length).also { input.readFully(it) }
        return Message(command, arg0, arg1, data)
    }

    companion object {
        const val DEFAULT_PORT = 5555

        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val CLSE = 0x45534c43
        const val WRTE = 0x45545257

        const val AUTH_TOKEN = 1
        const val AUTH_SIGNATURE = 2
        const val AUTH_RSAPUBLICKEY = 3

        private const val VERSION = 0x01000000
        private const val MAX_DATA = 4096
        private const val MAX_DATA_ACCEPTED = 1024 * 1024
        const val HEADER_SIZE = 24

        fun checksum(data: ByteArray): Int = data.sumOf { it.toInt() and 0xff }
    }
}
