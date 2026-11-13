package com.sanctuary.cadice

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ConversationAdapter(
    private val items: List<ConversationItem>,
    private val onItemClick: (ConversationItem) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tv_avatar)
        val tvName: TextView = view.findViewById(R.id.tv_name)
        val tvBadge: TextView = view.findViewById(R.id.tv_badge)
        val tvLastMsg: TextView = view.findViewById(R.id.tv_last_msg)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvBadge.text = item.badge
        holder.tvLastMsg.text = if (item.lastMsg.isNotEmpty()) item.lastMsg else item.description

        // 头像展示首字母或特定 emoji
        holder.tvAvatar.text = if (item.isGroup) "👥" else item.name.take(1)

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = items.size
}

class ChatAdapter(
    private val messages: List<ChatMessage>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_LEFT = 1
        private const val TYPE_RIGHT = 2
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isSelf) TYPE_RIGHT else TYPE_LEFT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_RIGHT) {
            val view = inflater.inflate(R.layout.item_message_right, parent, false)
            RightViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_message_left, parent, false)
            LeftViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        if (holder is RightViewHolder) {
            holder.tvContent.text = msg.content
            holder.tvAvatar.text = "我"
        } else if (holder is LeftViewHolder) {
            holder.tvContent.text = msg.content
            holder.tvSender.text = msg.fromName
            holder.tvAvatar.text = msg.fromName.take(1)
        }
    }

    override fun getItemCount(): Int = messages.size

    class LeftViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tv_avatar_left)
        val tvSender: TextView = view.findViewById(R.id.tv_sender_name)
        val tvContent: TextView = view.findViewById(R.id.tv_content_left)
    }

    class RightViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tv_avatar_right)
        val tvContent: TextView = view.findViewById(R.id.tv_content_right)
    }
}
