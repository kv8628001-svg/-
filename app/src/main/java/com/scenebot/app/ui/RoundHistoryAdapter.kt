package com.scenebot.app.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.scenebot.app.R
import com.scenebot.app.data.RoundEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RoundHistoryAdapter : RecyclerView.Adapter<RoundHistoryAdapter.RoundViewHolder>() {
    private var rounds: List<RoundEntity> = emptyList()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun submitList(newRounds: List<RoundEntity>) {
        rounds = newRounds
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoundViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_round_history, parent, false)
        return RoundViewHolder(view)
    }

    override fun onBindViewHolder(holder: RoundViewHolder, position: Int) {
        holder.bind(rounds[position])
    }

    override fun getItemCount(): Int = rounds.size

    inner class RoundViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvRoundNum: TextView = itemView.findViewById(R.id.tv_item_round_num)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_item_time)
        private val tvCards: TextView = itemView.findViewById(R.id.tv_item_cards)
        private val tvWinner: TextView = itemView.findViewById(R.id.tv_item_winner)
        private val tvPrediction: TextView = itemView.findViewById(R.id.tv_item_prediction)
        private val tvHitStatus: TextView = itemView.findViewById(R.id.tv_item_hit_status)
        private val tvDetectionStatus: TextView = itemView.findViewById(R.id.tv_item_detection_status)

        fun bind(round: RoundEntity) {
            tvRoundNum.text = "#${round.roundSequenceNumber}"
            tvTime.text = dateFormat.format(Date(round.timestamp))
            tvCards.text = "Cards: ${round.card1} | ${round.card2} | ${round.card3}"
            tvWinner.text = "Winner: Spot ${round.actualWinner}"

            val predStr = "Pred: ${round.predictedWinner} (A:${round.predictedProbA}% B:${round.predictedProbB}% C:${round.predictedProbC}%)"
            tvPrediction.text = predStr

            if (round.predictionCorrect) {
                tvHitStatus.text = "HIT ✓"
                tvHitStatus.setTextColor(Color.parseColor("#10B981")) // Green
            } else {
                tvHitStatus.text = "MISS ✗"
                tvHitStatus.setTextColor(Color.parseColor("#EF4444")) // Red
            }

            tvDetectionStatus.text = round.detectionStatus
            when (round.detectionStatus) {
                "Verified" -> tvDetectionStatus.setTextColor(Color.parseColor("#10B981"))
                "Result Not Verified" -> tvDetectionStatus.setTextColor(Color.parseColor("#F59E0B"))
                else -> tvDetectionStatus.setTextColor(Color.parseColor("#9CA3AF"))
            }
        }
    }
}
