package com.remote.androidtv.protocol

import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * The app's ADB key. The TV asks "Allow debugging?" once for this key; with "Always allow"
 * ticked it remembers it, so the key is generated once and kept in the app's private storage.
 */
class AdbKey(private val dir: File) {

    private val keyPair: KeyPair by lazy { loadOrCreate() }

    /** ADB signs the TV's 20-byte token as if it were a SHA-1 digest (PKCS#1 v1.5). */
    fun sign(token: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(SHA1_DIGEST_INFO + token)
    }

    /** Public key in the format adbd expects: base64 of Android's RSAPublicKey struct, a name, and a NUL. */
    fun publicKeyPayload(name: String): ByteArray {
        val encoded = Base64.getEncoder().encodeToString(encodePublicKey(keyPair.public as RSAPublicKey))
        return "$encoded $name\u0000".toByteArray(Charsets.UTF_8)
    }

    private fun loadOrCreate(): KeyPair {
        val priv = File(dir, "adbkey")
        val pub = File(dir, "adbkey.pub.der")
        if (priv.exists() && pub.exists()) {
            runCatching {
                val kf = KeyFactory.getInstance("RSA")
                return KeyPair(
                    kf.generatePublic(X509EncodedKeySpec(pub.readBytes())),
                    kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())),
                )
            }
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
        dir.mkdirs()
        priv.writeBytes(kp.private.encoded)
        pub.writeBytes(kp.public.encoded)
        return kp
    }

    companion object {
        private const val KEY_BITS = 2048
        private const val WORDS = KEY_BITS / 32

        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        /** struct { u32 len; u32 n0inv; u32 n[64]; u32 rr[64]; u32 exponent; }, all little-endian. */
        fun encodePublicKey(key: RSAPublicKey): ByteArray {
            val n = key.modulus
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
            val rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n)
            val buf = ByteBuffer.allocate(4 * (3 + 2 * WORDS)).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(WORDS)
            buf.putInt(n0inv)
            putWords(buf, n)
            putWords(buf, rr)
            buf.putInt(key.publicExponent.toInt())
            return buf.array()
        }

        private fun putWords(buf: ByteBuffer, value: BigInteger) {
            val mask = BigInteger.valueOf(0xffffffffL)
            var v = value
            repeat(WORDS) {
                buf.putInt(v.and(mask).toLong().toInt())
                v = v.shiftRight(32)
            }
        }
    }
}
