package com.daftar.kredi

enum class EntryType { CREDIT, PAYMENT }

/**
 * Who the book is with. For a CUSTOMER, CREDIT means they took goods and owe the shop;
 * for a SUPPLIER, CREDIT means the shop took goods and owes them.
 */
enum class PartyKind { CUSTOMER, SUPPLIER }

data class Customer(
    val id: Long,
    val name: String,
    val phone: String,
    val note: String,
    val createdAt: Long,
    val kind: PartyKind = PartyKind.CUSTOMER,
)

/** One line in a customer's book: goods taken on credit, or money paid back. */
data class Entry(
    val id: Long,
    val customerId: Long,
    val type: EntryType,
    val amount: Long,
    val note: String,
    val time: Long,
) {
    /** What this line adds to the debt: positive for credit, negative for a payment. */
    val signed: Long get() = if (type == EntryType.CREDIT) amount else -amount
}

data class CustomerSummary(
    val customer: Customer,
    /** What is still owed (by the customer, or to the supplier); negative when paid in advance. */
    val balance: Long,
    val lastActivity: Long,
)

object Ledger {

    fun balance(entries: List<Entry>): Long = entries.sumOf { it.signed }

    /** Everything still owed in these books (advances are not subtracted). */
    fun totalOwed(summaries: List<CustomerSummary>): Long =
        summaries.sumOf { if (it.balance > 0) it.balance else 0L }

    /**
     * The number in the international form wa.me expects, without "+".
     * Moroccan local numbers (06…, 07…, 05…) get the 212 prefix.
     */
    fun whatsappNumber(phone: String): String? {
        val raw = Digits.latin(phone).trim()
        var digits = raw.filter { it in '0'..'9' }
        when {
            raw.startsWith("+") -> Unit
            digits.startsWith("00") -> digits = digits.drop(2)
            digits.length == 10 && digits.startsWith("0") -> digits = "212" + digits.drop(1)
        }
        return digits.takeIf { it.length in 8..15 }
    }

    fun reminderMessage(name: String, balance: Long): String =
        "السلام عليكم $name،\n" +
            "الباقي عليك فالكريدي: ${Money.display(balance)}.\n" +
            "الله يخليك خلّص ملي تقدر. شكراً 🙏"

    /** A text copy of the book, oldest line first, to send on WhatsApp. */
    fun statement(
        name: String,
        entries: List<Entry>,
        kind: PartyKind = PartyKind.CUSTOMER,
        formatDate: (Long) -> String,
    ): String {
        val supplier = kind == PartyKind.SUPPLIER
        val lines = entries.sortedWith(compareBy({ it.time }, { it.id })).map { e ->
            val label = when {
                e.type == EntryType.PAYMENT -> if (supplier) "خلّصتك" else "خلّص"
                supplier -> "سلعة بالكريدي"
                else -> "كريدي"
            }
            val note = if (e.note.isBlank()) "" else " (${e.note})"
            "${formatDate(e.time)} — $label ${Money.display(e.amount)}$note"
        }
        val balance = balance(entries)
        val total = when {
            balance > 0 -> (if (supplier) "باقي عندك عندي: " else "الباقي: ") + Money.display(balance)
            balance < 0 -> (if (supplier) "خلّصتك قدّام: " else "عندك عندنا: ") + Money.display(-balance)
            else -> "الحساب خالص ✅"
        }
        return buildString {
            append(if (supplier) "كشف الحساب مع " else "كشف الحساب ديال ").append(name).append("\n\n")
            if (lines.isEmpty()) append("ما كاين حتى عملية.\n") else lines.forEach { append(it).append('\n') }
            append('\n').append(total)
        }
    }
}
