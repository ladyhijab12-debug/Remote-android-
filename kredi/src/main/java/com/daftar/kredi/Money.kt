package com.daftar.kredi

import java.util.Locale
import kotlin.math.abs

/** Amounts are stored as whole centimes (Long) so sums never pick up rounding errors. */
object Money {

    private val pattern = Regex("""\d+(\.\d{0,2})?|\.\d{1,2}""")
    private const val MAX_WHOLE = 1_000_000_000L

    /**
     * Reads what the user typed ("50", "12.5", "12,50", "1 200", Arabic-Indic digits) as centimes.
     * Returns null when it is not a valid positive amount.
     */
    fun parse(text: String): Long? {
        val cleaned = Digits.latin(text).trim()
            .replace(" ", "").replace(" ", "").replace(" ", "")
            .replace(',', '.').replace('٫', '.')
        if (!pattern.matches(cleaned)) return null
        val parts = cleaned.split('.')
        val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        if (whole > MAX_WHOLE) return null
        val frac = parts.getOrNull(1).orEmpty().padEnd(2, '0').take(2).toLong()
        val cents = whole * 100 + frac
        return if (cents > 0) cents else null
    }

    /** "1 200.50 DH"; whole amounts drop the decimals ("50 DH"). */
    fun format(cents: Long): String {
        val a = abs(cents)
        val whole = String.format(Locale.US, "%,d", a / 100).replace(',', ' ')
        val frac = a % 100
        val number = if (frac == 0L) whole else whole + "." + frac.toString().padStart(2, '0')
        return (if (cents < 0) "-" else "") + number + " DH"
    }

    /** Same text for the field the user edits: no currency, no grouping ("1200.5" → "1200.50"). */
    fun plain(cents: Long): String {
        val a = abs(cents)
        val frac = a % 100
        return (a / 100).toString() + if (frac == 0L) "" else "." + frac.toString().padStart(2, '0')
    }

    /** [format] wrapped as a left-to-right island, so it reads correctly inside Arabic text. */
    fun display(cents: Long): String = "⁦" + format(cents) + "⁩"
}

object Digits {
    /** Turns Arabic-Indic (٠١٢) and Persian (۰۱۲) digits into 0-9. */
    fun latin(text: String): String = buildString(text.length) {
        for (ch in text) {
            append(
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    else -> ch
                }
            )
        }
    }
}
