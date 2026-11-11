package com.daftar.kredi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/** A customer's lines, newest first, each with what was left to pay right after it. */
class EntryAdapter(private val onLongClick: (Entry) -> Unit) : RecyclerView.Adapter<EntryAdapter.Holder>() {

    private var items: List<Entry> = emptyList()
    var labels: Labels = Labels.of(PartyKind.CUSTOMER)
    private var runningAfter: Map<Long, Long> = emptyMap()

    fun submit(newestFirst: List<Entry>) {
        items = newestFirst
        var sum = 0L
        runningAfter = newestFirst.asReversed().associate { e -> sum += e.signed; e.id to sum }
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_entry, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.title)
        private val note: TextView = view.findViewById(R.id.note)
        private val date: TextView = view.findViewById(R.id.date)
        private val amount: TextView = view.findViewById(R.id.amount)
        private val running: TextView = view.findViewById(R.id.running)

        fun bind(e: Entry) {
            val ctx = itemView.context
            val credit = e.type == EntryType.CREDIT
            title.text = ctx.getString(labels.entry(e.type))
            note.text = e.note
            note.visibility = if (e.note.isBlank()) View.GONE else View.VISIBLE
            date.text = "⁦" + Dates.dayTime(e.time) + "⁩"
            amount.text = (if (credit) "+ " else "− ") + Money.display(e.amount)
            amount.setTextColor(ContextCompat.getColor(ctx, if (credit) R.color.debt else R.color.paid))
            running.text = ctx.getString(R.string.running_balance, Money.display(runningAfter[e.id] ?: 0L))
            itemView.setOnLongClickListener { onLongClick(e); true }
        }
    }
}
