package com.daftar.kredi

import java.util.UUID

/** Invoice photos are kept under generated names like "inv-<uuid>.jpg"; "" means no photo. */
object PhotoName {
    private val pattern = Regex("""inv-[0-9a-f-]{36}\.jpg""")

    fun new(): String = "inv-${UUID.randomUUID()}.jpg"

    fun isValid(name: String): Boolean = name.isEmpty() || pattern.matches(name)
}
