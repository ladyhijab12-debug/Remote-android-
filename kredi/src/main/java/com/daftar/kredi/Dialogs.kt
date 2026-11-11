package com.daftar.kredi

import android.app.DatePickerDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar

object Dialogs {

    /** Add ([existing] null) or edit a customer. */
    fun customer(context: Context, existing: Customer?, onSave: (name: String, phone: String, note: String) -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_customer, null)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.name_layout)
        val name = view.findViewById<TextInputEditText>(R.id.name)
        val phone = view.findViewById<TextInputEditText>(R.id.phone)
        val note = view.findViewById<TextInputEditText>(R.id.note)
        existing?.let { name.setText(it.name); phone.setText(it.phone); note.setText(it.note) }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) R.string.customer_new_title else R.string.customer_edit_title)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.setOnShowListener {
            name.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                if (n.isEmpty()) {
                    nameLayout.error = context.getString(R.string.name_required)
                } else {
                    onSave(n, phone.text.toString().trim(), note.text.toString().trim())
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    /** New credit or payment. [balance] is what the customer owes now, offered as "pay everything". */
    fun entry(
        context: Context,
        type: EntryType,
        balance: Long,
        onSave: (amount: Long, note: String, time: Long) -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_entry, null)
        val amountLayout = view.findViewById<TextInputLayout>(R.id.amount_layout)
        val amount = view.findViewById<TextInputEditText>(R.id.amount)
        val noteLayout = view.findViewById<TextInputLayout>(R.id.note_layout)
        val note = view.findViewById<TextInputEditText>(R.id.note)
        val dateButton = view.findViewById<MaterialButton>(R.id.date)
        val credit = type == EntryType.CREDIT
        noteLayout.hint = context.getString(if (credit) R.string.entry_note else R.string.payment_note)

        // Today by default; an earlier day can be picked when copying an old paper notebook.
        val time = Calendar.getInstance()
        fun showDate() {
            dateButton.text = context.getString(R.string.date, "⁦" + Dates.day(time.timeInMillis) + "⁩")
        }
        showDate()
        dateButton.setOnClickListener {
            DatePickerDialog(context, { _, y, m, d ->
                time.set(y, m, d)
                showDate()
            }, time.get(Calendar.YEAR), time.get(Calendar.MONTH), time.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }

        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(if (credit) R.string.credit_title else R.string.payment_title)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
        if (!credit && balance > 0) builder.setNeutralButton(R.string.pay_all, null)
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.setOnShowListener {
            amount.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                amount.setText(Money.plain(balance))
                amount.setSelection(amount.text?.length ?: 0)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cents = Money.parse(amount.text.toString())
                if (cents == null) {
                    amountLayout.error = context.getString(R.string.amount_invalid)
                } else {
                    onSave(cents, note.text.toString().trim(), time.timeInMillis)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    fun confirm(context: Context, title: String, message: String?, button: Int, onYes: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(button) { _, _ -> onYes() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
