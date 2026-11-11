package com.remote.adblocker.dns

import java.io.BufferedReader

/**
 * Decides which host names are ad servers. A listed domain also covers all of
 * its subdomains; anything on the allow list always wins.
 */
class BlockList(private val blocked: Set<String>, private val allowed: Set<String> = emptySet()) {

    val size: Int get() = blocked.size

    fun isBlocked(host: String): Boolean {
        val name = host.trimEnd('.').lowercase()
        if (matches(allowed, name)) return false
        return matches(blocked, name)
    }

    /** True if [name] or one of its parent domains (but never a bare TLD) is in [set]. */
    private fun matches(set: Set<String>, name: String): Boolean {
        if (set.isEmpty()) return false
        var candidate = name
        while (candidate.contains('.')) {
            if (candidate in set) return true
            candidate = candidate.substring(candidate.indexOf('.') + 1)
        }
        return false
    }

    companion object {
        private val SKIP = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0")

        /**
         * Reads either a hosts file ("0.0.0.0 ads.example.com") or a plain list
         * with one domain per line. '#' starts a comment.
         */
        fun parse(reader: BufferedReader): HashSet<String> {
            val result = HashSet<String>()
            reader.forEachLine { raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachLine
                val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                val host = when {
                    parts.size == 1 -> parts[0]
                    parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1" -> parts[1]
                    else -> return@forEachLine
                }.lowercase().trimEnd('.')
                if (host in SKIP || !isDomain(host)) return@forEachLine
                result.add(host)
            }
            return result
        }

        fun isDomain(host: String): Boolean {
            if (host.length > 253 || !host.contains('.')) return false
            if (host.startsWith('.') || host.contains("..")) return false
            return host.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' || it == '_' }
        }
    }
}
