package com.daftar.kredi

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Everything is stored on the phone in one small SQLite database. */
class KrediDb(context: Context) : SQLiteOpenHelper(context, "kredi.db", null, 3) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE customers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                phone TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                created INTEGER NOT NULL,
                kind TEXT NOT NULL DEFAULT 'CUSTOMER')"""
        )
        db.execSQL(
            """CREATE TABLE entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                customer_id INTEGER NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
                type TEXT NOT NULL,
                amount INTEGER NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                time INTEGER NOT NULL,
                ref TEXT NOT NULL DEFAULT '',
                photo TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL("CREATE INDEX entries_customer ON entries(customer_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 2 adds suppliers; every existing book is a customer's.
        if (oldVersion < 2) db.execSQL("ALTER TABLE customers ADD COLUMN kind TEXT NOT NULL DEFAULT 'CUSTOMER'")
        // Version 3 adds invoice numbers and photos.
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE entries ADD COLUMN ref TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE entries ADD COLUMN photo TEXT NOT NULL DEFAULT ''")
        }
    }

    /** Books of one [kind] matching [query] (name or phone): open balances first, then most recent. */
    fun summaries(kind: PartyKind, query: String = ""): List<CustomerSummary> {
        val like = "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val sql = """
            SELECT c.id, c.name, c.phone, c.note, c.created, c.kind,
                   COALESCE(SUM(CASE WHEN e.type = 'CREDIT' THEN e.amount ELSE -e.amount END), 0) AS bal,
                   COALESCE(MAX(e.time), c.created) AS last
            FROM customers c LEFT JOIN entries e ON e.customer_id = c.id
            WHERE c.kind = ? AND (c.name LIKE ? ESCAPE '\' OR c.phone LIKE ? ESCAPE '\')
            GROUP BY c.id
            ORDER BY (bal > 0) DESC, last DESC"""
        return readableDatabase.rawQuery(sql, arrayOf(kind.name, like, like)).use { c ->
            buildList {
                while (c.moveToNext()) add(CustomerSummary(c.toCustomer(), c.getLong(6), c.getLong(7)))
            }
        }
    }

    fun customer(id: Long): Customer? =
        readableDatabase.rawQuery("SELECT id, name, phone, note, created, kind FROM customers WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.toCustomer() else null }

    fun addCustomer(kind: PartyKind, name: String, phone: String, note: String): Long =
        writableDatabase.insertOrThrow("customers", null, ContentValues().apply {
            put("name", name); put("phone", phone); put("note", note); put("created", System.currentTimeMillis())
            put("kind", kind.name)
        })

    fun updateCustomer(id: Long, name: String, phone: String, note: String) {
        writableDatabase.update("customers", ContentValues().apply {
            put("name", name); put("phone", phone); put("note", note)
        }, "id = ?", arrayOf(id.toString()))
    }

    fun deleteCustomer(id: Long) {
        writableDatabase.delete("customers", "id = ?", arrayOf(id.toString()))
    }

    /** Newest first. */
    fun entries(customerId: Long): List<Entry> =
        readableDatabase.rawQuery(
            "SELECT id, customer_id, type, amount, note, time, ref, photo FROM entries WHERE customer_id = ? ORDER BY time DESC, id DESC",
            arrayOf(customerId.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.toEntry()) } }

    fun addEntry(e: Entry): Long =
        writableDatabase.insertOrThrow("entries", null, e.values())

    fun updateEntry(e: Entry) {
        writableDatabase.update("entries", e.values(), "id = ?", arrayOf(e.id.toString()))
    }

    fun entry(id: Long): Entry? =
        readableDatabase.rawQuery(
            "SELECT id, customer_id, type, amount, note, time, ref, photo FROM entries WHERE id = ?",
            arrayOf(id.toString())
        ).use { c -> if (c.moveToFirst()) c.toEntry() else null }

    /** Photo file names still used by some entry. */
    fun photoNames(): Set<String> =
        readableDatabase.rawQuery("SELECT photo FROM entries WHERE photo != ''", null)
            .use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    private fun Entry.values() = ContentValues().apply {
        put("customer_id", customerId); put("type", type.name); put("amount", amount)
        put("note", note); put("time", time); put("ref", ref); put("photo", photo)
    }

    fun deleteEntry(id: Long) {
        writableDatabase.delete("entries", "id = ?", arrayOf(id.toString()))
    }

    fun exportAll(): BackupData {
        val db = readableDatabase
        val customers = db.rawQuery("SELECT id, name, phone, note, created, kind FROM customers ORDER BY id", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toCustomer()) } }
        val entries = db.rawQuery("SELECT id, customer_id, type, amount, note, time, ref, photo FROM entries ORDER BY id", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toEntry()) } }
        return BackupData(customers, entries)
    }

    /** Replaces the whole book with a backup, all or nothing. */
    fun replaceAll(data: BackupData) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("entries", null, null)
            db.delete("customers", null, null)
            data.customers.forEach { c ->
                db.insertOrThrow("customers", null, ContentValues().apply {
                    put("id", c.id); put("name", c.name); put("phone", c.phone)
                    put("note", c.note); put("created", c.createdAt); put("kind", c.kind.name)
                })
            }
            data.entries.forEach { e ->
                db.insertOrThrow("entries", null, e.values().apply { put("id", e.id) })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.toCustomer() = Customer(
        getLong(0), getString(1), getString(2), getString(3), getLong(4),
        runCatching { PartyKind.valueOf(getString(5)) }.getOrDefault(PartyKind.CUSTOMER)
    )

    private fun Cursor.toEntry() = Entry(
        getLong(0), getLong(1),
        runCatching { EntryType.valueOf(getString(2)) }.getOrDefault(EntryType.CREDIT),
        getLong(3), getString(4), getLong(5), getString(6), getString(7)
    )
}
