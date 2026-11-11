package com.daftar.kredi

import android.app.DatePickerDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Calendar

object Dialogs {

    /** Add ([existing] null) or edit a customer or supplier. */
    fun customer(
        context: Context,
        kind: PartyKind,
        existing: Customer?,
        onSave: (name: String, phone: String, note: String) -> Unit,
    ) {
        val labels = Labels.of(kind)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_customer, null)
        val nameLayout = view.findViewById<TextInputLayout>(R.id.name_layout)
        val name = view.findViewById<TextInputEditText>(R.id.name)
        val phone = view.findViewById<TextInputEditText>(R.id.phone)
        val note = view.findViewById<TextInputEditText>(R.id.note)
        nameLayout.hint = context.getString(labels.nameHint)
        existing?.let { name.setText(it.name); phone.setText(it.phone); note.setText(it.note) }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) labels.newTitle else R.string.customer_edit_title)
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

    /** Lets the entry dialog ask the screen for a camera shot or a gallery picture (stored name, or null). */
    interface PhotoSource {
        fun take(onDone: (String?) -> Unit)
        fun pick(onDone: (String?) -> Unit)
    }

    /**
     * New ([existing] null) or edited credit / payment. [balance] is what is owed now, offered as
     * "pay everything". For suppliers a credit is an invoice, with a number and a photo.
     */
    fun entry(
        context: Context,
        labels: Labels,
        type: EntryType,
        balance: Long,
        existing: Entry?,
        photos: PhotoSource,
        onViewPhoto: (String) -> Unit,
        onSave: (amount: Long, note: String, time: Long, ref: String, photo: String) -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_entry, null)
        val amountLayout = view.findViewById<TextInputLayout>(R.id.amount_layout)
        val amount = view.findViewById<TextInputEditText>(R.id.amount)
        val refLayout = view.findViewById<TextInputLayout>(R.id.ref_layout)
        val ref = view.findViewById<TextInputEditText>(R.id.ref)
        val noteLayout = view.findViewById<TextInputLayout>(R.id.note_layout)
        val note = view.findViewById<TextInputEditText>(R.id.note)
        val dateButton = view.findViewById<MaterialButton>(R.id.date)
        val photoRow = view.findViewById<View>(R.id.photo_row)
        val preview = view.findViewById<ImageView>(R.id.photo_preview)
        val removePhoto = view.findViewById<MaterialButton>(R.id.photo_remove)
        val credit = type == EntryType.CREDIT
        val invoice = credit && labels.invoices
        noteLayout.hint = context.getString(if (credit) labels.creditNote else R.string.payment_note)
        refLayout.visibility = if (invoice) View.VISIBLE else View.GONE
        photoRow.visibility = if (invoice) View.VISIBLE else View.GONE

        existing?.let {
            amount.setText(Money.plain(it.amount))
            note.setText(it.note)
            ref.setText(it.ref)
        }

        // Photo shown in the dialog; photos taken here and then dropped are deleted again.
        val original = existing?.photo.orEmpty()
        var photo = original
        val taken = mutableListOf<String>()
        fun showPhoto() {
            val thumb = if (photo.isEmpty()) null else PhotoStore.thumbnail(context, photo, 200)
            preview.setImageBitmap(thumb)
            preview.visibility = if (thumb != null) View.VISIBLE else View.GONE
            removePhoto.visibility = if (photo.isNotEmpty()) View.VISIBLE else View.GONE
        }
        val gotPhoto: (String?) -> Unit = { name ->
            if (name != null) {
                taken += name
                photo = name
                showPhoto()
            }
        }
        showPhoto()
        view.findViewById<MaterialButton>(R.id.photo_take).setOnClickListener { photos.take(gotPhoto) }
        view.findViewById<MaterialButton>(R.id.photo_pick).setOnClickListener { photos.pick(gotPhoto) }
        removePhoto.setOnClickListener { photo = ""; showPhoto() }
        preview.setOnClickListener { if (photo.isNotEmpty()) onViewPhoto(photo) }

        // Today by default; an earlier day for an old invoice or when copying a paper notebook.
        val time = Calendar.getInstance().apply { existing?.let { timeInMillis = it.time } }
        val dateLabel = if (credit) labels.creditDate else labels.paymentDate
        fun showDate() {
            dateButton.text = context.getString(dateLabel, "\u2066" + Dates.day(time.timeInMillis) + "\u2069")
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

        val title = when {
            existing != null -> R.string.edit_entry_title
            credit -> labels.creditTitle
            else -> labels.paymentTitle
        }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
        if (!credit && existing == null && balance > 0) builder.setNeutralButton(R.string.pay_all, null)
        val dialog = builder.create()
        var saved = false
        dialog.setOnDismissListener {
            val keep = if (saved) photo else original
            taken.filter { it != keep }.forEach { PhotoStore.delete(context, it) }
            if (saved && original.isNotEmpty() && original != photo) PhotoStore.delete(context, original)
        }
        if (existing == null) dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.setOnShowListener {
            if (existing == null) amount.requestFocus()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                amount.setText(Money.plain(balance))
                amount.setSelection(amount.text?.length ?: 0)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cents = Money.parse(amount.text.toString())
                if (cents == null) {
                    amountLayout.error = context.getString(R.string.amount_invalid)
                } else {
                    saved = true
                    onSave(
                        cents, note.text.toString().trim(), time.timeInMillis,
                        if (invoice) ref.text.toString().trim() else "",
                        if (invoice) photo else "",
                    )
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
