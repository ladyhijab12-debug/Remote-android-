package com.daftar.kredi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LedgerTest {

    private fun entry(id: Long, type: EntryType, amount: Long, time: Long = id, note: String = "") =
        Entry(id, 1, type, amount, note, time)

    @Test
    fun parsesWhatPeopleType() {
        assertEquals(5000L, Money.parse("50"))
        assertEquals(1250L, Money.parse("12.5"))
        assertEquals(1250L, Money.parse("12,50"))
        assertEquals(120000L, Money.parse(" 1 200 "))
        assertEquals(50L, Money.parse(".5"))
        assertEquals(7500L, Money.parse("٧٥"))
        assertNull(Money.parse(""))
        assertNull(Money.parse("0"))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("1.234"))
        assertNull(Money.parse("-5"))
    }

    @Test
    fun formatsAmounts() {
        assertEquals("50 DH", Money.format(5000))
        assertEquals("12.50 DH", Money.format(1250))
        assertEquals("1 200.05 DH", Money.format(120005))
        assertEquals("-3 DH", Money.format(-300))
        assertEquals("1200.50", Money.plain(120050))
        assertEquals("7", Money.plain(700))
    }

    @Test
    fun balanceIsCreditMinusPayments() {
        val entries = listOf(
            entry(1, EntryType.CREDIT, 5000),
            entry(2, EntryType.CREDIT, 1250),
            entry(3, EntryType.PAYMENT, 2000),
        )
        assertEquals(4250L, Ledger.balance(entries))
    }

    @Test
    fun totalIgnoresAdvances() {
        val c = Customer(1, "a", "", "", 0)
        val list = listOf(CustomerSummary(c, 1000, 0), CustomerSummary(c, -500, 0), CustomerSummary(c, 0, 0))
        assertEquals(1000L, Ledger.totalOwed(list))
    }

    @Test
    fun paymentsSettleOldestInvoicesFirst() {
        val entries = listOf(
            entry(1, EntryType.CREDIT, 1000, time = 10),
            entry(2, EntryType.CREDIT, 3000, time = 20),
            entry(3, EntryType.PAYMENT, 1500, time = 30),
            entry(4, EntryType.CREDIT, 500, time = 40),
            entry(5, EntryType.PAYMENT, 200, time = 50),
        )
        assertEquals(mapOf(1L to 1000L, 2L to 700L, 4L to 0L), Ledger.paidPerCredit(entries))
        assertEquals(4500L, Ledger.totalCredit(entries))
        assertEquals(1700L, Ledger.totalPaid(entries))
        assertEquals(Ledger.totalCredit(entries) - Ledger.totalPaid(entries), Ledger.balance(entries))
    }

    @Test
    fun overpaymentStopsAtEachInvoice() {
        val paid = Ledger.paidPerCredit(listOf(entry(1, EntryType.CREDIT, 100), entry(2, EntryType.PAYMENT, 999)))
        assertEquals(mapOf(1L to 100L), paid)
    }

    @Test
    fun zipBackupCarriesPhotos() {
        val dir = createTempDir()
        try {
            val name = PhotoName.new()
            java.io.File(dir, name).writeBytes(byteArrayOf(1, 2, 3))
            val data = BackupData(
                listOf(Customer(1, "مول الزيت", "", "", 0, PartyKind.SUPPLIER)),
                listOf(Entry(1, 1, EntryType.CREDIT, 700, "", 5, "9", name), Entry(2, 1, EntryType.PAYMENT, 100, "", 6)),
            )
            val out = java.io.ByteArrayOutputStream()
            BackupArchive.write(out, data) { java.io.File(dir, it).takeIf { f -> f.isFile } }
            val restored = BackupArchive.read(out.toByteArray())
            assertEquals(data, restored.data)
            assertEquals(listOf<Byte>(1, 2, 3), restored.photos.getValue(name).toList())

            // Old plain-JSON backups still restore.
            assertEquals(data, BackupArchive.read(Backup.toJson(data).toByteArray()).data)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun zipBackupIgnoresStrangePaths() {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("data.json"))
            zip.write(Backup.toJson(BackupData(emptyList(), emptyList())).toByteArray())
            zip.putNextEntry(java.util.zip.ZipEntry("invoices/../../databases/kredi.db"))
            zip.write(byteArrayOf(9))
        }
        assertTrue(BackupArchive.read(out.toByteArray()).photos.isEmpty())
    }

    @Test
    fun whatsappNumbers() {
        assertEquals("212612345678", Ledger.whatsappNumber("06 12 34 56 78"))
        assertEquals("212712345678", Ledger.whatsappNumber("0712-345678"))
        assertEquals("212612345678", Ledger.whatsappNumber("+212 6 12 34 56 78"))
        assertEquals("33612345678", Ledger.whatsappNumber("0033612345678"))
        assertEquals("212612345678", Ledger.whatsappNumber("٠٦١٢٣٤٥٦٧٨"))
        assertNull(Ledger.whatsappNumber(""))
        assertNull(Ledger.whatsappNumber("123"))
    }

    @Test
    fun statementListsOldestFirstWithBalance() {
        val text = Ledger.statement(
            "أحمد",
            listOf(entry(2, EntryType.PAYMENT, 2000, time = 20), entry(1, EntryType.CREDIT, 5000, time = 10, note = "سكر")),
        ) { "d$it" }
        assertTrue(text, text.indexOf("d10") < text.indexOf("d20"))
        assertTrue(text, text.contains("(سكر)"))
        assertTrue(text, text.endsWith("الباقي: ${Money.display(3000)}"))
    }

    @Test
    fun backupRoundTrip() {
        val data = BackupData(
            listOf(
                Customer(7, "فاطمة \"الزهراء\"", "0611", "حي السلام", 123),
                Customer(8, "شركة الحليب", "0522", "", 124, PartyKind.SUPPLIER),
            ),
            listOf(
                Entry(3, 8, EntryType.CREDIT, 4550, "زيت", 456, ref = "F/2026/31", photo = PhotoName.new()),
                Entry(4, 8, EntryType.PAYMENT, 1000, "", 789),
            ),
        )
        assertEquals(data, Backup.fromJson(Backup.toJson(data)))
    }

    @Test
    fun supplierStatementUsesShopWording() {
        val text = Ledger.statement(
            "شركة الحليب",
            listOf(
                Entry(1, 1, EntryType.CREDIT, 90000, "حليب", 1, ref = "A-12"),
                entry(2, EntryType.PAYMENT, 40000),
            ),
            PartyKind.SUPPLIER,
        ) { "d$it" }
        assertTrue(text, text.startsWith("كشف الحساب مع شركة الحليب"))
        assertTrue(text, text.contains("فاتورة رقم A-12 ${Money.display(90000)} (حليب)"))
        assertTrue(text, text.contains("دفعة ${Money.display(40000)}"))
        assertTrue(text, text.contains("مجموع الفواتير: ${Money.display(90000)}"))
        assertTrue(text, text.contains("مجموع الدفوعات: ${Money.display(40000)}"))
        assertTrue(text, text.endsWith("باقي عندك عندي: ${Money.display(50000)}"))
    }

    @Test
    fun readsVersionOneBackupsAsCustomers() {
        val v1 = """{"format":"daftar-kredi","version":1,
            "customers":[{"id":1,"name":"أحمد","phone":"","note":"","createdAt":5}],
            "entries":[{"id":1,"customerId":1,"type":"CREDIT","amount":500,"note":"","time":6}]}"""
        val data = Backup.fromJson(v1)
        assertEquals(PartyKind.CUSTOMER, data.customers.single().kind)
        assertEquals(500L, data.entries.single().amount)
    }

    @Test
    fun rejectsBadBackups() {
        val bad = listOf(
            "not json",
            "{}",
            """{"format":"other","version":1,"customers":[],"entries":[]}""",
            """{"format":"daftar-kredi","version":1,"customers":[],"entries":[{"id":1,"customerId":9,"type":"CREDIT","amount":5,"time":1}]}""",
            """{"format":"daftar-kredi","version":1,"customers":[{"id":1,"name":"a"}],"entries":[{"id":1,"customerId":1,"type":"LOAN","amount":5,"time":1}]}""",
            """{"format":"daftar-kredi","version":2,"customers":[{"id":1,"name":"a","kind":"BANK"}],"entries":[]}""",
            """{"format":"daftar-kredi","version":4,"customers":[],"entries":[]}""",
            """{"format":"daftar-kredi","version":3,"customers":[{"id":1,"name":"a"}],"entries":[{"id":1,"customerId":1,"type":"CREDIT","amount":5,"time":1,"photo":"../../databases/kredi.db"}]}""",
        )
        for (text in bad) {
            try {
                Backup.fromJson(text)
                fail("accepted: $text")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }
}
