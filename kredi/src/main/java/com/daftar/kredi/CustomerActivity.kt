package com.daftar.kredi

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

/** One customer's or supplier's page: balance, add credit / payment, call, WhatsApp, and the history. */
class CustomerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "customer_id"
    }

    private val db by lazy { (application as KrediApp).db }
    private val adapter = EntryAdapter { askDeleteEntry(it) }
    private lateinit var customer: Customer
    private val labels get() = Labels.of(customer.kind)
    private var entries: List<Entry> = emptyList()
    private var balance = 0L

    private lateinit var toolbar: MaterialToolbar
    private lateinit var details: TextView
    private lateinit var balanceLabel: TextView
    private lateinit var balanceView: TextView
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customer)

        val found = db.customer(intent.getLongExtra(EXTRA_ID, -1))
        if (found == null) {
            finish()
            return
        }
        customer = found
        adapter.labels = labels

        toolbar = findViewById(R.id.toolbar)
        details = findViewById(R.id.details)
        balanceLabel = findViewById(R.id.balance_label)
        balanceView = findViewById(R.id.balance)
        empty = findViewById(R.id.empty)

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.customer)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_edit -> { edit(); true }
                R.id.action_delete -> { askDeleteCustomer(); true }
                else -> false
            }
        }

        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@CustomerActivity)
            adapter = this@CustomerActivity.adapter
        }

        findViewById<MaterialButton>(R.id.btn_credit).apply {
            setText(labels.addCredit)
            setOnClickListener { addEntry(EntryType.CREDIT) }
        }
        findViewById<MaterialButton>(R.id.btn_payment).apply {
            setText(labels.addPayment)
            setOnClickListener { addEntry(EntryType.PAYMENT) }
        }
        findViewById<MaterialButton>(R.id.btn_call).setOnClickListener { call() }
        findViewById<MaterialButton>(R.id.btn_whatsapp).apply {
            // A payment reminder only makes sense for customers.
            visibility = if (labels.canRemind) View.VISIBLE else View.GONE
            setOnClickListener { remind() }
        }
        findViewById<MaterialButton>(R.id.btn_share).setOnClickListener { shareStatement() }

        refresh()
    }

    private fun refresh() {
        toolbar.title = customer.name
        details.text = listOf(customer.phone.takeIf { it.isNotBlank() }?.let { "⁦$it⁩" }, customer.note)
            .filter { !it.isNullOrBlank() }.joinToString("  ·  ")
        details.visibility = if (details.text.isEmpty()) View.GONE else View.VISIBLE

        entries = db.entries(customer.id)
        balance = Ledger.balance(entries)
        adapter.submit(entries)
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        when {
            balance > 0 -> {
                balanceLabel.setText(labels.balanceOwes)
                balanceView.text = Money.display(balance)
            }
            balance < 0 -> {
                balanceLabel.setText(labels.balanceAdvance)
                balanceView.text = Money.display(-balance)
            }
            else -> {
                balanceLabel.setText(labels.balancePaid)
                balanceView.text = Money.display(0)
            }
        }
    }

    private fun addEntry(type: EntryType) {
        Dialogs.entry(this, labels, type, balance) { amount, note, time ->
            db.addEntry(customer.id, type, amount, note, time)
            refresh()
            Toast.makeText(
                this,
                if (type == EntryType.CREDIT) labels.savedCredit else labels.savedPayment,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun askDeleteEntry(entry: Entry) {
        val kind = getString(labels.entry(entry.type))
        Dialogs.confirm(
            this,
            getString(R.string.delete_entry_title),
            "$kind ${Money.display(entry.amount)} — ⁦${Dates.dayTime(entry.time)}⁩",
            R.string.delete,
        ) {
            db.deleteEntry(entry.id)
            refresh()
            Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
        }
    }

    private fun edit() {
        Dialogs.customer(this, customer.kind, customer) { name, phone, note ->
            db.updateCustomer(customer.id, name, phone, note)
            customer = customer.copy(name = name, phone = phone, note = note)
            refresh()
        }
    }

    private fun askDeleteCustomer() {
        Dialogs.confirm(
            this,
            getString(R.string.delete_customer_title, customer.name),
            getString(R.string.delete_customer_message),
            R.string.delete,
        ) {
            db.deleteCustomer(customer.id)
            Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun call() {
        if (customer.phone.isBlank()) return noPhone()
        start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", customer.phone, null)))
    }

    private fun remind() {
        val number = Ledger.whatsappNumber(customer.phone) ?: return noPhone()
        val text = Ledger.reminderMessage(customer.name, balance)
        start(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number?text=" + Uri.encode(text))))
    }

    private fun shareStatement() {
        val text = Ledger.statement(customer.name, entries, customer.kind) { Dates.day(it) }
        val number = Ledger.whatsappNumber(customer.phone)
        if (number != null) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$number?text=" + Uri.encode(text))))
        } else {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            start(Intent.createChooser(send, getString(R.string.share_statement)))
        }
    }

    private fun noPhone() {
        Toast.makeText(this, R.string.no_phone, Toast.LENGTH_LONG).show()
    }

    private fun start(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }
}
