package com.remote.adblocker

import java.util.concurrent.atomic.AtomicLong

/** Counters for the current run of the blocker, shown on the main screen. */
object Stats {
    private const val RECENT_LIMIT = 40

    val blocked = AtomicLong()
    val total = AtomicLong()
    private val recent = ArrayDeque<String>()

    fun recordAllowed() {
        total.incrementAndGet()
    }

    fun recordBlocked(host: String) {
        total.incrementAndGet()
        blocked.incrementAndGet()
        synchronized(recent) {
            recent.remove(host)
            recent.addFirst(host)
            while (recent.size > RECENT_LIMIT) recent.removeLast()
        }
    }

    fun recentBlocked(): List<String> = synchronized(recent) { recent.toList() }
}
