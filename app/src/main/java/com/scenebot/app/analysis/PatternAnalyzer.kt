package com.scenebot.app.analysis

import com.scenebot.app.data.RoundEntity
import kotlin.math.roundToInt

data class SequenceStat(
    val sequence: String,
    val count: Int,
    val percentage: Float
)

data class TransitionStat(
    val fromSequence: String,
    val toSequence: String,
    val count: Int,
    val probability: Float
)

data class StatisticalPredictionResult(
    val hasEnoughData: Boolean,
    val probA: Int,
    val probB: Int,
    val probC: Int,
    val mostLikely: String,
    val confidence: Int,
    val warning: String = "Statistical probability only — Not a guaranteed prediction"
)

data class PredictionAccuracy(
    val last20: Float,
    val last50: Float,
    val overall: Float
)

class PatternAnalyzer {
    fun calculateFrequencies(rounds: List<RoundEntity>): List<SequenceStat> {
        val total = rounds.size.toFloat()
        if (total == 0f) return emptyList()
        return rounds.groupingBy { it.sequence }
            .eachCount()
            .map { (seq, count) ->
                SequenceStat(seq, count, (count / total) * 100f)
            }
            .sortedByDescending { it.count }
    }

    fun calculateTransitions(rounds: List<RoundEntity>): List<TransitionStat> {
        if (rounds.size < 2) return emptyList()
        val transitionCounts = mutableMapOf<Pair<String, String>, Int>()
        val fromTotals = mutableMapOf<String, Int>()

        for (i in 0 until rounds.size - 1) {
            val from = rounds[i].sequence
            val to = rounds[i + 1].sequence
            val key = Pair(from, to)
            transitionCounts[key] = (transitionCounts[key] ?: 0) + 1
            fromTotals[from] = (fromTotals[from] ?: 0) + 1
        }

        return transitionCounts.map { (pair, count) ->
            val totalFrom = fromTotals[pair.first]?.toFloat() ?: 1f
            TransitionStat(
                fromSequence = pair.first,
                toSequence = pair.second,
                count = count,
                probability = (count / totalFrom) * 100f
            )
        }.sortedByDescending { it.count }
    }

    fun calculateStatisticalPrediction(rounds: List<RoundEntity>, currentSeq: String): StatisticalPredictionResult {
        if (rounds.size < 5) {
            return StatisticalPredictionResult(
                hasEnoughData = false,
                probA = 0, probB = 0, probC = 0,
                mostLikely = "?", confidence = 0
            )
        }

        // Global distribution
        val totalCards = (rounds.size * 3).toFloat()
        var countA = 0f
        var countB = 0f
        var countC = 0f
        rounds.forEach { r ->
            listOf(r.card1, r.card2, r.card3).forEach { c ->
                when (c) {
                    "A" -> countA++
                    "B" -> countB++
                    "C" -> countC++
                }
            }
        }
        val globalA = countA / totalCards
        val globalB = countB / totalCards
        val globalC = countC / totalCards

        // Transition frequency from currentSeq
        var transA = 0f
        var transB = 0f
        var transC = 0f
        var transTotal = 0f
        for (i in 0 until rounds.size - 1) {
            if (rounds[i].sequence == currentSeq) {
                val next = rounds[i + 1]
                when (next.card1) {
                    "A" -> transA += 1.5f
                    "B" -> transB += 1.5f
                    "C" -> transC += 1.5f
                }
                transTotal += 1.5f
            }
        }
        val tA = if (transTotal > 0) transA / transTotal else 0.333f
        val tB = if (transTotal > 0) transB / transTotal else 0.333f
        val tC = if (transTotal > 0) transC / transTotal else 0.333f

        // Recency momentum (last 10 rounds)
        val recent = rounds.takeLast(10)
        var recA = 0f
        var recB = 0f
        var recC = 0f
        recent.forEachIndexed { idx, r ->
            val w = 1f + (idx * 0.2f)
            if (r.card1 == "A") recA += w
            if (r.card1 == "B") recB += w
            if (r.card1 == "C") recC += w
        }
        val recTotal = (recA + recB + recC).coerceAtLeast(1f)

        // Blended weights
        val scoreA = globalA * 0.3f + (recA / recTotal) * 0.35f + tA * 0.35f
        val scoreB = globalB * 0.3f + (recB / recTotal) * 0.35f + tB * 0.35f
        val scoreC = globalC * 0.3f + (recC / recTotal) * 0.35f + tC * 0.35f
        val sum = (scoreA + scoreB + scoreC).coerceAtLeast(0.0001f)

        // Robust normalization
        val rawPA = ((scoreA / sum) * 100).roundToInt().coerceIn(0, 100)
        val rawPB = ((scoreB / sum) * 100).roundToInt().coerceIn(0, 100)
        val rawPC = (100 - rawPA - rawPB).coerceIn(0, 100)

        val totalPercents = rawPA + rawPB + rawPC
        val pA = if (totalPercents != 100 && totalPercents > 0) (rawPA * 100 / totalPercents) else rawPA
        val pB = if (totalPercents != 100 && totalPercents > 0) (rawPB * 100 / totalPercents) else rawPB
        val pC = (100 - pA - pB).coerceAtLeast(0)

        val topCard = when {
            pA >= pB && pA >= pC -> "A"
            pB >= pA && pB >= pC -> "B"
            else -> "C"
        }
        val topConf = maxOf(pA, maxOf(pB, pC))

        return StatisticalPredictionResult(
            hasEnoughData = true,
            probA = pA,
            probB = pB,
            probC = pC,
            mostLikely = topCard,
            confidence = topConf
        )
    }
}
