package com.remote.adblocker.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsPacketTest {

    private fun dnsQuery(name: String, type: Int, id: Int = 0x1234): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.split('.')) {
            out.write(label.length)
            out.write(label.toByteArray())
        }
        out.write(byteArrayOf(0, (type shr 8).toByte(), type.toByte(), 0, 1))
        return out.toByteArray()
    }

    private fun udpPacket(payload: ByteArray, destinationPort: Int = 53, protocol: Int = 17): ByteArray {
        val total = 28 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[8] = 64
        p[9] = protocol.toByte()
        byteArrayOf(10, 111, 222.toByte(), 1).copyInto(p, 12)
        byteArrayOf(10, 111, 222.toByte(), 2).copyInto(p, 16)
        p[20] = 0xC3.toByte(); p[21] = 0x50 // source port 50000
        p[22] = (destinationPort shr 8).toByte(); p[23] = destinationPort.toByte()
        val udpLength = 8 + payload.size
        p[24] = (udpLength shr 8).toByte(); p[25] = udpLength.toByte()
        payload.copyInto(p, 28)
        return p
    }

    @Test
    fun parsesQuery() {
        val packet = udpPacket(dnsQuery("Ads.Example.COM", DnsPacket.TYPE_A))
        // The tunnel buffer is larger than the packet.
        val buffer = packet.copyOf(4096)
        val query = DnsPacket.parse(buffer, packet.size)
        assertNotNull(query)
        assertEquals("ads.example.com", query!!.name)
        assertEquals(DnsPacket.TYPE_A, query.type)
        assertEquals(50000, query.sourcePort)
        assertEquals(53, query.destinationPort)
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 1), query.source)
    }

    @Test
    fun ignoresEverythingThatIsNotADnsQuery() {
        val dns = dnsQuery("example.com", DnsPacket.TYPE_A)
        assertNull(DnsPacket.parse(udpPacket(dns, destinationPort = 443), 28 + dns.size))
        assertNull(DnsPacket.parse(udpPacket(dns, protocol = 6), 28 + dns.size))
        val response = dns.clone().also { it[2] = 0x81.toByte() }
        assertNull(DnsPacket.parse(udpPacket(response), 28 + response.size))
        val ipv6 = udpPacket(dns).also { it[0] = 0x60 }
        assertNull(DnsPacket.parse(ipv6, ipv6.size))
        val truncated = udpPacket(dns)
        assertNull(DnsPacket.parse(truncated, truncated.size - 6))
        assertNull(DnsPacket.parse(ByteArray(10), 10))
    }

    @Test
    fun rejectsMalformedNames() {
        val dns = dnsQuery("example.com", DnsPacket.TYPE_A)
        dns[12] = 0xC0.toByte() // compression pointer where a label should be
        assertNull(DnsPacket.parse(udpPacket(dns), 28 + dns.size))
        val overrun = dnsQuery("example.com", DnsPacket.TYPE_A)
        overrun[12] = 60 // label longer than the message
        assertNull(DnsPacket.parse(udpPacket(overrun), 28 + overrun.size))
    }

    @Test
    fun blockedResponseForA() {
        val dns = dnsQuery("ads.example.com", DnsPacket.TYPE_A, id = 0xBEEF)
        val query = DnsPacket.parse(udpPacket(dns), 28 + dns.size)!!
        val response = DnsPacket.blockedResponse(query)
        assertEquals(dns.size + 16, response.size)
        assertEquals(0xBE.toByte(), response[0]); assertEquals(0xEF.toByte(), response[1])
        assertEquals(0x81.toByte(), response[2]) // response + recursion desired
        assertEquals(0x80.toByte(), response[3]) // recursion available, NOERROR
        assertEquals(1, response[7].toInt())     // one answer
        assertArrayEquals(dns.copyOfRange(12, dns.size), response.copyOfRange(12, dns.size))
        val answer = response.copyOfRange(dns.size, response.size)
        assertArrayEquals(
            byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 0, 0, 0, 0),
            answer,
        )
    }

    @Test
    fun blockedResponseForAaaaAndOtherTypes() {
        val aaaa = dnsQuery("ads.example.com", DnsPacket.TYPE_AAAA)
        val aaaaResponse = DnsPacket.blockedResponse(DnsPacket.parse(udpPacket(aaaa), 28 + aaaa.size)!!)
        assertEquals(aaaa.size + 28, aaaaResponse.size)
        assertEquals(16, aaaaResponse[aaaa.size + 11].toInt())

        val https = dnsQuery("ads.example.com", 65)
        val httpsResponse = DnsPacket.blockedResponse(DnsPacket.parse(udpPacket(https), 28 + https.size)!!)
        assertEquals(https.size, httpsResponse.size)
        assertEquals(0, httpsResponse[7].toInt())
    }

    @Test
    fun dropsEdnsRecordFromBlockedResponse() {
        val base = dnsQuery("ads.example.com", DnsPacket.TYPE_A)
        base[11] = 1 // ARCOUNT
        val withOpt = base + byteArrayOf(0, 0, 41, 0x10, 0, 0, 0, 0, 0, 0, 0)
        val query = DnsPacket.parse(udpPacket(withOpt), 28 + withOpt.size)!!
        val response = DnsPacket.blockedResponse(query)
        assertEquals(base.size + 16, response.size)
        assertEquals(0, response[11].toInt())
    }

    @Test
    fun wrapsResponseBackToSender() {
        val dns = dnsQuery("ads.example.com", DnsPacket.TYPE_A)
        val query = DnsPacket.parse(udpPacket(dns), 28 + dns.size)!!
        val response = DnsPacket.blockedResponse(query)
        val packet = DnsPacket.wrapResponse(query, response)
        assertEquals(28 + response.size, packet.size)
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 2), packet.copyOfRange(12, 16))
        assertArrayEquals(byteArrayOf(10, 111, 222.toByte(), 1), packet.copyOfRange(16, 20))
        assertEquals(53, ((packet[20].toInt() and 0xFF) shl 8) or (packet[21].toInt() and 0xFF))
        assertEquals(50000, ((packet[22].toInt() and 0xFF) shl 8) or (packet[23].toInt() and 0xFF))
        // A header with a correct checksum sums to zero.
        assertEquals(0, DnsPacket.ipChecksum(packet, 0, 20))
        assertArrayEquals(response, packet.copyOfRange(28, packet.size))
    }
}
