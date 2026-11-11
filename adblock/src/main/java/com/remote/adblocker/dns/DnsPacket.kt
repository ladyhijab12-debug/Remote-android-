package com.remote.adblocker.dns

/** A DNS question that arrived in an IPv4/UDP packet read from the VPN tunnel. */
class DnsQuery(
    val source: ByteArray,
    val destination: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    /** The raw DNS message (UDP payload). */
    val payload: ByteArray,
    /** Lower-case host name being asked about, without a trailing dot. */
    val name: String,
    val type: Int,
    /** Offset in [payload] just past the first question. */
    val questionEnd: Int,
)

object DnsPacket {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    private const val PROTOCOL_UDP = 17
    private const val DNS_PORT = 53
    private const val DNS_HEADER = 12

    /** Returns the DNS query carried by [packet], or null if it is anything else. */
    fun parse(packet: ByteArray, length: Int): DnsQuery? {
        if (length < 20 + 8 + DNS_HEADER || length > packet.size) return null
        if ((packet[0].toInt() and 0xF0) != 0x40) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20) return null
        if (packet[9].toInt() != PROTOCOL_UDP) return null
        // More-fragments flag or a fragment offset: not a whole datagram.
        if ((u16(packet, 6) and 0x3FFF) != 0) return null
        val total = u16(packet, 2)
        if (total > length || total < ihl + 8) return null
        val udpLength = u16(packet, ihl + 4)
        if (udpLength < 8 + DNS_HEADER || ihl + udpLength > total) return null
        val destinationPort = u16(packet, ihl + 2)
        if (destinationPort != DNS_PORT) return null

        val payload = packet.copyOfRange(ihl + 8, ihl + udpLength)
        if ((payload[2].toInt() and 0x80) != 0) return null // a response, not a query
        if (u16(payload, 4) < 1) return null

        val name = StringBuilder()
        var pos = DNS_HEADER
        while (true) {
            if (pos >= payload.size) return null
            val labelLength = payload[pos].toInt() and 0xFF
            pos++
            if (labelLength == 0) break
            if (labelLength > 63 || pos + labelLength > payload.size) return null
            if (name.isNotEmpty()) name.append('.')
            for (i in pos until pos + labelLength) {
                name.append((payload[i].toInt() and 0xFF).toChar().lowercaseChar())
            }
            pos += labelLength
        }
        if (pos + 4 > payload.size) return null

        return DnsQuery(
            source = packet.copyOfRange(12, 16),
            destination = packet.copyOfRange(16, 20),
            sourcePort = u16(packet, ihl),
            destinationPort = destinationPort,
            payload = payload,
            name = name.toString(),
            type = u16(payload, pos),
            questionEnd = pos + 4,
        )
    }

    /**
     * A DNS answer that sends the app nowhere: 0.0.0.0 for A, :: for AAAA and
     * an empty answer for every other record type.
     */
    fun blockedResponse(query: DnsQuery): ByteArray {
        val addressLength = when (query.type) {
            TYPE_A -> 4
            TYPE_AAAA -> 16
            else -> 0
        }
        val answerLength = if (addressLength == 0) 0 else 12 + addressLength
        val out = ByteArray(query.questionEnd + answerLength)
        System.arraycopy(query.payload, 0, out, 0, query.questionEnd)
        // QR=1, keep opcode and RD; RA=1, RCODE=0.
        out[2] = ((query.payload[2].toInt() and 0x79) or 0x80).toByte()
        out[3] = 0x80.toByte()
        put16(out, 4, 1)                                 // QDCOUNT
        put16(out, 6, if (addressLength == 0) 0 else 1)  // ANCOUNT
        put16(out, 8, 0)                                 // NSCOUNT
        put16(out, 10, 0)                                // ARCOUNT
        if (addressLength != 0) {
            var pos = query.questionEnd
            put16(out, pos, 0xC000 or DNS_HEADER)        // pointer to the question name
            put16(out, pos + 2, query.type)
            put16(out, pos + 4, 1)                       // class IN
            put16(out, pos + 6, 0)                       // TTL (high)
            put16(out, pos + 8, 60)                      // TTL (low)
            put16(out, pos + 10, addressLength)
            pos += 12
            // The address itself stays all zeroes.
        }
        return out
    }

    /** Wraps a DNS [response] in the IPv4/UDP packet that answers [query]. */
    fun wrapResponse(query: DnsQuery, response: ByteArray): ByteArray {
        val total = 20 + 8 + response.size
        val out = ByteArray(total)
        out[0] = 0x45
        put16(out, 2, total)
        put16(out, 6, 0x4000) // don't fragment
        out[8] = 64
        out[9] = PROTOCOL_UDP.toByte()
        System.arraycopy(query.destination, 0, out, 12, 4)
        System.arraycopy(query.source, 0, out, 16, 4)
        put16(out, 10, ipChecksum(out, 0, 20))
        put16(out, 20, query.destinationPort)
        put16(out, 22, query.sourcePort)
        put16(out, 24, 8 + response.size)
        // UDP checksum 0 = "not computed", which IPv4 allows.
        System.arraycopy(response, 0, out, 28, response.size)
        return out
    }

    internal fun ipChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            sum += u16(data, i)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value shr 8).toByte()
        data[offset + 1] = value.toByte()
    }
}
