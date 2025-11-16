package com.example.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.model.SwingAnalysis
import com.example.swingfit.R
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SwingAnalysisAdapter(
    private val items: List<SwingAnalysis>,
    private val onClick: (SwingAnalysis) -> Unit
) : RecyclerView.Adapter<SwingAnalysisAdapter.ViewHolder>() {

    inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val imgThumb: ImageView = v.findViewById(R.id.imgThumb)
        val tvTitle: TextView = v.findViewById(R.id.tvTitle)
        val tvDate: TextView = v.findViewById(R.id.tvDate)
        val tvFeedback: TextView = v.findViewById(R.id.tvFeedback)
        val tvTempo: TextView = v.findViewById(R.id.tvTempo)   // 뷰 id는 그대로 쓰되, 내용은 “강점”으로 표시
        val tvPath: TextView = v.findViewById(R.id.tvPath)     // 내용은 “개선”으로 표시

        init {
            v.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
        }
    }

    private fun String?.orDash(): String =
        if (this.isNullOrBlank() || this == "-") "-" else this

    private fun formatDate(createdAt: String?, ts: Long?): String {
        if (!createdAt.isNullOrBlank()) return createdAt
        if (ts != null) {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return fmt.format(Date(ts))
        }
        return ""
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_swing_analysis, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        // 제목: 스윙 타입 우선, 없으면 “스윙 분석”
        holder.tvTitle.text = (item.swingType?.takeIf { it.isNotBlank() && it != "-" } ?: "스윙 분석")

        // 날짜
        holder.tvDate.text = formatDate(item.createdAt, item.timestampMs)

        // 본문: 새 모델 필드에 맞춰 매핑
        holder.tvFeedback.text = "총평: ${item.overallFeedback.orDash()}"
        holder.tvTempo.text    = "강점: ${item.keyStrength.orDash()}"
        holder.tvPath.text     = "개선: ${item.improvement.orDash()}"

        // 썸네일
        val thumb = item.imageUriLocal ?: item.thumbnailUri
        if (!thumb.isNullOrBlank()) {
            Glide.with(holder.itemView)
                .load(android.net.Uri.parse(thumb))
                .placeholder(R.drawable.bg_big_thumb)
                .error(R.drawable.bg_big_thumb)
                .into(holder.imgThumb)
        } else {
            holder.imgThumb.setImageResource(R.drawable.bg_big_thumb)
        }
    }

    override fun getItemCount(): Int = items.size
}