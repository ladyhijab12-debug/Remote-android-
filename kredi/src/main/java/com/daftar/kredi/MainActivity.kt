package com.daftar.kredi

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Start screen: what everyone owes, the customer list, search, and backups. */
class MainActivity : AppCompatActivity() {

    private val db by lazy { (application as KrediApp).db }
    private val adapter = CustomerAdapter { openCustomer(it.id) }
    private lateinit var search: EditText
    private lateinit var total: TextView
    private lateinit var count: TextView
    private lateinit var empty: TextView

    private val backupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { saveBackup(it) }
        }

    private val restoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { askRestore(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        search = findViewById(R.id.search)
        total = findViewById(R.id.total)
        count = findViewById(R.id.count)
        empty = findViewById(R.id.empty)

        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })

        findViewById<ExtendedFloatingActionButton>(R.id.fab).setOnClickListener {
            Dialogs.customer(this, null) { name, phone, note ->
                openCustomer(db.addCustomer(name, phone, note))
            }
        }

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            inflateMenu(R.menu.main)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_backup -> {
                        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        backupLauncher.launch("daftar-kredi-$day.json")
                        true
                    }
                    R.id.action_restore -> {
                        restoreLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        true
                    }
                    else -> false
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val all = db.summaries()
        val query = search.text.toString()
        val shown = if (query.isBlank()) all else db.summaries(query)
        adapter.submit(shown)

        total.text = Money.display(Ledger.totalOwed(all))
        count.text = getString(R.string.customers_count, all.size, all.count { it.balance > 0 })
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(if (all.isEmpty()) R.string.empty_book else R.string.empty_search)
    }

    private fun openCustomer(id: Long) {
        startActivity(Intent(this, CustomerActivity::class.java).putExtra(CustomerActivity.EXTRA_ID, id))
    }

    private fun saveBackup(uri: Uri) {
        try {
            val json = Backup.toJson(db.exportAll())
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.backup_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun askRestore(uri: Uri) {
        val data = try {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            Backup.fromJson(text)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_LONG).show()
            return
        }
        Dialogs.confirm(
            this,
            getString(R.string.restore_title),
            getString(R.string.restore_message, data.customers.size, data.entries.size),
            R.string.restore_button,
        ) {
            db.replaceAll(data)
            refresh()
            Toast.makeText(this, R.string.restore_done, Toast.LENGTH_SHORT).show()
        }
    }
}
