package com.scenebot.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "rounds",
    indices = [
        Index(value = ["fingerprint"]),
        Index(value = ["timestamp"]),
        Index(value = ["roundSequenceNumber"])
    ]
)
data class RoundEntity(
    @PrimaryKey(autoGenerate = true)
    val roundId: Long = 0,
    val roundSequenceNumber: Long,
    val timestamp: Long,
    val card1: String,
    val card2: String,
    val card3: String,
    val detectionStatus: String, // "Verified", "Card Not Detected", "Result Not Verified"
    val actualWinner: String,    // "A", "B", "C"
    val predictedWinner: String, // "A", "B", "C", "None"
    val predictedProbA: Int,     // 0..100
    val predictedProbB: Int,     // 0..100
    val predictedProbC: Int,     // 0..100
    val predictionCorrect: Boolean,
    val historicalAccuracyAtRound: Float,
    val brierScoreAtRound: Float,
    val source: String,          // "AUTO_CAPTURE", "SIMULATION", "MANUAL"
    val fingerprint: String      // unique hash preventing duplicates
)
