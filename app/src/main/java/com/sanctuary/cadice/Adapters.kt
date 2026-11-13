package com.sanctuary.cadice

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.CoroutineScope

class ConversationAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val items: List<ConversationItem>,
    private var serverUrl: String,
    private var token: String,
    private var isDarkTheme: Boolean,
    private val onItemClick: (ConversationItem) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    fun updateThemeAndAuth(dark: Boolean, serverUrl: String, token: String) {
        this.isDarkTheme = dark
        this.serverUrl = serverUrl
        this.token = token
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardAvatar: MaterialCardView = view.findViewById(R.id.card_avatar)
        val tvAvatar: TextView = view.findViewById(R.id.tv_avatar)
        val ivAvatar: ImageView = view.findViewById(R.id.iv_avatar)
        val viewOnlineDot: MaterialCardView = view.findViewById(R.id.view_online_dot)
        val tvName: TextView = view.findViewById(R.id.tv_name)
        val cardBadge: MaterialCardView = view.findViewById(R.id.card_badge)
        val tvBadge: TextView = view.findViewById(R.id.tv_badge)
        val tvTime: TextView = view.findViewById(R.id.tv_time)
        val tvLastMsg: TextView = view.findViewById(R.id.tv_last_msg)
        val cardUnread: MaterialCardView = view.findViewById(R.id.card_unread)
        val tvUnread: TextView = view.findViewById(R.id.tv_unread)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        // 主题着色
        if (isDarkTheme) {
            holder.tvName.setTextColor(Color.parseColor("#F1F5F9"))
            holder.tvLastMsg.setTextColor(Color.parseColor("#94A3B8"))
            holder.tvTime.setTextColor(Color.parseColor("#64748B"))
            holder.cardAvatar.setCardBackgroundColor(
                Color.parseColor(if (item.isGroup) "#4C1D95" else "#334155")
            )
            holder.cardBadge.setCardBackgroundColor(Color.parseColor("#1E293B"))
            holder.tvBadge.setTextColor(Color.parseColor("#94A3B8"))
            holder.viewOnlineDot.strokeColor = Color.parseColor("#0F172A")
        } else {
            holder.tvName.setTextColor(Color.parseColor("#0F172A"))
            holder.tvLastMsg.setTextColor(Color.parseColor("#64748B"))
            holder.tvTime.setTextColor(Color.parseColor("#94A3B8"))
            holder.cardAvatar.setCardBackgroundColor(
                Color.parseColor(if (item.isGroup) "#7C3AED" else "#64748B")
            )
            holder.cardBadge.setCardBackgroundColor(Color.parseColor("#F1F5F9"))
            holder.tvBadge.setTextColor(Color.parseColor("#64748B"))
            holder.viewOnlineDot.strokeColor = Color.parseColor("#FFFFFF")
        }

        holder.tvName.text = item.name
        holder.tvBadge.text = item.badge
        holder.tvLastMsg.text = if (item.lastMsg.isNotEmpty()) item.lastMsg else item.description
        holder.tvTime.text = SanctuaryCore.formatListTime(item.lastTime)

        // 在线小绿点（仅私聊好友）
        holder.viewOnlineDot.visibility = if (!item.isGroup && item.isOnline) View.VISIBLE else View.GONE

        // 未读数角标
        if (item.unreadCount > 0) {
            holder.cardUnread.visibility = View.VISIBLE
            holder.tvUnread.text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString()
        } else {
            holder.cardUnread.visibility = View.GONE
        }

        // 加载真实头像（无头像时自动优雅展示群标或首字母）
        val fallback = if (item.isGroup) "👥" else item.name.take(1)
        SanctuaryCore.loadAvatar(
            context = context,
            scope = scope,
            serverUrl = serverUrl,
            token = token,
            isGroup = item.isGroup,
            id = item.id,
            avatarUpdatedAt = item.avatarUpdatedAt,
            fallbackText = fallback,
            ivAvatar = holder.ivAvatar,
            tvFallback = holder.tvAvatar
        )

        holder.itemView.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = items.size
}

class ChatAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val messages: List<ChatMessage>,
    private val serverUrl: String,
    private val token: String,
    private val isDarkTheme: Boolean
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
        val timeText = SanctuaryCore.formatBubbleTime(msg.createdAt)

        if (holder is RightViewHolder) {
            holder.tvContent.text = msg.content
            holder.tvTime.text = timeText
            holder.tvTime.setTextColor(Color.parseColor(if (isDarkTheme) "#64748B" else "#94A3B8"))
            SanctuaryCore.loadAvatar(
                context = context,
                scope = scope,
                serverUrl = serverUrl,
                token = token,
                isGroup = false,
                id = msg.fromUid,
                avatarUpdatedAt = msg.fromAvatarUpdatedAt,
                fallbackText = msg.fromName.take(1).ifBlank { "我" },
                ivAvatar = holder.ivAvatar,
                tvFallback = holder.tvAvatar
            )
        } else if (holder is LeftViewHolder) {
            holder.tvContent.text = msg.content
            holder.tvSender.text = msg.fromName
            holder.tvTime.text = timeText

            if (isDarkTheme) {
                holder.cardBubble.setCardBackgroundColor(Color.parseColor("#1E293B"))
                holder.cardBubble.strokeColor = Color.parseColor("#334155")
                holder.tvContent.setTextColor(Color.parseColor("#F8FAFC"))
                holder.tvSender.setTextColor(Color.parseColor("#94A3B8"))
                holder.tvTime.setTextColor(Color.parseColor("#64748B"))
            } else {
                holder.cardBubble.setCardBackgroundColor(Color.parseColor("#FFFFFF"))
                holder.cardBubble.strokeColor = Color.parseColor("#E2E8F0")
                holder.tvContent.setTextColor(Color.parseColor("#0F172A"))
                holder.tvSender.setTextColor(Color.parseColor("#64748B"))
                holder.tvTime.setTextColor(Color.parseColor("#94A3B8"))
            }

            SanctuaryCore.loadAvatar(
                context = context,
                scope = scope,
                serverUrl = serverUrl,
                token = token,
                isGroup = false,
                id = msg.fromUid,
                avatarUpdatedAt = msg.fromAvatarUpdatedAt,
                fallbackText = msg.fromName.take(1),
                ivAvatar = holder.ivAvatar,
                tvFallback = holder.tvAvatar
            )
        }
    }

    override fun getItemCount(): Int = messages.size

    class LeftViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tv_avatar_left)
        val ivAvatar: ImageView = view.findViewById(R.id.iv_avatar_left)
        val tvSender: TextView = view.findViewById(R.id.tv_sender_name)
        val tvTime: TextView = view.findViewById(R.id.tv_time_left)
        val cardBubble: MaterialCardView = view.findViewById(R.id.card_bubble_left)
        val tvContent: TextView = view.findViewById(R.id.tv_content_left)
    }

    class RightViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tv_avatar_right)
        val ivAvatar: ImageView = view.findViewById(R.id.iv_avatar_right)
        val tvTime: TextView = view.findViewById(R.id.tv_time_right)
        val tvContent: TextView = view.findViewById(R.id.tv_content_right)
    }
}
