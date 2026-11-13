package com.sanctuary.cadice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
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
    private var fontScale: Float = 1.0f,
    private val onItemClick: (ConversationItem) -> Unit
) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {

    fun updateStyle(dark: Boolean, fontScale: Float, serverUrl: String, token: String) {
        this.isDarkTheme = dark
        this.fontScale = fontScale
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

        holder.tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f * fontScale)
        holder.tvLastMsg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * fontScale)

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

        if (item.draftText.isNotBlank()) {
            val prefix = "[草稿] "
            val ssb = SpannableStringBuilder(prefix).append(item.draftText.replace('\n', ' '))
            ssb.setSpan(
                ForegroundColorSpan(Color.parseColor("#F43F5E")),
                0,
                prefix.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            holder.tvLastMsg.text = ssb
        } else {
            holder.tvLastMsg.text = if (item.lastMsg.isNotEmpty()) item.lastMsg else item.description
        }

        holder.tvTime.text = SanctuaryCore.formatListTime(item.lastTime)
        // 核心修复：以 SanctuaryCore.isUserOnline(item.id) 为唯一权威真相源，消息页与联系人页绿点永远一致！
        val isOnlineNow = !item.isGroup && (SanctuaryCore.isUserOnline(item.id) || item.isOnline)
        holder.viewOnlineDot.visibility = if (isOnlineNow) View.VISIBLE else View.GONE

        if (item.unreadCount > 0) {
            holder.cardUnread.visibility = View.VISIBLE
            holder.tvUnread.text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString()
        } else {
            holder.cardUnread.visibility = View.GONE
        }

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
    private val serverUrl: String,
    private val token: String,
    private var isDarkTheme: Boolean,
    private var fontScale: Float = 1.0f,
    private val onMessageLongClick: ((ChatMessage) -> Unit)? = null,
    private val onRetryFailedClick: ((ChatMessage) -> Unit)? = null,
    private val onImageClick: ((ChatMessage) -> Unit)? = null
) : ListAdapter<ChatListRow, RecyclerView.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        const val TYPE_DATE_DIVIDER = 0
        const val TYPE_LEFT = 1
        const val TYPE_RIGHT = 2

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<ChatListRow>() {
            override fun areItemsTheSame(oldItem: ChatListRow, newItem: ChatListRow): Boolean {
                return oldItem.stableId == newItem.stableId
            }

            override fun areContentsTheSame(oldItem: ChatListRow, newItem: ChatListRow): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (val row = getItem(position)) {
            is ChatListRow.DateDivider -> TYPE_DATE_DIVIDER
            is ChatListRow.MessageRow -> if (row.message.isSelf) TYPE_RIGHT else TYPE_LEFT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_DATE_DIVIDER -> DateViewHolder(inflater.inflate(R.layout.item_date_divider, parent, false))
            TYPE_RIGHT -> RightViewHolder(inflater.inflate(R.layout.item_message_right, parent, false))
            else -> LeftViewHolder(inflater.inflate(R.layout.item_message_left, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is ChatListRow.DateDivider -> {
                val dh = holder as DateViewHolder
                dh.tvDate.text = row.label
                if (isDarkTheme) {
                    dh.cardDate.setCardBackgroundColor(Color.parseColor("#1E293B"))
                    dh.tvDate.setTextColor(Color.parseColor("#94A3B8"))
                } else {
                    dh.cardDate.setCardBackgroundColor(Color.parseColor("#E2E8F0"))
                    dh.tvDate.setTextColor(Color.parseColor("#475569"))
                }
            }
            is ChatListRow.MessageRow -> bindMessage(holder, row.message)
        }
    }

    private fun bindMessage(holder: RecyclerView.ViewHolder, msg: ChatMessage) {
        val timeText = SanctuaryCore.formatBubbleTime(msg.createdAt)

        if (holder is RightViewHolder) {
            if (msg.replyToMid > 0L) {
                holder.layoutReply.visibility = View.VISIBLE
                holder.tvReply.text = msg.replyPreview.ifBlank { "引用消息 #${msg.replyToMid}" }
            } else {
                holder.layoutReply.visibility = View.GONE
            }

            if (msg.isImageMessage) {
                holder.ivImage.visibility = View.VISIBLE
                holder.tvContent.visibility = View.GONE
                SanctuaryCore.loadChatImage(context, scope, serverUrl, msg.filePath, msg.localFileUri, holder.ivImage)
                holder.ivImage.setOnClickListener { onImageClick?.invoke(msg) }
                holder.ivImage.setOnLongClickListener {
                    onMessageLongClick?.invoke(msg)
                    true
                }
            } else {
                holder.ivImage.visibility = View.GONE
                holder.tvContent.visibility = View.VISIBLE
                holder.tvContent.text = msg.content
                holder.tvContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * fontScale)
            }

            holder.tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * fontScale)
            holder.tvTime.setTextColor(Color.parseColor(if (isDarkTheme) "#64748B" else "#94A3B8"))

            when (msg.syncStatus) {
                ChatMessage.STATUS_SENDING -> {
                    holder.tvTime.text = "$timeText · ⏳"
                    holder.cardBubble.alpha = 0.85f
                    holder.tvSendStatus.visibility = View.VISIBLE
                    holder.tvSendStatus.text = "⏳"
                    holder.tvSendStatus.setTextColor(Color.parseColor("#94A3B8"))
                    holder.tvSendStatus.setOnClickListener(null)
                }
                ChatMessage.STATUS_FAILED -> {
                    holder.tvTime.text = "$timeText · 未送达"
                    holder.cardBubble.alpha = 0.90f
                    holder.tvSendStatus.visibility = View.VISIBLE
                    holder.tvSendStatus.text = "❗重发"
                    holder.tvSendStatus.setTextColor(Color.parseColor("#EF4444"))
                    holder.tvSendStatus.setOnClickListener {
                        onRetryFailedClick?.invoke(msg)
                    }
                }
                else -> {
                    holder.tvTime.text = "$timeText ✓"
                    holder.cardBubble.alpha = 1.0f
                    holder.tvSendStatus.visibility = View.GONE
                    holder.tvSendStatus.setOnClickListener(null)
                }
            }

            holder.cardBubble.setOnLongClickListener {
                onMessageLongClick?.invoke(msg)
                true
            }
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
            if (msg.replyToMid > 0L) {
                holder.layoutReply.visibility = View.VISIBLE
                holder.tvReply.text = msg.replyPreview.ifBlank { "引用消息 #${msg.replyToMid}" }
            } else {
                holder.layoutReply.visibility = View.GONE
            }

            if (msg.isImageMessage) {
                holder.ivImage.visibility = View.VISIBLE
                holder.tvContent.visibility = View.GONE
                SanctuaryCore.loadChatImage(context, scope, serverUrl, msg.filePath, msg.localFileUri, holder.ivImage)
                holder.ivImage.setOnClickListener { onImageClick?.invoke(msg) }
                holder.ivImage.setOnLongClickListener {
                    onMessageLongClick?.invoke(msg)
                    true
                }
            } else {
                holder.ivImage.visibility = View.GONE
                holder.tvContent.visibility = View.VISIBLE
                holder.tvContent.text = msg.content
                holder.tvContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f * fontScale)
            }

            holder.tvSender.text = msg.fromName
            holder.tvSender.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * fontScale)
            holder.tvTime.text = timeText
            holder.tvTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * fontScale)
            holder.cardBubble.setOnLongClickListener {
                onMessageLongClick?.invoke(msg)
                true
            }

            if (isDarkTheme) {
                holder.cardBubble.setCardBackgroundColor(Color.parseColor("#1E293B"))
                holder.cardBubble.strokeColor = Color.parseColor("#334155")
                holder.tvContent.setTextColor(Color.parseColor("#F8FAFC"))
                holder.tvReply.setTextColor(Color.parseColor("#94A3B8"))
                holder.tvSender.setTextColor(Color.parseColor("#94A3B8"))
                holder.tvTime.setTextColor(Color.parseColor("#64748B"))
            } else {
                holder.cardBubble.setCardBackgroundColor(Color.parseColor("#FFFFFF"))
                holder.cardBubble.strokeColor = Color.parseColor("#E2E8F0")
                holder.tvContent.setTextColor(Color.parseColor("#0F172A"))
                holder.tvReply.setTextColor(Color.parseColor("#64748B"))
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

    class DateViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardDate: MaterialCardView = view.findViewById(R.id.card_date_divider)
        val tvDate: TextView = view.findViewById(R.id.tv_date_divider)
    }

    class LeftViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tv_avatar_left)
        val ivAvatar: ImageView = view.findViewById(R.id.iv_avatar_left)
        val tvSender: TextView = view.findViewById(R.id.tv_sender_name)
        val tvTime: TextView = view.findViewById(R.id.tv_time_left)
        val cardBubble: MaterialCardView = view.findViewById(R.id.card_bubble_left)
        val layoutReply: LinearLayout = view.findViewById(R.id.layout_reply_left)
        val tvReply: TextView = view.findViewById(R.id.tv_reply_left)
        val ivImage: ImageView = view.findViewById(R.id.iv_image_left)
        val tvContent: TextView = view.findViewById(R.id.tv_content_left)
    }

    class RightViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAvatar: TextView = view.findViewById(R.id.tv_avatar_right)
        val ivAvatar: ImageView = view.findViewById(R.id.iv_avatar_right)
        val tvTime: TextView = view.findViewById(R.id.tv_time_right)
        val tvSendStatus: TextView = view.findViewById(R.id.tv_send_status)
        val cardBubble: MaterialCardView = view.findViewById(R.id.card_bubble_right)
        val layoutReply: LinearLayout = view.findViewById(R.id.layout_reply_right)
        val tvReply: TextView = view.findViewById(R.id.tv_reply_right)
        val ivImage: ImageView = view.findViewById(R.id.iv_image_right)
        val tvContent: TextView = view.findViewById(R.id.tv_content_right)
    }
}

/**
 * ↩️ 右滑引用回复手势处理器（借鉴 GetStream SwipeReplyCallback）
 */
class SwipeReplyCallback(
    private val adapter: ChatAdapter,
    private val onReplyTriggered: (ChatMessage) -> Unit
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {

    private var triggered = false
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A78BFA")
        textSize = 36f
        textAlign = Paint.Align.CENTER
    }

    override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        val pos = viewHolder.adapterPosition
        if (pos == RecyclerView.NO_POSITION) return 0
        val row = adapter.currentList.getOrNull(pos) ?: return 0
        if (row !is ChatListRow.MessageRow || row.message.syncStatus != ChatMessage.STATUS_SENT) {
            return 0
        }
        return super.getSwipeDirs(recyclerView, viewHolder)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // 不真正移除条目，而是回弹复位
    }

    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 1.5f

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val maxPull = recyclerView.resources.displayMetrics.density * 72f
        val clampedDx = dX.coerceIn(0f, maxPull)
        if (clampedDx >= maxPull * 0.72f && isCurrentlyActive && !triggered) {
            triggered = true
            val pos = viewHolder.adapterPosition
            val row = if (pos != RecyclerView.NO_POSITION) adapter.currentList.getOrNull(pos) else null
            if (row is ChatListRow.MessageRow) {
                viewHolder.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                onReplyTriggered(row.message)
            }
        }
        if (dX == 0f || !isCurrentlyActive) {
            triggered = false
        }
        if (clampedDx > 16f) {
            val cy = viewHolder.itemView.top + viewHolder.itemView.height / 2f + 12f
            val alpha = ((clampedDx / maxPull) * 255f).toInt().coerceIn(40, 255)
            textPaint.alpha = alpha
            c.drawText("↩", clampedDx * 0.45f, cy, textPaint)
        }
        super.onChildDraw(c, recyclerView, viewHolder, clampedDx, dY, actionState, isCurrentlyActive)
    }
}
