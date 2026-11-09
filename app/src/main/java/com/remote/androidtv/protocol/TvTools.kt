package com.remote.androidtv.protocol

import java.io.IOException

/** A home-screen app installed on the TV. */
data class Launcher(val component: String, val isStock: Boolean, val isCurrent: Boolean) {
    val packageName: String get() = component.substringBefore('/')
    val label: String get() = KNOWN_NAMES[packageName] ?: packageName

    private companion object {
        val KNOWN_NAMES = mapOf(
            "com.google.android.apps.tv.launcherx" to "Google TV (الأصلية)",
            "com.google.android.tvlauncher" to "Android TV Home (الأصلية)",
            "com.google.android.leanbacklauncher" to "Leanback Launcher (الأصلية)",
            "com.spocky.projengmenu" to "Projectivy Launcher",
            "me.efesser.flauncher" to "FLauncher",
            "com.wolf.firelauncher" to "Wolf Launcher",
            "com.wolf.google.lm" to "Wolf Launcher",
            "ca.dstudio.atvlauncher.free" to "ATV Launcher",
            "ca.dstudio.atvlauncher.pro" to "ATV Launcher Pro",
            "com.teslacoilsw.launcher" to "Nova Launcher",
            "com.mitv.tvhome" to "PatchWall (Xiaomi)",
        )
    }
}

/** Cleaning and home-screen (launcher) changes, done with shell commands over [AdbClient]. */
class TvTools(private val shell: (String) -> String) {

    constructor(adb: AdbClient) : this(adb::shell)

    /** Free space on the TV's internal storage, in bytes, or null if it could not be read. */
    fun freeBytes(): Long? = parseDfAvailable(shell("df /data"))

    /**
     * Clears the cache of every app and stops apps running in the background.
     * Returns how many bytes were freed (null if storage could not be measured).
     */
    fun clean(): Long? {
        val before = freeBytes()
        // Asks the system to free up to this much by deleting app caches (it stops when all are empty).
        shell("pm trim-caches 999999999999999")
        shell("am kill-all")
        val after = freeBytes()
        return if (before != null && after != null) (after - before).coerceAtLeast(0) else null
    }

    fun launchers(): List<Launcher> {
        val current = parseComponents(shell("cmd package resolve-activity --brief $HOME_INTENT")).lastOrNull()
        return parseComponents(shell("cmd package query-activities --brief $HOME_INTENT"))
            .filterNot { c -> HIDDEN.any { c.contains(it) } }
            .distinct()
            .map { Launcher(it, it.substringBefore('/') in STOCK_PACKAGES, it == current) }
    }

    /**
     * Makes [launcher] the home screen. Google TV tends to bring its own home back, so with
     * [disableStock] the stock launchers are switched off too (undo with [restoreStock]).
     */
    fun setLauncher(launcher: Launcher, disableStock: Boolean) {
        if (disableStock && !launcher.isStock) {
            launchers().filter { it.isStock }.map { it.packageName }.distinct().forEach {
                shell("pm disable-user --user 0 ${quote(it)}")
            }
        }
        val out = shell("cmd package set-home-activity ${quote(launcher.component)}")
        if (out.contains("Error", ignoreCase = true) || out.contains("Exception")) {
            throw IOException(out.trim())
        }
        goHome()
    }

    /** Turns the TV's original home screen back on and makes it the default again. */
    fun restoreStock() {
        STOCK_PACKAGES.forEach { shell("pm enable ${quote(it)} 2>/dev/null") }
        launchers().firstOrNull { it.isStock }?.let {
            shell("cmd package set-home-activity ${quote(it.component)}")
        }
        goHome()
    }

    private fun goHome() {
        shell("input keyevent ${TvKeys.HOME}")
    }

    companion object {
        private const val HOME_INTENT = "-a android.intent.action.MAIN -c android.intent.category.HOME"

        val STOCK_PACKAGES = listOf(
            "com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher",
            "com.google.android.leanbacklauncher",
        )

        /** System screens that answer the HOME intent but are not real home screens. */
        private val HIDDEN = listOf("FallbackHome", "ResolverActivity", "setupwizard", "SetupWizard", "provision")

        private val COMPONENT = Regex("""[A-Za-z][\w.]*/[\w.$]+""")

        fun parseComponents(output: String): List<String> =
            output.lineSequence().mapNotNull { COMPONENT.find(it.trim())?.value }.toList()

        /** `df /data` prints 1K blocks: "Filesystem 1K-blocks Used Available Use% Mounted on". */
        fun parseDfAvailable(output: String): Long? {
            val line = output.lineSequence().lastOrNull { it.trim().endsWith("/data") } ?: return null
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 6) return null
            return cols[3].toLongOrNull()?.times(1024)
        }

        private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
