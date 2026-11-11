package com.daftar.kredi

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import java.io.File
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
    private val adapter = EntryAdapter(onClick = { editEntry(it) }, onLongClick = { askDeleteEntry(it) })
    private lateinit var customer: Customer
    private val labels get() = Labels.of(customer.kind)
    private var entries: List<Entry> = emptyList()
    private var balance = 0L

    private lateinit var toolbar: MaterialToolbar
    private lateinit var details: TextView
    private lateinit var balanceLabel: TextView
    private lateinit var balanceView: TextView
    private lateinit var empty: TextView
    private lateinit var totals: TextView

    // Invoice photos: the entry dialog asks, the camera / gallery answers here.
    private var photoCallback: ((String?) -> Unit)? = null
    private val cameraFile by lazy { File(File(cacheDir, "camera").apply { mkdirs() }, "shot.jpg") }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = FileProvider.getUriForFile(this, "$packageName.files", cameraFile)
        deliverPhoto(if (ok) uri else null)
        cameraFile.delete()
    }

    private val pickPicture = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        deliverPhoto(uri)
    }

    private val photoSource = object : Dialogs.PhotoSource {
        override fun take(onDone: (String?) -> Unit) {
            photoCallback = onDone
            cameraFile.delete()
            try {
                takePicture.launch(FileProvider.getUriForFile(this@CustomerActivity, "$packageName.files", cameraFile))
            } catch (e: ActivityNotFoundException) {
                photoCallback = null
                Toast.makeText(this@CustomerActivity, R.string.no_app, Toast.LENGTH_SHORT).show()
            }
        }

        override fun pick(onDone: (String?) -> Unit) {
            photoCallback = onDone
            try {
                pickPicture.launch("image/*")
            } catch (e: ActivityNotFoundException) {
                photoCallback = null
                Toast.makeText(this@CustomerActivity, R.string.no_app, Toast.LENGTH_SHORT).show()
            }
        }
    }

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
        totals = findViewById(R.id.totals)

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
        totals.text = getString(
            labels.totals, Money.display(Ledger.totalCredit(entries)), Money.display(Ledger.totalPaid(entries))
        )
        totals.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
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
        Dialogs.entry(this, labels, type, balance, null, photoSource, ::viewPhoto) { amount, note, time, ref, photo ->
            db.addEntry(Entry(0, customer.id, type, amount, note, time, ref, photo))
            refresh()
            Toast.makeText(
                this,
                if (type == EntryType.CREDIT) labels.savedCredit else labels.savedPayment,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun editEntry(entry: Entry) {
        Dialogs.entry(this, labels, entry.type, balance, entry, photoSource, ::viewPhoto) { amount, note, time, ref, photo ->
            db.updateEntry(entry.copy(amount = amount, note = note, time = time, ref = ref, photo = photo))
            refresh()
        }
    }

    private fun viewPhoto(name: String) {
        startActivity(Intent(this, PhotoActivity::class.java).putExtra(PhotoActivity.EXTRA_NAME, name))
    }

    private fun deliverPhoto(uri: Uri?) {
        val callback = photoCallback ?: return
        photoCallback = null
        if (uri == null) return callback(null)
        val name = try {
            PhotoStore.import(this, uri)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_SHORT).show()
            null
        }
        callback(name)
    }

    private fun askDeleteEntry(entry: Entry) {
        val kind = getString(labels.entry(entry.type))
        Dialogs.confirm(
            this,
            getString(R.string.delete_entry_title),
            "$kind ${Money.display(entry.amount)} — ⁦${Dates.day(entry.time)}⁩",
            R.string.delete,
        ) {
            db.deleteEntry(entry.id)
            PhotoStore.delete(this, entry.photo)
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
            entries.forEach { PhotoStore.delete(this, it.photo) }
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
