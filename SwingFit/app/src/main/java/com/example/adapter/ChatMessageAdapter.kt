package com.example.adapter

import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.swingfit.databinding.ItemChatMessageBinding
import java.util.UUID

/**
 * 채팅 메시지 어댑터
 * - item_chat_message.xml 기반 (좌: Bot, 우: User)
 * - ListAdapter + DiffUtil 적용
 * - URL 자동 링크(Linkify)
 */
class ChatMessageAdapter :
    ListAdapter<ChatMessage, ChatMessageAdapter.ChatViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean =
                oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ItemChatMessageBinding.inflate(inflater, parent, false)
        return ChatViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ChatViewHolder) {
        super.onViewRecycled(holder)
        holder.unbind()
    }

    inner class ChatViewHolder(private val b: ItemChatMessageBinding) :
        RecyclerView.ViewHolder(b.root) {

        private val userWrap: View = b.layoutUser
        private val botWrap: View = b.layoutBot
        private val tvUser: TextView = b.tvUserMessage
        private val tvBot: TextView = b.tvBotMessage

        fun bind(item: ChatMessage) {
            if (item.role == ChatRole.USER) {
                // 사용자 말풍선만 표시
                userWrap.visibility = View.VISIBLE
                botWrap.visibility = View.GONE
                tvUser.text = item.text
                Linkify.addLinks(tvUser, Linkify.WEB_URLS)
            } else {
                // 봇 말풍선만 표시
                userWrap.visibility = View.GONE
                botWrap.visibility = View.VISIBLE
                tvBot.text = item.text
                Linkify.addLinks(tvBot, Linkify.WEB_URLS)
            }
        }

        fun unbind() {
            tvUser.text = null
            tvBot.text = null
            userWrap.visibility = View.GONE
            botWrap.visibility = View.GONE
        }
    }

    /** 단건 추가(append) 헬퍼 */
    fun append(message: ChatMessage) {
        val newList = currentList.toMutableList()
        newList.add(message)
        submitList(newList)
    }

    /** 다건 추가(appendAll) 헬퍼 */
    fun appendAll(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        val newList = currentList.toMutableList()
        newList.addAll(messages)
        submitList(newList)
    }

    /** 전체 교체(set) 헬퍼 */
    fun set(messages: List<ChatMessage>) {
        submitList(messages.toList())
    }

    /** 비우기 */
    fun clearAll() {
        submitList(emptyList())
    }
}

/** 채팅 메시지 모델 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String
)

enum class ChatRole { USER, BOT }