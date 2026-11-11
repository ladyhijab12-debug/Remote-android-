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
    /** For a supplier: when the invoice is dated (or when the payment was made). */
    val time: Long,
    /** Invoice number, if any. */
    val ref: String = "",
    /** File name of the invoice photo in [PhotoStore], if any. */
    val photo: String = "",
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

    fun totalCredit(entries: List<Entry>): Long =
        entries.sumOf { if (it.type == EntryType.CREDIT) it.amount else 0L }

    fun totalPaid(entries: List<Entry>): Long =
        entries.sumOf { if (it.type == EntryType.PAYMENT) it.amount else 0L }

    /**
     * How much of each credit / invoice is already paid (credit id → amount).
     * Payments settle the oldest invoices first.
     */
    fun paidPerCredit(entries: List<Entry>): Map<Long, Long> {
        var pool = totalPaid(entries)
        return entries.filter { it.type == EntryType.CREDIT }
            .sortedWith(compareBy({ it.time }, { it.id }))
            .associate { e ->
                val paid = minOf(e.amount, pool)
                pool -= paid
                e.id to paid
            }
    }

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
                e.type == EntryType.PAYMENT -> if (supplier) "دفعة" else "خلّص"
                supplier -> if (e.ref.isBlank()) "فاتورة" else "فاتورة رقم ${e.ref}"
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
            if (supplier && lines.isNotEmpty()) {
                append('\n').append("مجموع الفواتير: ").append(Money.display(totalCredit(entries)))
                append('\n').append("مجموع الدفوعات: ").append(Money.display(totalPaid(entries))).append('\n')
            }
            append('\n').append(total)
        }
    }
}
