package com.example.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.model.BallAnalysis
import com.example.swingfit.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BallAnalysisAdapter(
    private val items: List<BallAnalysis>,
    private val onClick: (BallAnalysis) -> Unit
) : RecyclerView.Adapter<BallAnalysisAdapter.ViewHolder>() {

    inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val imgThumb: ImageView = v.findViewById(R.id.imgThumb)
        val tvTitle: TextView = v.findViewById(R.id.tvTitle)
        val tvDate: TextView = v.findViewById(R.id.tvDate)
        val tvCarry: TextView = v.findViewById(R.id.tvCarry)
        val tvLaunchAngle: TextView = v.findViewById(R.id.tvLaunchAngle)
        val tvBallSpeed: TextView = v.findViewById(R.id.tvBallSpeed)

        init {
            v.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
        }
    }

    private fun String?.orDash() = if (this.isNullOrBlank()) "-" else this

    private fun formatDate(createdAt: String?, tsMs: Long?): String {
        if (!createdAt.isNullOrBlank()) return createdAt
        if (tsMs != null) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(tsMs))
        }
        return ""
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ball_analysis, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        // 제목 & 날짜
        holder.tvTitle.text = "총 비거리: ${item.totalDistance.orDash()}"
        holder.tvDate.text  = formatDate(item.createdAt, item.timestampMs)

        // 요약 데이터
        holder.tvCarry.text       = "캐리: ${item.carryDistance.orDash()}"
        holder.tvLaunchAngle.text = "발사각: ${item.launchAngle.orDash()}"
        holder.tvBallSpeed.text   = "볼스피드: ${item.ballSpeed.orDash()}"

        // ★ 썸네일 로딩 (RecordsRepository에서 thumbnailUri에 thumbUriLocal 주입됨)
        val thumb = item.thumbnailUri
        if (!thumb.isNullOrBlank()) {
            Glide.with(holder.itemView)
                .load(thumb)
                .placeholder(R.drawable.bg_big_thumb)
                .into(holder.imgThumb)
        } else {
            holder.imgThumb.setImageResource(R.drawable.bg_big_thumb)
        }
    }

    override fun getItemCount(): Int = items.size
}