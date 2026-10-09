package com.scenebot.app.analysis

import com.scenebot.app.data.RoundEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PatternAnalyzerTest {

    private lateinit var analyzer: PatternAnalyzer

    @Before
    fun setUp() {
        analyzer = PatternAnalyzer()
    }

    @Test
    fun testProbabilitiesSumToExactly100Percent() {
        val dummyRounds = listOf(
            createDummyRound(1, "A"),
            createDummyRound(2, "B"),
            createDummyRound(3, "A"),
            createDummyRound(4, "C"),
            createDummyRound(5, "A"),
            createDummyRound(6, "B"),
            createDummyRound(7, "B"),
            createDummyRound(8, "C"),
            createDummyRound(9, "A"),
            createDummyRound(10, "A"),
            createDummyRound(11, "B")
        )

        val result = analyzer.calculateStatisticalPrediction(dummyRounds)
        val sum = result.probA + result.probB + result.probC
        assertEquals("Probabilities must sum to exactly 100", 100, sum)
        assertTrue("probA must be >= 1", result.probA >= 1)
        assertTrue("probB must be >= 1", result.probB >= 1)
        assertTrue("probC must be >= 1", result.probC >= 1)
    }

    @Test
    fun testInsufficientDataWhenRoundsLessThanTen() {
        val fewRounds = listOf(
            createDummyRound(1, "A"),
            createDummyRound(2, "B"),
            createDummyRound(3, "C")
        )

        val result = analyzer.calculateStatisticalPrediction(fewRounds)
        assertFalse("Should not have enough data when < 10 rounds", result.hasEnoughData)
        assertTrue("Data status must mention insufficient", result.dataStatus.contains("Insufficient Data"))
        assertEquals("Probabilities must still sum to 100", 100, result.probA + result.probB + result.probC)
    }

    @Test
    fun testSufficientDataWhenRoundsTenOrMore() {
        val tenRounds = (1..10).map { createDummyRound(it.toLong(), if (it % 2 == 0) "A" else "B") }
        val result = analyzer.calculateStatisticalPrediction(tenRounds)
        assertTrue("Should have enough data when >= 10 rounds", result.hasEnoughData)
        assertTrue("Data status must mention Sufficient", result.dataStatus.contains("Sufficient"))
    }

    @Test
    fun testWalkForwardBacktestCalculatesCorrectMetrics() {
        // Create 20 rounds alternating A and B
        val rounds = (1..20).map { createDummyRound(it.toLong(), if (it % 2 == 0) "A" else "B") }
        val report = analyzer.runWalkForwardBacktest(rounds)

        assertTrue("Total tested must be > 0", report.totalTested > 0)
        assertTrue("Accuracy must be between 0 and 100", report.accuracyPercentage in 0f..100f)
        assertTrue("Brier score must be non-negative", report.brierScore >= 0f)
    }

    @Test
    fun testBaselineComparisonAccountsForUniformOutcomes() {
        // Equal distribution of A, B, C
        val rounds = (1..30).map {
            val spot = when (it % 3) {
                0 -> "A"
                1 -> "B"
                else -> "C"
            }
            createDummyRound(it.toLong(), spot)
        }
        val result = analyzer.calculateStatisticalPrediction(rounds)
        assertTrue("Summary or baseline comparison should indicate independence or baseline",
            result.baselineComparison.contains("Baseline") || result.baselineComparison.contains("33.3%"))
    }

    private fun createDummyRound(seq: Long, winner: String): RoundEntity {
        return RoundEntity(
            roundId = seq,
            roundSequenceNumber = seq,
            timestamp = 1700000000000L + seq * 15000L,
            card1 = "Card 1",
            card2 = "Card 2",
            card3 = "Card 3",
            detectionStatus = "Verified",
            actualWinner = winner,
            predictedWinner = "A",
            predictedProbA = 33,
            predictedProbB = 33,
            predictedProbC = 34,
            predictionCorrect = (winner == "A"),
            historicalAccuracyAtRound = 33.3f,
            brierScoreAtRound = 0.667f,
            source = "TEST",
            fingerprint = "fp_$seq"
        )
    }
}
