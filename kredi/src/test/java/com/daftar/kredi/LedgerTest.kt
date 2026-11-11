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
            listOf(Customer(7, "فاطمة \"الزهراء\"", "0611", "حي السلام", 123)),
            listOf(Entry(3, 7, EntryType.CREDIT, 4550, "زيت", 456), Entry(4, 7, EntryType.PAYMENT, 1000, "", 789)),
        )
        assertEquals(data, Backup.fromJson(Backup.toJson(data)))
    }

    @Test
    fun rejectsBadBackups() {
        val bad = listOf(
            "not json",
            "{}",
            """{"format":"other","version":1,"customers":[],"entries":[]}""",
            """{"format":"daftar-kredi","version":1,"customers":[],"entries":[{"id":1,"customerId":9,"type":"CREDIT","amount":5,"time":1}]}""",
            """{"format":"daftar-kredi","version":1,"customers":[{"id":1,"name":"a"}],"entries":[{"id":1,"customerId":1,"type":"LOAN","amount":5,"time":1}]}""",
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
