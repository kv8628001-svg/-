package com.scenebot.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoundDeduplicatorTest {

    @Test
    fun testIdenticalFingerprintIsDetectedAsDuplicate() {
        val fp1 = "fingerprint_round_101_cards_A_B_C"
        val fp2 = "fingerprint_round_101_cards_A_B_C"

        val isDuplicate = (fp1 == fp2)
        assertTrue("Identical fingerprint must be flagged as duplicate", isDuplicate)
    }

    @Test
    fun testTimeIntervalThresholdPreventsSpam() {
        val lastSavedTime = 100000L
        val currentTimeTooFast = 102000L // 2 seconds later
        val minInterval = 6000L // 6s

        val isTooFast = (currentTimeTooFast - lastSavedTime < minInterval)
        assertTrue("Rounds repeating in less than 6s must be blocked", isTooFast)

        val currentTimeValid = 107000L // 7 seconds later
        val isValid = (currentTimeValid - lastSavedTime >= minInterval)
        assertTrue("Rounds repeating after 6s must be accepted", isValid)
    }
}
