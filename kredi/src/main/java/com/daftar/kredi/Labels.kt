package com.daftar.kredi

/** The words that change between a customer's book and a supplier's book. */
class Labels private constructor(
    val totalOwed: Int,
    val count: Int,
    val searchHint: Int,
    val add: Int,
    val empty: Int,
    val advanceShort: Int,
    val balanceOwes: Int,
    val balancePaid: Int,
    val balanceAdvance: Int,
    val addCredit: Int,
    val addPayment: Int,
    val entryCredit: Int,
    val entryPayment: Int,
    val creditTitle: Int,
    val paymentTitle: Int,
    val creditNote: Int,
    val newTitle: Int,
    val nameHint: Int,
    val savedCredit: Int,
    val savedPayment: Int,
    val canRemind: Boolean,
    /** Credits are invoices: they get a number and a photo. */
    val invoices: Boolean,
    val creditDate: Int,
    val paymentDate: Int,
    val totals: Int,
) {
    fun entry(type: EntryType) = if (type == EntryType.CREDIT) entryCredit else entryPayment

    companion object {
        private val CUSTOMER = Labels(
            totalOwed = R.string.total_owed,
            count = R.string.customers_count,
            searchHint = R.string.search_hint,
            add = R.string.add_customer,
            empty = R.string.empty_book,
            advanceShort = R.string.balance_advance,
            balanceOwes = R.string.balance_label_owes,
            balancePaid = R.string.balance_label_paid,
            balanceAdvance = R.string.balance_label_advance,
            addCredit = R.string.add_credit,
            addPayment = R.string.add_payment,
            entryCredit = R.string.entry_credit,
            entryPayment = R.string.entry_payment,
            creditTitle = R.string.credit_title,
            paymentTitle = R.string.payment_title,
            creditNote = R.string.entry_note,
            newTitle = R.string.customer_new_title,
            nameHint = R.string.customer_name,
            savedCredit = R.string.saved_credit,
            savedPayment = R.string.saved_payment,
            canRemind = true,
            invoices = false,
            creditDate = R.string.date,
            paymentDate = R.string.date,
            totals = R.string.totals_customer,
        )

        private val SUPPLIER = Labels(
            totalOwed = R.string.total_owed_suppliers,
            count = R.string.suppliers_count,
            searchHint = R.string.search_hint_suppliers,
            add = R.string.add_supplier,
            empty = R.string.empty_suppliers,
            advanceShort = R.string.balance_advance_supplier,
            balanceOwes = R.string.supplier_balance_label_owes,
            balancePaid = R.string.supplier_balance_label_paid,
            balanceAdvance = R.string.supplier_balance_label_advance,
            addCredit = R.string.supplier_add_credit,
            addPayment = R.string.supplier_add_payment,
            entryCredit = R.string.supplier_entry_credit,
            entryPayment = R.string.supplier_entry_payment,
            creditTitle = R.string.supplier_credit_title,
            paymentTitle = R.string.supplier_payment_title,
            creditNote = R.string.supplier_entry_note,
            newTitle = R.string.supplier_new_title,
            nameHint = R.string.supplier_name,
            savedCredit = R.string.supplier_saved_credit,
            savedPayment = R.string.supplier_saved_payment,
            canRemind = false,
            invoices = true,
            creditDate = R.string.invoice_date,
            paymentDate = R.string.payment_date,
            totals = R.string.totals_supplier,
        )

        fun of(kind: PartyKind) = if (kind == PartyKind.SUPPLIER) SUPPLIER else CUSTOMER
    }
}
