package com.remote.androidtv.protocol

import java.io.Closeable
import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

class PairingException(message: String) : IOException(message)

/** The code typed does not match this TV / connection. Safe to ask the user again. */
class WrongCodeException : IOException("Wrong pairing code")

/**
 * Pairing on port 6467. Call [start] (the TV then shows a 6-character code),
 * then [finish] with the code the user typed.
 */
class PairingClient(
    private val host: String,
    private val certs: CertificateManager,
    private val clientName: String,
    private val port: Int = PAIRING_PORT,
) : Closeable {

    private var socket: SSLSocket? = null

    fun start() {
        val s = certs.connect(host, port, timeoutMs = 10_000)
        socket = s
        send { message(10) { string(1, SERVICE_NAME); string(2, clientName) } }
        expect(11)
        send {
            message(20) {
                message(1) { varint(1, ENCODING_HEXADECIMAL); varint(2, CODE_LENGTH) }
                varint(3, ROLE_INPUT)
            }
        }
        expect(20)
        send {
            message(30) {
                message(1) { varint(1, ENCODING_HEXADECIMAL); varint(2, CODE_LENGTH) }
                varint(2, ROLE_INPUT)
            }
        }
        expect(31)
        // The code is now on the TV screen; give the user time to type it.
        s.soTimeout = 0
    }

    fun finish(code: String) {
        val s = socket ?: throw IllegalStateException("start() not called")
        val serverKey = s.session.peerCertificates.first().publicKey as RSAPublicKey
        val secret = computeSecret(certs.publicKey, serverKey, code) ?: throw WrongCodeException()
        s.soTimeout = 10_000
        send { message(40) { bytes(1, secret) } }
        expect(41)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun send(block: ProtoWriter.() -> Unit) {
        val payload = ProtoWriter().apply {
            varint(1, PROTOCOL_VERSION)
            varint(2, STATUS_OK)
            block()
        }.toByteArray()
        Framing.write(socket!!.outputStream, payload)
    }

    private fun expect(field: Int): ProtoMessage {
        val msg = ProtoMessage.parse(Framing.read(socket!!.inputStream))
        when (val status = msg.varint(2)?.toInt()) {
            STATUS_OK -> Unit
            STATUS_BAD_SECRET -> throw WrongCodeException()
            else -> throw PairingException("TV rejected pairing (status $status)")
        }
        if (!msg.has(field)) throw PairingException("Unexpected reply from TV")
        return msg
    }

    companion object {
        const val PAIRING_PORT = 6467
        const val CODE_LENGTH = 6

        private const val SERVICE_NAME = "atvremote"
        private const val PROTOCOL_VERSION = 2
        private const val STATUS_OK = 200
        private const val STATUS_BAD_SECRET = 402
        private const val ENCODING_HEXADECIMAL = 3
        private const val ROLE_INPUT = 1

        /** Normalises what the user typed; null if it cannot be a pairing code. */
        fun normalizeCode(code: String): String? {
            val c = code.filterNot { it.isWhitespace() }.uppercase()
            return c.takeIf { it.length == CODE_LENGTH && it.all { ch -> ch in '0'..'9' || ch in 'A'..'F' } }
        }

        /**
         * secret = SHA-256(client modulus, client exponent, server modulus, server exponent, code[2:]).
         * The first byte of the hash must equal code[0:2], which lets us reject typos locally.
         * Returns null when the code does not match.
         */
        fun computeSecret(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray? {
            val c = normalizeCode(code) ?: return null
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(client.modulus.unsignedBytes())
            digest.update(client.publicExponent.unsignedBytes())
            digest.update(server.modulus.unsignedBytes())
            digest.update(server.publicExponent.unsignedBytes())
            digest.update(c.substring(2).hexToBytes())
            val hash = digest.digest()
            return hash.takeIf { (it[0].toInt() and 0xFF) == c.substring(0, 2).toInt(16) }
        }

        private fun BigInteger.unsignedBytes(): ByteArray {
            val b = toByteArray()
            return if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
        }

        private fun String.hexToBytes(): ByteArray =
            ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }
}
