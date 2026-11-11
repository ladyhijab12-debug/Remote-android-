package com.remote.adblocker

import android.content.Context
import com.remote.adblocker.dns.BlockList
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Keeps the block list (built-in + downloaded) and the user's allow list. */
object BlockListStore {
    private const val LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private const val BUILT_IN = "blocklist.txt"
    private const val DOWNLOADED = "hosts-downloaded.txt"
    private const val PREFS = "adblock"
    private const val KEY_ALLOWED = "allowed"
    private const val KEY_UPDATED = "updated_at"
    private const val KEY_TOTAL_BLOCKED = "total_blocked"
    private const val MIN_PLAUSIBLE_ENTRIES = 1000
    private const val UPDATE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): BlockList {
        val hosts = context.assets.open(BUILT_IN).bufferedReader().use { BlockList.parse(it) }
        val downloaded = File(context.filesDir, DOWNLOADED)
        if (downloaded.exists()) {
            try {
                downloaded.bufferedReader().use { hosts.addAll(BlockList.parse(it)) }
            } catch (_: IOException) {
                // Fall back to the built-in list.
            }
        }
        return BlockList(hosts, allowed(context))
    }

    /** Downloads a fresh list. Returns the number of domains in it, or throws. */
    @Throws(IOException::class)
    fun update(context: Context): Int {
        val temp = File(context.filesDir, "$DOWNLOADED.tmp")
        val connection = URL(LIST_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { input -> temp.outputStream().use { input.copyTo(it) } }
        } finally {
            connection.disconnect()
        }
        val count = temp.bufferedReader().use { BlockList.parse(it).size }
        if (count < MIN_PLAUSIBLE_ENTRIES) {
            temp.delete()
            throw IOException("list too short ($count)")
        }
        if (!temp.renameTo(File(context.filesDir, DOWNLOADED))) throw IOException("could not save list")
        prefs(context).edit().putLong(KEY_UPDATED, System.currentTimeMillis()).apply()
        return count
    }

    fun updatedAt(context: Context): Long = prefs(context).getLong(KEY_UPDATED, 0)

    fun isStale(context: Context): Boolean =
        System.currentTimeMillis() - updatedAt(context) > UPDATE_INTERVAL_MS

    fun allowed(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ALLOWED, emptySet())!!.toSet()

    /** Saves the allow list from free text (one domain per line) and returns what was kept. */
    fun saveAllowed(context: Context, text: String): Set<String> {
        val hosts = text.split('\n', ' ', ',', '،')
            .map { cleanHost(it) }
            .filter { BlockList.isDomain(it) }
            .toSet()
        prefs(context).edit().putStringSet(KEY_ALLOWED, hosts).apply()
        return hosts
    }

    /** Accepts "https://www.example.com/page" as well as "example.com". */
    private fun cleanHost(input: String): String =
        input.trim().lowercase()
            .substringAfter("://")
            .substringBefore('/')
            .substringBefore(':')
            .trim('.')

    fun totalBlocked(context: Context): Long = prefs(context).getLong(KEY_TOTAL_BLOCKED, 0)

    fun addTotalBlocked(context: Context, count: Long) {
        if (count <= 0) return
        val p = prefs(context)
        p.edit().putLong(KEY_TOTAL_BLOCKED, p.getLong(KEY_TOTAL_BLOCKED, 0) + count).apply()
    }
}
