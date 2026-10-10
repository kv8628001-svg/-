package com.scenebot.app.analysis

import com.scenebot.app.data.RoundEntity
import kotlin.math.pow
import kotlin.math.roundToInt

data class StatisticalPredictionResult(
    val hasEnoughData: Boolean,
    val dataStatus: String,
    val probA: Int,
    val probB: Int,
    val probC: Int,
    val mostLikely: String,
    val confidence: Int,
    val rationale: String,
    val sampleSize: Int,
    val walkForwardAccuracy: Float,
    val brierScore: Float,
    val baselineComparison: String,
    val warning: String = "Statistical frequency analysis only. Past rounds do not guarantee future winners in independent RNG games."
)

data class BacktestReport(
    val totalTested: Int,
    val correctCount: Int,
    val accuracyPercentage: Float,
    val brierScore: Float,
    val baselineAccuracy: Float = 33.33f,
    val baselineBrier: Float = 0.667f,
    val isBeatingBaseline: Boolean,
    val summary: String
)

class PatternAnalyzer {

    companion object {
        const val MIN_DATA_THRESHOLD = 10
        const val UNIFORM_BASELINE_ACCURACY = 33.33f
        const val UNIFORM_BASELINE_BRIER = 0.667f
    }

    /**
     * Calculates Bayesian Dirichlet smoothed probability distribution for A, B, C
     * with 1st-order Markov transition from previous round winner.
     * Guaranteed: probA + probB + probC == 100
     */
    fun calculateStatisticalPrediction(rounds: List<RoundEntity>): StatisticalPredictionResult {
        val n = rounds.size
        val hasEnough = n >= MIN_DATA_THRESHOLD
        val dataStatus = if (hasEnough) {
            "Sufficient ($n real rounds)"
        } else {
            "Insufficient Data ($n/$MIN_DATA_THRESHOLD rounds)"
        }

        // If no data, return uniform smoothed distribution
        if (n == 0) {
            return StatisticalPredictionResult(
                hasEnoughData = false,
                dataStatus = dataStatus,
                probA = 33, probB = 33, probC = 34,
                mostLikely = "A",
                confidence = 34,
                rationale = "Uniform prior (no observed rounds yet)",
                sampleSize = 0,
                walkForwardAccuracy = 0f,
                brierScore = UNIFORM_BASELINE_BRIER,
                baselineComparison = "Awaiting rounds to compare against 33.3% baseline"
            )
        }

        // 1. Empirical Bayesian Counts with Dirichlet prior alpha=1.0 (Laplace smoothing)
        var countA = 1.0
        var countB = 1.0
        var countC = 1.0
        for (r in rounds) {
            when (r.actualWinner.uppercase()) {
                "A" -> countA += 1.0
                "B" -> countB += 1.0
                "C" -> countC += 1.0
            }
        }
        val totalDirichlet = countA + countB + countC
        val freqA = countA / totalDirichlet
        val freqB = countB / totalDirichlet
        val freqC = countC / totalDirichlet

        // 2. 1st-order Markov transition conditioned on the last round winner
        val lastWinner = rounds.last().actualWinner.uppercase()
        var transCountA = 1.0
        var transCountB = 1.0
        var transCountC = 1.0
        for (i in 0 until rounds.size - 1) {
            if (rounds[i].actualWinner.equals(lastWinner, ignoreCase = true)) {
                when (rounds[i + 1].actualWinner.uppercase()) {
                    "A" -> transCountA += 1.0
                    "B" -> transCountB += 1.0
                    "C" -> transCountC += 1.0
                }
            }
        }
        val totalTrans = transCountA + transCountB + transCountC
        val transA = transCountA / totalTrans
        val transB = transCountB / totalTrans
        val transC = transCountC / totalTrans

        // 3. Blend: 50% Dirichlet frequency + 50% Markov transition
        val rawA = 0.5 * freqA + 0.5 * transA
        val rawB = 0.5 * freqB + 0.5 * transB
        val rawC = 0.5 * freqC + 0.5 * transC
        val rawSum = rawA + rawB + rawC

        // 4. Strict Integer Normalization: sum must be exactly 100
        var pA = ((rawA / rawSum) * 100.0).roundToInt().coerceIn(1, 98)
        var pB = ((rawB / rawSum) * 100.0).roundToInt().coerceIn(1, 98)
        var pC = 100 - pA - pB
        if (pC < 1) {
            pC = 1
            if (pA >= pB) pA -= 1 else pB -= 1
        }

        val topWinner = when {
            pA >= pB && pA >= pC -> "A"
            pB >= pA && pB >= pC -> "B"
            else -> "C"
        }
        val maxConf = maxOf(pA, maxOf(pB, pC))

        // 5. Walk-Forward Backtest evaluation
        val backtest = runWalkForwardBacktest(rounds)

        val rationale = if (hasEnough) {
            "Bayesian Dirichlet smoothing over $n rounds with Markov transition from Spot $lastWinner"
        } else {
            "Empirical frequency over $n rounds conditioned on Spot $lastWinner (stabilizes at $MIN_DATA_THRESHOLD rounds)"
        }

        val baselineComparison = when {
            backtest.totalTested == 0 -> "Awaiting >= 5 rounds for walk-forward validation"
            backtest.accuracyPercentage > 35.0f -> "Model leads Random Baseline (+${String.format("%.1f", backtest.accuracyPercentage - UNIFORM_BASELINE_ACCURACY)}%)"
            else -> "Model matches Random Baseline (${String.format("%.1f", backtest.accuracyPercentage)}% vs 33.3%)"
        }

        return StatisticalPredictionResult(
            hasEnoughData = hasEnough,
            dataStatus = dataStatus,
            probA = pA,
            probB = pB,
            probC = pC,
            mostLikely = topWinner,
            confidence = maxConf,
            rationale = rationale,
            sampleSize = n,
            walkForwardAccuracy = backtest.accuracyPercentage,
            brierScore = backtest.brierScore,
            baselineComparison = baselineComparison
        )
    }

    /**
     * Walk-forward backtest:
     * Iterates through history step-by-step.
     * At step t, predicts using only rounds 0..t-1 (no lookahead bias).
     * Compares predicted spot against actual round t winner.
     * Computes accuracy and Brier score.
     */
    fun runWalkForwardBacktest(rounds: List<RoundEntity>): BacktestReport {
        if (rounds.size < 5) {
            return BacktestReport(
                totalTested = 0,
                correctCount = 0,
                accuracyPercentage = 0f,
                brierScore = UNIFORM_BASELINE_BRIER,
                isBeatingBaseline = false,
                summary = "Insufficient rounds (<5) for walk-forward validation"
            )
        }

        var tested = 0
        var correct = 0
        var totalBrier = 0.0

        for (i in 4 until rounds.size) {
            val historyWindow = rounds.subList(0, i)
            val actual = rounds[i].actualWinner.uppercase()

            var countA = 1.0
            var countB = 1.0
            var countC = 1.0
            for (h in historyWindow) {
                when (h.actualWinner.uppercase()) {
                    "A" -> countA += 1.0
                    "B" -> countB += 1.0
                    "C" -> countC += 1.0
                }
            }
            val tot = countA + countB + countC
            val pA = countA / tot
            val pB = countB / tot
            val pC = countC / tot

            val predictedSpot = when {
                pA >= pB && pA >= pC -> "A"
                pB >= pA && pB >= pC -> "B"
                else -> "C"
            }

            tested++
            if (predictedSpot == actual) {
                correct++
            }

            val yA = if (actual == "A") 1.0 else 0.0
            val yB = if (actual == "B") 1.0 else 0.0
            val yC = if (actual == "C") 1.0 else 0.0

            val stepBrier = (pA - yA).pow(2.0) + (pB - yB).pow(2.0) + (pC - yC).pow(2.0)
            totalBrier += stepBrier
        }

        val accuracy = if (tested > 0) (correct.toFloat() / tested) * 100f else 0f
        val avgBrier = if (tested > 0) (totalBrier / tested).toFloat() else UNIFORM_BASELINE_BRIER
        val beatsBaseline = accuracy > UNIFORM_BASELINE_ACCURACY && avgBrier < UNIFORM_BASELINE_BRIER
        val summary = if (beatsBaseline) {
            "Walk-forward accuracy ${String.format("%.1f", accuracy)}% (+${String.format("%.1f", accuracy - UNIFORM_BASELINE_ACCURACY)}% vs baseline)"
        } else {
            "Walk-forward accuracy ${String.format("%.1f", accuracy)}% (Matches uniform random 33.3%)"
        }

        return BacktestReport(
            totalTested = tested,
            correctCount = correct,
            accuracyPercentage = accuracy,
            brierScore = avgBrier,
            isBeatingBaseline = beatsBaseline,
            summary = summary
        )
    }
}
