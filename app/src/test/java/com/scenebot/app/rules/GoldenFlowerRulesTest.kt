package com.scenebot.app.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoldenFlowerRulesTest {

    @Test
    fun testTrailBeatsPureSequence() {
        // A-A-A (Trail) vs K-Q-J same suit (Pure Sequence)
        val trail = listOf(Card(14, "♠"), Card(14, "♥"), Card(14, "♦"))
        val pureSeq = listOf(Card(13, "♠"), Card(12, "♠"), Card(11, "♠"))

        val evalTrail = GoldenFlowerRules.evaluate(trail)
        val evalPureSeq = GoldenFlowerRules.evaluate(pureSeq)

        assertEquals(HandType.TRAIL, evalTrail.type)
        assertEquals(HandType.PURE_SEQUENCE, evalPureSeq.type)
        assertTrue("Trail score must be greater than Pure Sequence", evalTrail.score > evalPureSeq.score)
    }

    @Test
    fun testPureSequenceBeatsSequence() {
        // K-Q-J same suit (Pure Sequence) vs K-Q-J mixed suits (Sequence)
        val pureSeq = listOf(Card(13, "♠"), Card(12, "♠"), Card(11, "♠"))
        val seq = listOf(Card(13, "♠"), Card(12, "♥"), Card(11, "♦"))

        val evalPureSeq = GoldenFlowerRules.evaluate(pureSeq)
        val evalSeq = GoldenFlowerRules.evaluate(seq)

        assertEquals(HandType.PURE_SEQUENCE, evalPureSeq.type)
        assertEquals(HandType.SEQUENCE, evalSeq.type)
        assertTrue("Pure Sequence must beat Sequence", evalPureSeq.score > evalSeq.score)
    }

    @Test
    fun testSequenceBeatsColor() {
        // 9-8-7 mixed (Sequence) vs A-K-9 flush (Color)
        val seq = listOf(Card(9, "♠"), Card(8, "♥"), Card(7, "♦"))
        val flush = listOf(Card(14, "♥"), Card(13, "♥"), Card(9, "♥"))

        val evalSeq = GoldenFlowerRules.evaluate(seq)
        val evalFlush = GoldenFlowerRules.evaluate(flush)

        assertEquals(HandType.SEQUENCE, evalSeq.type)
        assertEquals(HandType.COLOR, evalFlush.type)
        assertTrue("Sequence must beat Flush/Color", evalSeq.score > evalFlush.score)
    }

    @Test
    fun testColorBeatsPair() {
        val flush = listOf(Card(10, "♦"), Card(8, "♦"), Card(4, "♦"))
        val pair = listOf(Card(14, "♠"), Card(14, "♥"), Card(10, "♦"))

        val evalFlush = GoldenFlowerRules.evaluate(flush)
        val evalPair = GoldenFlowerRules.evaluate(pair)

        assertEquals(HandType.COLOR, evalFlush.type)
        assertEquals(HandType.PAIR, evalPair.type)
        assertTrue("Flush must beat Pair", evalFlush.score > evalPair.score)
    }

    @Test
    fun testPairBeatsHighCard() {
        val pair = listOf(Card(8, "♠"), Card(8, "♥"), Card(2, "♦"))
        val highCard = listOf(Card(14, "♠"), Card(13, "♥"), Card(10, "♦"))

        val evalPair = GoldenFlowerRules.evaluate(pair)
        val evalHigh = GoldenFlowerRules.evaluate(highCard)

        assertEquals(HandType.PAIR, evalPair.type)
        assertEquals(HandType.HIGH_CARD, evalHigh.type)
        assertTrue("Pair must beat High Card", evalPair.score > evalHigh.score)
    }

    @Test
    fun testCompareThreeHands() {
        val handA = GoldenFlowerRules.evaluate(listOf(Card(14, "♠"), Card(14, "♥"), Card(14, "♦"))) // Trail A
        val handB = GoldenFlowerRules.evaluate(listOf(Card(13, "♠"), Card(12, "♠"), Card(11, "♠"))) // Pure Sequence K
        val handC = GoldenFlowerRules.evaluate(listOf(Card(10, "♠"), Card(10, "♥"), Card(2, "♦")))  // Pair 10

        val winner = GoldenFlowerRules.compareHands(handA, handB, handC)
        assertEquals("Slot A must win with Trail", "A", winner)
    }
}
