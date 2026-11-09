package com.remote.androidtv.protocol

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Plays the TV side of the protocol over real TLS sockets on localhost. */
class FakeTvTest {
    private lateinit var dir: File
    private lateinit var phone: CertificateManager
    private lateinit var tv: CertificateManager
    private val errors = ArrayBlockingQueue<Throwable>(10)

    @Before fun setUp() {
        dir = Files.createTempDirectory("atv").toFile()
        phone = CertificateManager(File(dir, "phone.p12"))
        tv = CertificateManager(File(dir, "tv.p12"))
        tv.certificate // force creation
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        errors.poll()?.let { throw AssertionError("TV side failed", it) }
    }

    private fun server(acceptClient: Boolean = true): SSLServerSocket {
        val ks = KeyStore.getInstance("PKCS12")
        File(dir, "tv.p12").inputStream().use { ks.load(it, "atvremote".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "atvremote".toCharArray())
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {
                if (!acceptClient) throw CertificateException("unknown client")
            }
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, arrayOf(tm), null) }
        return (ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply { needClientAuth = true }
    }

    private fun tvThread(body: () -> Unit) = Thread {
        try { body() } catch (e: Throwable) { errors.add(e) }
    }.apply { isDaemon = true; start() }

    private fun SSLSocket.read() = ProtoMessage.parse(Framing.read(inputStream))
    private fun SSLSocket.write(block: ProtoWriter.() -> Unit) = Framing.write(outputStream, ProtoWriter().apply(block).toByteArray())

    @Test fun pairingFlow() {
        val ss = server()
        val codes = ArrayBlockingQueue<String>(1)
        tvThread {
            val s = ss.accept() as SSLSocket
            val req = s.read()
            assertEquals(2L, req.varint(1))
            assertEquals("atvremote", req.message(10)!!.string(1))
            assertEquals("Test phone", req.message(10)!!.string(2))
            s.write { varint(1, 2); varint(2, 200); message(11) { string(1, "Fake TV") } }
            val opts = s.read().message(20)!!
            assertEquals(3L, opts.message(1)!!.varint(1))
            assertEquals(6L, opts.message(1)!!.varint(2))
            s.write { varint(1, 2); varint(2, 200); message(20) { varint(3, 1) } }
            assertTrue(s.read().has(30))
            s.write { varint(1, 2); varint(2, 200); message(31) {} }

            // What a real TV does to produce the on-screen code.
            val clientKey = s.session.peerCertificates.first().publicKey as RSAPublicKey
            val serverKey = tv.publicKey
            val nonce = byteArrayOf(0x3c, 0x7a)
            val md = MessageDigest.getInstance("SHA-256")
            listOf(clientKey.modulus, clientKey.publicExponent, serverKey.modulus, serverKey.publicExponent)
                .forEach { md.update(it.toByteArray().let { b -> if (b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b }) }
            md.update(nonce)
            val hash = md.digest()
            codes.add("%02x3c7a".format(hash[0].toInt() and 0xFF))

            assertArrayEquals(hash, s.read().message(40)!!.bytes(1))
            s.write { varint(1, 2); varint(2, 200); message(41) { bytes(1, hash) } }
            s.close()
        }

        PairingClient("127.0.0.1", phone, "Test phone", ss.localPort).use { p ->
            p.start()
            val code = codes.poll(5, TimeUnit.SECONDS)!!
            // A typo is caught locally and the user can retry on the same connection.
            val typo = (if (code[0] == '0') "1" else "0") + code.substring(1)
            try { p.finish(typo); fail("typo accepted") } catch (_: WrongCodeException) {}
            p.finish(" $code ")
        }
        ss.close()
    }

    @Test fun remoteSession() {
        val ss = server()
        val keys = ArrayBlockingQueue<ProtoMessage>(5)
        tvThread {
            val s = ss.accept() as SSLSocket
            s.write { message(1) { varint(1, 639) } }
            val cfg = s.read().message(1)!!
            assertEquals("Pixel", cfg.message(2)!!.string(1))
            assertEquals("atvremote", cfg.message(2)!!.string(5))
            s.write { message(2) { varint(1, 639) } }
            assertTrue(s.read().has(2))
            s.write { message(8) { varint(1, 42) } }
            assertEquals(42L, s.read().message(9)!!.varint(1))
            s.write { message(50) { varint(6, 100); varint(7, 30); varint(8, 0) } }
            keys.add(s.read())
            keys.add(s.read())
            s.close()
        }

        val volume = ArrayBlockingQueue<Int>(1)
        val disconnected = ArrayBlockingQueue<String>(1)
        val client = RemoteClient("127.0.0.1", phone, DeviceInfo("Pixel", "Google"), object : RemoteClient.Listener {
            override fun onVolumeChanged(level: Int, max: Int, muted: Boolean) { volume.add(level) }
            override fun onDisconnected(error: Throwable?) { disconnected.add("x") }
        }, ss.localPort)
        client.connect()
        assertTrue(client.isConnected)
        assertEquals(30, volume.poll(5, TimeUnit.SECONDS))
        client.sendKey(TvKeys.DPAD_CENTER)
        client.launchApp("https://www.youtube.com")

        val key = keys.poll(5, TimeUnit.SECONDS)!!.message(10)!!
        assertEquals(23L, key.varint(1))
        assertEquals(3L, key.varint(2))
        assertEquals("https://www.youtube.com", keys.poll(5, TimeUnit.SECONDS)!!.message(90)!!.string(1))
        assertEquals("x", disconnected.poll(5, TimeUnit.SECONDS))
        client.close()
        ss.close()
    }

    @Test fun unpairedTvIsReported() {
        val ss = server(acceptClient = false)
        tvThread {
            val s = ss.accept() as SSLSocket
            runCatching { s.startHandshake(); s.inputStream.read() }
            s.close()
        }
        val client = RemoteClient("127.0.0.1", phone, DeviceInfo("Pixel", "Google"), object : RemoteClient.Listener {}, ss.localPort)
        try { client.connect(); fail("connected without pairing") } catch (_: NotPairedException) {}
        ss.close()
    }

    @Test fun certificateIsReused() {
        val again = CertificateManager(File(dir, "phone.p12"))
        assertEquals(phone.publicKey.modulus, again.publicKey.modulus)
        assertNull(PairingClient.normalizeCode("12345G"))
        assertEquals("ABC123", PairingClient.normalizeCode("abc 123"))
    }
}
