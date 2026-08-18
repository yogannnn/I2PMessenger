package ru.servertronix.i2pmessenger

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

class MessageAdapter(
    private var messages: List<Message>
) : RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isMine) VIEW_TYPE_MINE else VIEW_TYPE_OTHER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_MINE -> {
                val view = inflater.inflate(R.layout.item_message_mine, parent, false)
                MessageViewHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_message_other, parent, false)
                MessageViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.bind(message)
    }

    override fun getItemCount(): Int = messages.size

    fun updateMessages(newMessages: List<Message>) {
        messages = newMessages
        notifyDataSetChanged()
    }

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvText: TextView = itemView.findViewById(R.id.tvMessageText)
        private val tvTime: TextView = itemView.findViewById(R.id.tvMessageTime)

        fun bind(message: Message) {
            tvText.text = message.text
            val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            tvTime.text = dateFormat.format(Date(message.timestamp))
        }
    }

    companion object {
        private const val VIEW_TYPE_MINE = 1
        private const val VIEW_TYPE_OTHER = 2
    }
}