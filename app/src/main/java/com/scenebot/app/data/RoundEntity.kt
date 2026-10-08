package com.scenebot.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "rounds",
    indices = [Index(value = ["fingerprint"]), Index(value = ["timestamp"])]
)
data class RoundEntity(
    @PrimaryKey(autoGenerate = true)
    val roundId: Long = 0,
    val timestamp: Long,
    val card1: String,
    val card2: String,
    val card3: String,
    val sequence: String,
    val source: String,
    val confidence: Float,
    val fingerprint: String,
    val isCorrected: Boolean = false
)
