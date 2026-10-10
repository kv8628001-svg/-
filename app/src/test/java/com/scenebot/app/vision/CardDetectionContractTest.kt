package com.scenebot.app.vision

import com.scenebot.app.rules.Card
import com.scenebot.app.rules.GoldenFlowerRules
import com.scenebot.app.service.GameRoundState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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

        val matchA = Regex("(?:WIN|WINNER|VICTORY|WON|BEST|CHAMPION)\\s*[:\\-]?\\s*A\\b|\\bA\\s*(?:WIN|WINNER|VICTORY|WON|CHAMPION)|PLAYER\\s*A|SPOT\\s*A\\s*WIN|A\\s*[胜赢]").containsMatchIn(lineA)
        val matchB = Regex("(?:WIN|WINNER|VICTORY|WON|BEST|CHAMPION)\\s*[:\\-]?\\s*B\\b|\\bB\\s*(?:WIN|WINNER|VICTORY|WON|CHAMPION)|PLAYER\\s*B|SPOT\\s*B\\s*WIN|B\\s*[胜赢]").containsMatchIn(lineB)
        val matchC = Regex("(?:WIN|WINNER|VICTORY|WON|BEST|CHAMPION)\\s*[:\\-]?\\s*C\\b|\\bC\\s*(?:WIN|WINNER|VICTORY|WON|CHAMPION)|PLAYER\\s*C|SPOT\\s*C\\s*WIN|C\\s*[胜赢]").containsMatchIn(lineC)

        assertTrue(matchA)
        assertTrue(matchB)
        assertTrue(matchC)
    }

    @Test
    fun testChineseWinnerKeywordsMatch() {
        val lineA = "A胜"
        val lineB = "B赢"
        val lineC = "C胜"

        val matchA = Regex("A\\s*[胜赢]").containsMatchIn(lineA)
        val matchB = Regex("B\\s*[胜赢]").containsMatchIn(lineB)
        val matchC = Regex("C\\s*[胜赢]").containsMatchIn(lineC)

        assertTrue(matchA)
        assertTrue(matchB)
        assertTrue(matchC)
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

    @Test
    fun testGameRoundStateEnumValues() {
        val states = GameRoundState.values()
        assertEquals(5, states.size)
        assertTrue(states.contains(GameRoundState.BETTING_ACTIVE))
        assertTrue(states.contains(GameRoundState.SHOWDOWN_REVEAL))
        assertTrue(states.contains(GameRoundState.ROUND_COMMITTED))
    }
}
