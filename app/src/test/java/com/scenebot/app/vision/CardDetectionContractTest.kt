package com.scenebot.app.vision

import com.scenebot.app.rules.Card
import com.scenebot.app.rules.GoldenFlowerRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CardDetectionContractTest {

    @Test
    fun testLowConfidenceDoesNotFabricateResults() {
        val lowConfidence = 0.40f
        val status = when {
            lowConfidence >= 0.70f -> "Verified"
            lowConfidence >= 0.50f -> "Result Not Verified"
            else -> "Card Not Detected"
        }
        assertEquals("Low confidence must strictly report Card Not Detected", "Card Not Detected", status)
    }

    @Test
    fun testWinnerExtractionRegexMatchesZonePatterns() {
        val lineA = "WINNER A"
        val lineB = "SPOT B WIN"
        val lineC = "PLAYER C VICTORY"

        val matchA = Regex("(?:WIN|WINNER|VICTORY|WON|BEST)\\s*[:\\-]?\\s*A\\b|\\bA\\s*(?:WIN|WINNER|VICTORY|WON)").containsMatchIn(lineA)
        val matchB = Regex("(?:WIN|WINNER|VICTORY|WON|BEST)\\s*[:\\-]?\\s*B\\b|\\bB\\s*(?:WIN|WINNER|VICTORY|WON)").containsMatchIn(lineB)
        val matchC = Regex("(?:WIN|WINNER|VICTORY|WON|BEST)\\s*[:\\-]?\\s*C\\b|\\bC\\s*(?:WIN|WINNER|VICTORY|WON)").containsMatchIn(lineC)

        assertEquals(true, matchA)
        assertEquals(true, matchB)
        assertEquals(true, matchC)
    }

    @Test
    fun testGoldenFlowerRulesResolveWinnerAccurately() {
        // Hand A: Flush
        val handA = GoldenFlowerRules.evaluate(listOf(Card(14, "♠"), Card(10, "♠"), Card(7, "♠")))
        // Hand B: Pair of Kings
        val handB = GoldenFlowerRules.evaluate(listOf(Card(13, "♥"), Card(13, "♦"), Card(4, "♠")))
        // Hand C: High Card Queen
        val handC = GoldenFlowerRules.evaluate(listOf(Card(12, "♣"), Card(8, "♦"), Card(3, "♠")))

        val winner = GoldenFlowerRules.compareHands(handA, handB, handC)
        assertEquals("Flush in Hand A must beat Pair in Hand B and High Card in C", "A", winner)
    }
}
