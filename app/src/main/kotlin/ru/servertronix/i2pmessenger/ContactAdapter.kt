package ru.servertronix.i2pmessenger

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ContactAdapter(
    private var contacts: List<Contact>,
    private val onItemClick: (Contact) -> Unit,
    private val onItemLongClick: (Contact, Int) -> Unit
) : RecyclerView.Adapter<ContactAdapter.ContactViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_contact, parent, false)
        return ContactViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = contacts[position]
        holder.bind(contact)
        holder.itemView.setOnClickListener { onItemClick(contact) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(contact, position)
            true
        }
    }

    override fun getItemCount(): Int = contacts.size

    fun updateContacts(newContacts: List<Contact>) {
        contacts = newContacts
        notifyDataSetChanged()
    }

    inner class ContactViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvContactName)
        private val tvAddress: TextView = itemView.findViewById(R.id.tvContactAddress)

        fun bind(contact: Contact) {
            tvName.text = contact.name
            tvAddress.text = contact.address.take(20) + "..."
        }
    }
}