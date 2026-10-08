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
    val confidence: Float
)

class CardVisionRecognizer(private val context: Context) {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val calibrationManager = CalibrationManager(context)

    suspend fun processScreen(fullScreenshot: Bitmap): CardRecognitionResult? {
        val region = calibrationManager.getCurrentCardRegion(fullScreenshot.width, fullScreenshot.height)
        if (region.width() <= 0 || region.height() <= 0) return null

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

        val slotWidth = cardRegionCrop.width / 3
        if (slotWidth <= 0) {
            cardRegionCrop.recycle()
            return null
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
        return CardRecognitionResult(
            card1 = c1.first,
            card2 = c2.first,
            card3 = c3.first,
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
                text.contains("A") -> Pair("A", 0.92f)
                text.contains("B") -> Pair("B", 0.92f)
                text.contains("C") -> Pair("C", 0.92f)
                else -> cvResult
            }
        } catch (_: Exception) {
            cvResult
        }
    }

    private fun evaluateMorphologyAndTemplate(bitmap: Bitmap): Pair<String, Float> {
        var topCenterDarkPixels = 0
        var leftSpineDarkPixels = 0
        var rightOpeningDarkPixels = 0
        var totalSamples = 0
        val w = bitmap.width
        val h = bitmap.height

        for (y in 0 until h step 4) {
            for (x in 0 until w step 4) {
                val pixel = bitmap.getPixel(x, y)
                val luminance = (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)).toInt()
                val isDark = luminance < 128
                if (isDark) {
                    if (y < h / 3 && x in (w / 3)..(2 * w / 3)) topCenterDarkPixels++
                    if (x < w / 4) leftSpineDarkPixels++
                    if (x > 3 * w / 4 && y in (h / 3)..(2 * h / 3)) rightOpeningDarkPixels++
                }
                totalSamples++
            }
        }

        return when {
            topCenterDarkPixels > 15 && rightOpeningDarkPixels > 5 -> Pair("A", 0.88f)
            leftSpineDarkPixels > 30 && rightOpeningDarkPixels > 15 -> Pair("B", 0.89f)
            rightOpeningDarkPixels < 5 && topCenterDarkPixels > 10 -> Pair("C", 0.87f)
            else -> Pair("A", 0.70f)
        }
    }
}
