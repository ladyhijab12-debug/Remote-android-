package com.remote.androidtv.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import kotlin.concurrent.thread

class FakeAdbTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Simulated adbd: knows no key at first, accepts the public key it is offered, then runs shell commands. */
    private class FakeAdbd(private val outputs: Map<String, String>) {
        val server = ServerSocket(0)
        val commands = CopyOnWriteArrayList<String>()
        var acceptedKey: RSAPublicKey? = null
        val token = ByteArray(20) { it.toByte() }

        fun start(knownKey: RSAPublicKey? = null) = thread(isDaemon = true) {
            server.accept().use { s ->
                val input = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                assertEquals(AdbClient.CNXN, read(input).command)
                send(out, AdbClient.AUTH, AdbClient.AUTH_TOKEN, 0, token)
                val sig = read(input)
                assertEquals(AdbClient.AUTH_SIGNATURE, sig.arg0)
                if (knownKey == null || !verify(knownKey, sig.data)) {
                    send(out, AdbClient.AUTH, AdbClient.AUTH_TOKEN, 0, token)
                    val pub = read(input)
                    assertEquals(AdbClient.AUTH_RSAPUBLICKEY, pub.arg0)
                    acceptedKey = decodeKey(pub.data)
                }
                send(out, AdbClient.CNXN, 0x01000000, 4096, "device::".toByteArray())
                var remote = 100
                while (true) {
                    val m = runCatching { read(input) }.getOrNull() ?: return@use
                    if (m.command != AdbClient.OPEN) continue
                    val cmd = String(m.data).trimEnd('\u0000').removePrefix("shell:")
                    commands += cmd
                    val id = remote++
                    send(out, AdbClient.OKAY, id, m.arg0)
                    val text = outputs.entries.firstOrNull { cmd.startsWith(it.key) }?.value ?: ""
                    if (text.isNotEmpty()) {
                        send(out, AdbClient.WRTE, id, m.arg0, text.toByteArray())
                        assertEquals(AdbClient.OKAY, read(input).command)
                    }
                    send(out, AdbClient.CLSE, id, m.arg0)
                    assertEquals(AdbClient.CLSE, read(input).command)
                }
            }
        }

        /** Same check adbd does: RSA-decrypt the signature and compare with DigestInfo(SHA-1) + token. */
        fun verify(key: RSAPublicKey, sig: ByteArray): Boolean = runCatching {
            val c = Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.DECRYPT_MODE, key) }
            c.doFinal(sig).takeLast(20).toByteArray().contentEquals(token)
        }.getOrDefault(false)

        /** Parses Android's RSAPublicKey struct and checks n0inv / rr are consistent with n. */
        fun decodeKey(payload: ByteArray): RSAPublicKey {
            val b64 = String(payload).trimEnd('\u0000').substringBefore(' ')
            val b = ByteBuffer.wrap(Base64.getDecoder().decode(b64)).order(ByteOrder.LITTLE_ENDIAN)
            val words = b.int
            val n0inv = b.int
            fun big(): BigInteger {
                var v = BigInteger.ZERO
                for (i in 0 until words) {
                    v = v.or(BigInteger.valueOf(b.int.toLong() and 0xffffffffL).shiftLeft(32 * i))
                }
                return v
            }
            val n = big()
            val rr = big()
            val e = b.int
            val r32 = BigInteger.ONE.shiftLeft(32)
            assertEquals(BigInteger.ZERO, n.multiply(BigInteger.valueOf(n0inv.toLong() and 0xffffffffL)).add(BigInteger.ONE).mod(r32))
            assertEquals(BigInteger.ONE.shiftLeft(words * 64).mod(n), rr)
            return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, BigInteger.valueOf(e.toLong()))) as RSAPublicKey
        }

        class Msg(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

        fun read(input: DataInputStream): Msg {
            val h = ByteArray(24).also { input.readFully(it) }
            val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int; val crc = b.int; val magic = b.int
            assertEquals(cmd.inv(), magic)
            val data = ByteArray(len).also { input.readFully(it) }
            assertEquals(AdbClient.checksum(data), crc)
            return Msg(cmd, a0, a1, data)
        }

        fun send(out: OutputStream, cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
            val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(AdbClient.checksum(data)).putInt(cmd.inv())
            out.write(h.array() + data)
            out.flush()
        }
    }

    @Test fun firstConnectOffersKeyThenSignatureIsAccepted() {
        val keyDir = tmp.newFolder()
        val first = FakeAdbd(mapOf("echo" to "hi\n"))
        first.start()
        var asked = false
        val c = AdbClient("127.0.0.1", AdbKey(keyDir), "test@phone", first.server.localPort)
        c.connect(userTimeoutMs = 5000) { asked = true }
        assertTrue(asked)
        assertEquals("hi\n", c.shell("echo hi"))
        c.close()

        // Second time the TV knows the key: a valid signature is enough, no dialog.
        val second = FakeAdbd(emptyMap())
        second.start(knownKey = first.acceptedKey)
        asked = false
        val c2 = AdbClient("127.0.0.1", AdbKey(keyDir), "test@phone", second.server.localPort)
        c2.connect(userTimeoutMs = 5000) { asked = true }
        assertTrue("TV should not ask again", !asked)
        c2.close()
    }

    @Test fun toolsCleanAndChangeLauncher() {
        val df = listOf(
            "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-5 8000000 6000000 2000000 75% /data\n",
            "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/dm-5 8000000 5500000 2500000 69% /data\n",
        ).iterator()
        val ran = mutableListOf<String>()
        val tools = TvTools { cmd ->
            ran += cmd
            when {
                cmd.startsWith("df") -> df.next()
                cmd.contains("query-activities") -> """
                    3 activities found:
                      com.google.android.apps.tv.launcherx/.home.HomeActivity
                      com.spocky.projengmenu/.ui.home.MainActivity
                      com.android.tv.settings/.FallbackHome
                """.trimIndent()
                cmd.contains("resolve-activity") -> "priority=0 preferredOrder=0\ncom.google.android.apps.tv.launcherx/.home.HomeActivity"
                else -> ""
            }
        }
        assertEquals(500000L * 1024, tools.clean())
        assertTrue(ran.any { it.startsWith("pm trim-caches") })

        val launchers = tools.launchers()
        assertEquals(listOf("Google TV (الأصلية)", "Projectivy Launcher"), launchers.map { it.label })
        assertTrue(launchers[0].isCurrent && launchers[0].isStock)

        ran.clear()
        tools.setLauncher(launchers[1], disableStock = true)
        assertTrue(ran.contains("pm disable-user --user 0 'com.google.android.apps.tv.launcherx'"))
        assertTrue(ran.contains("cmd package set-home-activity 'com.spocky.projengmenu/.ui.home.MainActivity'"))
        assertEquals("input keyevent 3", ran.last())
    }

    @Test fun parsesComponentsAndDf() {
        assertArrayEquals(
            arrayOf("a.b/.C", "x.y/z.W"),
            TvTools.parseComponents("2 activities found:\n  a.b/.C\n  x.y/z.W\n").toTypedArray(),
        )
        assertEquals(null, TvTools.parseDfAvailable("df: /data: Permission denied"))
    }
}
