package com.scenebot.app.rules

data class Card(val rank: Int, val suit: String) {
    override fun toString(): String {
        val rStr = when (rank) {
            14 -> "A"
            13 -> "K"
            12 -> "Q"
            11 -> "J"
            10 -> "10"
            else -> rank.toString()
        }
        return "$rStr$suit"
    }

    companion object {
        fun fromString(s: String): Card? {
            val clean = s.trim().uppercase()
            if (clean.isEmpty()) return null
            val suit = when {
                clean.contains("♠") || clean.endsWith("S") -> "♠"
                clean.contains("♥") || clean.endsWith("H") -> "♥"
                clean.contains("♦") || clean.endsWith("D") -> "♦"
                clean.contains("♣") || clean.endsWith("C") -> "♣"
                else -> "♠"
            }
            val rankStr = clean.replace(Regex("[♠♥♦♣SHDC]"), "").trim()
            val rank = when (rankStr) {
                "A", "1" -> 14
                "K" -> 13
                "Q" -> 12
                "J" -> 11
                "10" -> 10
                else -> rankStr.toIntOrNull() ?: 10
            }
            return Card(rank.coerceIn(2, 14), suit)
        }
    }
}

enum class HandType(val scoreMultiplier: Int, val displayName: String) {
    TRAIL(600000, "Trail (Set)"),             // 3 of a kind
    PURE_SEQUENCE(500000, "Pure Sequence"),   // Straight Flush
    SEQUENCE(400000, "Sequence (Straight)"),  // Straight
    COLOR(300000, "Color (Flush)"),           // Flush
    PAIR(200000, "Pair"),                     // Pair
    HIGH_CARD(100000, "High Card")            // High Card
}

data class HandEvaluation(
    val type: HandType,
    val score: Int,
    val description: String
)

object GoldenFlowerRules {
    fun evaluate(cards: List<Card>): HandEvaluation {
        if (cards.size < 3) {
            return HandEvaluation(HandType.HIGH_CARD, 100000, "Incomplete Hand")
        }
        val sorted = cards.take(3).sortedByDescending { it.rank }
        val r1 = sorted[0].rank
        val r2 = sorted[1].rank
        val r3 = sorted[2].rank

        val isFlush = (sorted[0].suit == sorted[1].suit && sorted[1].suit == sorted[2].suit)
        val isTrail = (r1 == r2 && r2 == r3)

        // Straight check: standard or A-2-3 (14-3-2)
        val isStandardStraight = (r1 - r2 == 1 && r2 - r3 == 1)
        val isA23Straight = (r1 == 14 && r2 == 3 && r3 == 2)
        val isStraight = isStandardStraight || isA23Straight
        val straightHigh = if (isA23Straight) 3 else r1

        return when {
            isTrail -> {
                val score = HandType.TRAIL.scoreMultiplier + r1
                HandEvaluation(HandType.TRAIL, score, "Trail of ${sorted[0]}")
            }
            isFlush && isStraight -> {
                val score = HandType.PURE_SEQUENCE.scoreMultiplier + straightHigh
                HandEvaluation(HandType.PURE_SEQUENCE, score, "Pure Sequence high $straightHigh")
            }
            isStraight -> {
                val score = HandType.SEQUENCE.scoreMultiplier + straightHigh
                HandEvaluation(HandType.SEQUENCE, score, "Sequence high $straightHigh")
            }
            isFlush -> {
                val score = HandType.COLOR.scoreMultiplier + (r1 * 400 + r2 * 20 + r3)
                HandEvaluation(HandType.COLOR, score, "Color high $r1")
            }
            r1 == r2 || r2 == r3 || r1 == r3 -> {
                val pairRank = if (r1 == r2 || r1 == r3) r1 else r2
                val kicker = if (r1 == r2) r3 else if (r2 == r3) r1 else r2
                val score = HandType.PAIR.scoreMultiplier + (pairRank * 100 + kicker)
                HandEvaluation(HandType.PAIR, score, "Pair of $pairRank with $kicker kicker")
            }
            else -> {
                val score = HandType.HIGH_CARD.scoreMultiplier + (r1 * 400 + r2 * 20 + r3)
                HandEvaluation(HandType.HIGH_CARD, score, "High Card $r1-$r2-$r3")
            }
        }
    }

    fun compareHands(handA: HandEvaluation, handB: HandEvaluation, handC: HandEvaluation): String {
        return when {
            handA.score >= handB.score && handA.score >= handC.score -> "A"
            handB.score >= handA.score && handB.score >= handC.score -> "B"
            else -> "C"
        }
    }
}
