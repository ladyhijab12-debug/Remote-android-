package com.daftar.kredi

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * A book's lines, newest first. Each line shows what was left right after it, and each credit /
 * invoice shows how much of it is already paid (payments settle the oldest first).
 */
class EntryAdapter(
    private val onClick: (Entry) -> Unit,
    private val onLongClick: (Entry) -> Unit,
) : RecyclerView.Adapter<EntryAdapter.Holder>() {

    private var items: List<Entry> = emptyList()
    private var runningAfter: Map<Long, Long> = emptyMap()
    private var paid: Map<Long, Long> = emptyMap()
    private val thumbs = LruCache<String, Bitmap>(40)
    var labels: Labels = Labels.of(PartyKind.CUSTOMER)

    fun submit(newestFirst: List<Entry>) {
        items = newestFirst
        var sum = 0L
        runningAfter = newestFirst.asReversed().associate { e -> sum += e.signed; e.id to sum }
        paid = Ledger.paidPerCredit(newestFirst)
        thumbs.evictAll()
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.thumb)
        private val title: TextView = view.findViewById(R.id.title)
        private val note: TextView = view.findViewById(R.id.note)
        private val status: TextView = view.findViewById(R.id.status)
        private val date: TextView = view.findViewById(R.id.date)
        private val amount: TextView = view.findViewById(R.id.amount)
        private val running: TextView = view.findViewById(R.id.running)

        fun bind(e: Entry) {
            val ctx = itemView.context
            val credit = e.type == EntryType.CREDIT
            title.text = if (credit && e.ref.isNotBlank()) {
                ctx.getString(R.string.invoice_title_ref, "⁦${e.ref}⁩")
            } else {
                ctx.getString(labels.entry(e.type))
            }
            note.text = e.note
            note.visibility = if (e.note.isBlank()) View.GONE else View.VISIBLE
            date.text = "⁦" + Dates.day(e.time) + "⁩"
            amount.text = (if (credit) "+ " else "− ") + Money.display(e.amount)
            amount.setTextColor(ContextCompat.getColor(ctx, if (credit) R.color.debt else R.color.paid))
            running.text = ctx.getString(R.string.running_balance, Money.display(runningAfter[e.id] ?: 0L))

            if (credit) {
                val p = paid[e.id] ?: 0L
                status.visibility = View.VISIBLE
                when {
                    p >= e.amount -> {
                        status.setText(R.string.status_paid)
                        status.setTextColor(ContextCompat.getColor(ctx, R.color.paid))
                    }
                    p > 0 -> {
                        status.text = ctx.getString(R.string.status_partial, Money.display(p), Money.display(e.amount - p))
                        status.setTextColor(ContextCompat.getColor(ctx, R.color.advance))
                    }
                    else -> {
                        status.setText(R.string.status_unpaid)
                        status.setTextColor(ContextCompat.getColor(ctx, R.color.debt))
                    }
                }
            } else {
                status.visibility = View.GONE
            }

            val bitmap = if (e.photo.isEmpty()) null else
                thumbs.get(e.photo) ?: PhotoStore.thumbnail(ctx, e.photo, 120)?.also { thumbs.put(e.photo, it) }
            thumb.setImageBitmap(bitmap)
            thumb.visibility = if (bitmap != null) View.VISIBLE else View.GONE

            itemView.setOnClickListener { onClick(e) }
            itemView.setOnLongClickListener { onLongClick(e); true }
        }
    }
}
