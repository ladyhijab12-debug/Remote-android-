package com.daftar.kredi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class CustomerAdapter(private val onClick: (Customer) -> Unit) : RecyclerView.Adapter<CustomerAdapter.Holder>() {

    private var items: List<CustomerSummary> = emptyList()

    fun submit(list: List<CustomerSummary>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_customer, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatar: TextView = view.findViewById(R.id.avatar)
        private val name: TextView = view.findViewById(R.id.name)
        private val subtitle: TextView = view.findViewById(R.id.subtitle)
        private val balance: TextView = view.findViewById(R.id.balance)

        fun bind(s: CustomerSummary) {
            val ctx = itemView.context
            val c = s.customer
            avatar.text = c.name.trim().take(1)
            name.text = c.name
            subtitle.text = listOf(
                c.phone.takeIf { it.isNotBlank() }?.let { "⁦$it⁩" },
                ctx.getString(R.string.last_activity, Dates.day(s.lastActivity)),
            ).filterNotNull().joinToString("  ·  ")
            when {
                s.balance > 0 -> {
                    balance.text = Money.display(s.balance)
                    balance.setTextColor(ContextCompat.getColor(ctx, R.color.debt))
                }
                s.balance < 0 -> {
                    balance.text = ctx.getString(R.string.balance_advance, Money.display(-s.balance))
                    balance.setTextColor(ContextCompat.getColor(ctx, R.color.advance))
                }
                else -> {
                    balance.text = ctx.getString(R.string.balance_paid)
                    balance.setTextColor(ContextCompat.getColor(ctx, R.color.paid))
                }
            }
            itemView.setOnClickListener { onClick(c) }
        }
    }
}
