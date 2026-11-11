package com.daftar.kredi

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class BackupData(val customers: List<Customer>, val entries: List<Entry>)

/** The backup file: plain JSON the user keeps in Drive, WhatsApp, etc. */
object Backup {

    private const val FORMAT = "daftar-kredi"
    private const val VERSION = 3

    fun toJson(data: BackupData): String {
        val customers = JSONArray()
        data.customers.forEach { c ->
            customers.put(
                JSONObject()
                    .put("id", c.id).put("name", c.name).put("phone", c.phone)
                    .put("note", c.note).put("createdAt", c.createdAt).put("kind", c.kind.name)
            )
        }
        val entries = JSONArray()
        data.entries.forEach { e ->
            entries.put(
                JSONObject()
                    .put("id", e.id).put("customerId", e.customerId).put("type", e.type.name)
                    .put("amount", e.amount).put("note", e.note).put("time", e.time)
                    .put("ref", e.ref).put("photo", e.photo)
            )
        }
        return JSONObject()
            .put("format", FORMAT).put("version", VERSION)
            .put("customers", customers).put("entries", entries)
            .toString(2)
    }

    /** Throws [IllegalArgumentException] when the file is not a valid backup. */
    fun fromJson(text: String): BackupData = try {
        parse(JSONObject(text))
    } catch (e: JSONException) {
        throw IllegalArgumentException("bad backup file", e)
    }

    private fun parse(root: JSONObject): BackupData {
        require(root.optString("format") == FORMAT) { "not a daftar-kredi backup" }
        require(root.optInt("version") in 1..VERSION) { "unsupported version" }

        val customers = root.getJSONArray("customers").let { arr ->
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Customer(
                    id = o.getLong("id"),
                    name = o.getString("name").trim().also { require(it.isNotEmpty()) { "empty name" } },
                    phone = o.optString("phone"),
                    note = o.optString("note"),
                    createdAt = o.optLong("createdAt"),
                    // Version 1 files only had customers.
                    kind = PartyKind.valueOf(o.optString("kind", PartyKind.CUSTOMER.name)),
                )
            }
        }
        val ids = customers.map { it.id }.toSet()
        require(ids.size == customers.size) { "duplicate customer id" }

        val entries = root.getJSONArray("entries").let { arr ->
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    id = o.getLong("id"),
                    customerId = o.getLong("customerId").also { require(it in ids) { "unknown customer $it" } },
                    type = EntryType.valueOf(o.getString("type")),
                    amount = o.getLong("amount").also { require(it > 0) { "bad amount" } },
                    note = o.optString("note"),
                    time = o.getLong("time"),
                    ref = o.optString("ref"),
                    // Only a bare file name, never a path, since it is joined to the photo folder.
                    photo = o.optString("photo").also { require(PhotoName.isValid(it)) { "bad photo name" } },
                )
            }
        }
        return BackupData(customers, entries)
    }
}
