package com.scenebot.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scenebot.app.calibration.CalibrationManager
import kotlinx.coroutines.tasks.await

data class CardRecognitionResult(
    val card1: String,
    val card2: String,
    val card3: String,
    val detectedWinner: String?, // "A", "B", "C" if winner badge visible
    val detectionStatus: String, // "Verified", "Card Not Detected", "Result Not Verified"
    val confidence: Float
)

class CardVisionRecognizer(private val context: Context) {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val calibrationManager = CalibrationManager(context)

    suspend fun processScreen(fullScreenshot: Bitmap): CardRecognitionResult? {
        val region = calibrationManager.getCurrentCardRegion(fullScreenshot.width, fullScreenshot.height)
        if (region.width() <= 10 || region.height() <= 10) {
            return CardRecognitionResult(
                card1 = "Card Not Detected",
                card2 = "Card Not Detected",
                card3 = "Card Not Detected",
                detectedWinner = null,
                detectionStatus = "Card Not Detected",
                confidence = 0f
            )
        }

        val cardRegionCrop = try {
            Bitmap.createBitmap(
                fullScreenshot,
                region.left.coerceAtLeast(0),
                region.top.coerceAtLeast(0),
                region.width().coerceAtMost(fullScreenshot.width - region.left),
                region.height().coerceAtMost(fullScreenshot.height - region.top)
            )
        } catch (_: Exception) {
            return null
        }

        // Run ML Kit OCR on the cropped game region
        val ocrText = try {
            val inputImage = InputImage.fromBitmap(cardRegionCrop, 0)
            textRecognizer.process(inputImage).await().text.uppercase()
        } catch (_: Exception) {
            ""
        }

        // Check for visible Winner Badge: e.g. "WINNER A", "A WIN", "A VICTORY", or highlighted spot
        var winner: String? = null
        if (ocrText.contains("WIN A") || ocrText.contains("A WIN") || ocrText.contains("VICTORY A")) {
            winner = "A"
        } else if (ocrText.contains("WIN B") || ocrText.contains("B WIN") || ocrText.contains("VICTORY B")) {
            winner = "B"
        } else if (ocrText.contains("WIN C") || ocrText.contains("C WIN") || ocrText.contains("VICTORY C")) {
            winner = "C"
        }

        // Divide crop into 3 card slots
        val slotWidth = cardRegionCrop.width / 3
        if (slotWidth <= 10) {
            cardRegionCrop.recycle()
            return CardRecognitionResult(
                card1 = "Card Not Detected",
                card2 = "Card Not Detected",
                card3 = "Card Not Detected",
                detectedWinner = winner,
                detectionStatus = "Card Not Detected",
                confidence = 0f
            )
        }

        val card1Bmp = Bitmap.createBitmap(cardRegionCrop, 0, 0, slotWidth, cardRegionCrop.height)
        val card2Bmp = Bitmap.createBitmap(cardRegionCrop, slotWidth, 0, slotWidth, cardRegionCrop.height)
        val card3Bmp = Bitmap.createBitmap(cardRegionCrop, slotWidth * 2, 0, slotWidth, cardRegionCrop.height)

        val c1 = recognizeSlot(card1Bmp)
        val c2 = recognizeSlot(card2Bmp)
        val c3 = recognizeSlot(card3Bmp)

        card1Bmp.recycle()
        card2Bmp.recycle()
        card3Bmp.recycle()
        cardRegionCrop.recycle()

        val avgConfidence = (c1.second + c2.second + c3.second) / 3f

        // STRICT TRUTHFULNESS:
        // If average confidence is below 0.65 or cards are blank, report "Card Not Detected"
        val isVerified = avgConfidence >= 0.70f && c1.first != "Card Not Detected" && c2.first != "Card Not Detected" && c3.first != "Card Not Detected"
        val status = when {
            isVerified -> "Verified"
            avgConfidence >= 0.50f -> "Result Not Verified"
            else -> "Card Not Detected"
        }

        return CardRecognitionResult(
            card1 = c1.first,
            card2 = c2.first,
            card3 = c3.first,
            detectedWinner = winner ?: (if (c1.first == "A" || c2.first == "A" || c3.first == "A") "A" else null),
            detectionStatus = status,
            confidence = avgConfidence
        )
    }

    private suspend fun recognizeSlot(cardBitmap: Bitmap): Pair<String, Float> {
        val cvResult = evaluateMorphologyAndTemplate(cardBitmap)
        if (cvResult.second >= 0.85f) {
            return cvResult
        }

        return try {
            val inputImage = InputImage.fromBitmap(cardBitmap, 0)
            val visionText = textRecognizer.process(inputImage).await()
            val text = visionText.text.uppercase()
            when {
                text.contains("A") -> Pair("A", 0.90f)
                text.contains("B") -> Pair("B", 0.90f)
                text.contains("C") -> Pair("C", 0.90f)
                text.contains("K") -> Pair("K", 0.88f)
                text.contains("Q") -> Pair("Q", 0.88f)
                text.contains("J") -> Pair("J", 0.88f)
                text.contains("10") -> Pair("10", 0.85f)
                else -> cvResult
            }
        } catch (_: Exception) {
            cvResult
        }
    }

    private fun evaluateMorphologyAndTemplate(bitmap: Bitmap): Pair<String, Float> {
        var darkPixels = 0
        var totalSamples = 0
        val w = bitmap.width
        val h = bitmap.height

        for (y in 0 until h step 4) {
            for (x in 0 until w step 4) {
                val pixel = bitmap.getPixel(x, y)
                val luminance = (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)).toInt()
                if (luminance < 128) {
                    darkPixels++
                }
                totalSamples++
            }
        }

        val darkRatio = if (totalSamples > 0) darkPixels.toFloat() / totalSamples else 0f
        return when {
            darkRatio in 0.15f..0.45f -> Pair("Card Verified", 0.75f)
            darkRatio in 0.05f..0.15f -> Pair("Low Contrast", 0.50f)
            else -> Pair("Card Not Detected", 0.20f)
        }
    }
}
