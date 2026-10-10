package com.scenebot.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.scenebot.app.calibration.CalibrationManager
import com.scenebot.app.rules.Card
import com.scenebot.app.rules.GoldenFlowerRules
import com.scenebot.app.rules.HandType
import kotlinx.coroutines.tasks.await

data class CardRecognitionResult(
    val card1: String,             // Spot A Hand / Cards
    val card2: String,             // Spot B Hand / Cards
    val card3: String,             // Spot C Hand / Cards
    val detectedWinner: String?,   // "A", "B", "C" or null
    val detectionStatus: String,   // "Verified", "Showdown Detected", "Betting Phase", "Observing Table", "Screen Too Dark"
    val gamePhase: String,         // "BETTING", "SHOWDOWN", "IDLE"
    val confidence: Float,
    val diagnostics: String
)

class CardVisionRecognizer(private val context: Context) {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val calibrationManager = CalibrationManager(context)

    suspend fun processScreen(fullScreenshot: Bitmap): CardRecognitionResult? {
        val region = calibrationManager.getCurrentCardRegion(fullScreenshot.width, fullScreenshot.height)
        if (region.width() <= 20 || region.height() <= 20) {
            return CardRecognitionResult(
                card1 = "Card Not Detected",
                card2 = "Card Not Detected",
                card3 = "Card Not Detected",
                detectedWinner = null,
                detectionStatus = "Region Cut Off",
                gamePhase = "IDLE",
                confidence = 0f,
                diagnostics = "Game region crop is too small or off-screen."
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

        // 1. Diagnostic Screen Quality Assessment (Dark / Blur / Missing)
        val quality = checkScreenQuality(cardRegionCrop)
        if (!quality.isValid) {
            cardRegionCrop.recycle()
            return CardRecognitionResult(
                card1 = "Card Not Detected",
                card2 = "Card Not Detected",
                card3 = "Card Not Detected",
                detectedWinner = null,
                detectionStatus = quality.statusMessage,
                gamePhase = "IDLE",
                confidence = 0f,
                diagnostics = quality.diagnostics
            )
        }

        // 2. OCR using Google ML Kit on the cropped game region
        val visionText = try {
            val inputImage = InputImage.fromBitmap(cardRegionCrop, 0)
            textRecognizer.process(inputImage).await()
        } catch (_: Exception) {
            null
        }

        val cropWidth = cardRegionCrop.width
        val cropHeight = cardRegionCrop.height

        // 3. Analyze Detected Text across Table Zones (A = Left, B = Center, C = Right)
        val zoneTextA = StringBuilder()
        val zoneTextB = StringBuilder()
        val zoneTextC = StringBuilder()
        val allText = StringBuilder()

        var hasBettingKeyword = false
        var hasWinnerKeyword = false
        var explicitWinner: String? = null

        if (visionText != null) {
            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val lineText = line.text.uppercase()
                    allText.append(" ").append(lineText)

                    val box = line.boundingBox
                    val centerX = box?.centerX() ?: (cropWidth / 2)

                    // Partition into Spot A (Left 36%), Spot B (Center 36-64%), Spot C (Right 64-100%)
                    if (centerX < cropWidth * 0.36f) {
                        zoneTextA.append(" ").append(lineText)
                    } else if (centerX < cropWidth * 0.64f) {
                        zoneTextB.append(" ").append(lineText)
                    } else {
                        zoneTextC.append(" ").append(lineText)
                    }

                    // Check for Betting Phase markers
                    if (lineText.contains("BET") || lineText.contains("CHIP") ||
                        lineText.contains("COUNTDOWN") || lineText.contains("TIME") ||
                        lineText.matches(Regex(".*\\b(1[0-5]|[1-9])S\\b.*"))
                    ) {
                        hasBettingKeyword = true
                    }

                    // Check for explicit Winner patterns in line:
                    // e.g. "WIN A", "A WIN", "WINNER B", "VICTORY C", "PLAYER A WIN"
                    if (Regex("(?:WIN|WINNER|VICTORY|WON|BEST)\\s*[:\\-]?\\s*A\\b|\\bA\\s*(?:WIN|WINNER|VICTORY|WON)").containsMatchIn(lineText)) {
                        explicitWinner = "A"
                        hasWinnerKeyword = true
                    } else if (Regex("(?:WIN|WINNER|VICTORY|WON|BEST)\\s*[:\\-]?\\s*B\\b|\\bB\\s*(?:WIN|WINNER|VICTORY|WON)").containsMatchIn(lineText)) {
                        explicitWinner = "B"
                        hasWinnerKeyword = true
                    } else if (Regex("(?:WIN|WINNER|VICTORY|WON|BEST)\\s*[:\\-]?\\s*C\\b|\\bC\\s*(?:WIN|WINNER|VICTORY|WON)").containsMatchIn(lineText)) {
                        explicitWinner = "C"
                        hasWinnerKeyword = true
                    } else if (lineText.contains("WIN") || lineText.contains("VICTORY") || lineText.contains("WON")) {
                        hasWinnerKeyword = true
                        // If general WIN keyword found, check which zone the box is in!
                        if (centerX < cropWidth * 0.36f && explicitWinner == null) {
                            explicitWinner = "A"
                        } else if (centerX < cropWidth * 0.64f && explicitWinner == null) {
                            explicitWinner = "B"
                        } else if (explicitWinner == null) {
                            explicitWinner = "C"
                        }
                    }
                }
            }
        }

        // 4. Parse Hand Types / Ranks for Spot A, Spot B, Spot C
        val strA = zoneTextA.toString()
        val strB = zoneTextB.toString()
        val strC = zoneTextC.toString()

        val handDescA = extractHandDescription(strA, "Spot A")
        val handDescB = extractHandDescription(strB, "Spot B")
        val handDescC = extractHandDescription(strC, "Spot C")

        // 5. Visual Spot Brightness & Card Presence Assessment
        val spotMetrics = evaluateSpotVisuals(cardRegionCrop)
        cardRegionCrop.recycle()

        // 6. Winner Resolution Strategy:
        // Priority 1: Explicit OCR Winner Announcement ("WINNER A", "B WIN", etc.)
        // Priority 2: Golden Flower Rule Comparison if hand ranks are recognized
        // Priority 3: Visual Gold Highlight / Winning Crown Luminance Peak
        var finalWinner: String? = explicitWinner
        var confidence = 0.50f

        if (finalWinner != null) {
            confidence = 0.95f
        } else if (hasWinnerKeyword && spotMetrics.brightestSpot != null) {
            finalWinner = spotMetrics.brightestSpot
            confidence = 0.82f
        } else if (handDescA.score > 0 && handDescB.score > 0 && handDescC.score > 0) {
            finalWinner = GoldenFlowerRules.compareHands(handDescA, handDescB, handDescC)
            confidence = 0.85f
        } else if (spotMetrics.isHighlightSignificant && spotMetrics.brightestSpot != null) {
            finalWinner = spotMetrics.brightestSpot
            confidence = 0.72f
        }

        // Determine Game Phase & Status
        val phase: String
        val status: String
        val isVerified: Boolean

        if (finalWinner != null && confidence >= 0.70f) {
            phase = "SHOWDOWN"
            status = "Verified"
            isVerified = true
        } else if (hasBettingKeyword) {
            phase = "BETTING"
            status = "Betting Phase"
            isVerified = false
        } else if (spotMetrics.hasCardsVisible || handDescA.score > 0 || handDescB.score > 0 || handDescC.score > 0) {
            phase = "SHOWDOWN"
            status = if (finalWinner != null) "Showdown Detected" else "Result Not Verified"
            isVerified = (finalWinner != null && confidence >= 0.70f)
        } else {
            phase = "IDLE"
            status = "Observing Table"
            isVerified = false
        }

        val card1Display = handDescA.description
        val card2Display = handDescB.description
        val card3Display = handDescC.description

        val diag = "Phase: $phase | Winner: ${finalWinner ?: "None"} (${(confidence * 100).toInt()}%) | Text: ${allText.take(40)}"

        return CardRecognitionResult(
            card1 = card1Display,
            card2 = card2Display,
            card3 = card3Display,
            detectedWinner = finalWinner,
            detectionStatus = status,
            gamePhase = phase,
            confidence = confidence,
            diagnostics = diag
        )
    }

    private data class ScreenQuality(val isValid: Boolean, val statusMessage: String, val diagnostics: String)

    private fun checkScreenQuality(bitmap: Bitmap): ScreenQuality {
        val w = bitmap.width
        val h = bitmap.height
        var totalLum = 0L
        var sampleCount = 0

        for (y in 0 until h step 8) {
            for (x in 0 until w step 8) {
                val pixel = bitmap.getPixel(x, y)
                val lum = (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)).toInt()
                totalLum += lum
                sampleCount++
            }
        }

        val avgLum = if (sampleCount > 0) totalLum / sampleCount else 0L

        return when {
            avgLum < 15 -> ScreenQuality(false, "Screen Black / Blank", "Screen region is completely black (avg lum: $avgLum). Switch to Poppo Live Golden Flower.")
            avgLum > 248 -> ScreenQuality(false, "Screen White / Blank", "Screen region is blank white (avg lum: $avgLum).")
            else -> ScreenQuality(true, "OK", "Luminance normal ($avgLum)")
        }
    }

    private data class SpotMetrics(
        val brightestSpot: String?,
        val isHighlightSignificant: Boolean,
        val hasCardsVisible: Boolean
    )

    private fun evaluateSpotVisuals(bitmap: Bitmap): SpotMetrics {
        val w = bitmap.width
        val h = bitmap.height
        val zoneW = w / 3

        var lumA = 0L; var countA = 0
        var lumB = 0L; var countB = 0
        var lumC = 0L; var countC = 0
        var whiteCardPixels = 0

        for (y in 0 until h step 6) {
            for (x in 0 until w step 6) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()

                // High brightness + low saturation indicates white face-up card surface
                if (lum > 180 && Math.abs(r - g) < 25 && Math.abs(g - b) < 25) {
                    whiteCardPixels++
                }

                if (x < zoneW) {
                    lumA += lum; countA++
                } else if (x < zoneW * 2) {
                    lumB += lum; countB++
                } else {
                    lumC += lum; countC++
                }
            }
        }

        val avgA = if (countA > 0) lumA / countA else 0L
        val avgB = if (countB > 0) lumB / countB else 0L
        val avgC = if (countC > 0) lumC / countC else 0L

        val maxLum = maxOf(avgA, maxOf(avgB, avgC))
        val minLum = minOf(avgA, minOf(avgB, avgC))

        val brightest = when (maxLum) {
            avgA -> "A"
            avgB -> "B"
            else -> "C"
        }

        // In Poppo Live showdown, winning house has glowing gold highlight / victory aura
        val isSignificant = (maxLum - minLum > 28) && (maxLum > 75)
        val hasCards = whiteCardPixels > 30

        return SpotMetrics(brightest, isSignificant, hasCards)
    }

    private fun extractHandDescription(zoneText: String, defaultSpotLabel: String): com.scenebot.app.rules.HandEvaluation {
        val t = zoneText.uppercase()

        return when {
            t.contains("TRAIL") || t.contains("SET") || t.contains("THREE OF") || t.contains("BAOZI") || t.contains("豹子") -> {
                com.scenebot.app.rules.HandEvaluation(HandType.TRAIL, 600000, "$defaultSpotLabel: Trail (Set)")
            }
            t.contains("PURE SEQUENCE") || t.contains("STRAIGHT FLUSH") || t.contains("SHUNJIN") || t.contains("顺金") -> {
                com.scenebot.app.rules.HandEvaluation(HandType.PURE_SEQUENCE, 500000, "$defaultSpotLabel: Pure Sequence")
            }
            t.contains("SEQUENCE") || t.contains("STRAIGHT") || t.contains("SHUNZI") || t.contains("顺子") -> {
                com.scenebot.app.rules.HandEvaluation(HandType.SEQUENCE, 400000, "$defaultSpotLabel: Sequence (Straight)")
            }
            t.contains("COLOR") || t.contains("FLUSH") || t.contains("JINHUA") || t.contains("金花") -> {
                com.scenebot.app.rules.HandEvaluation(HandType.COLOR, 300000, "$defaultSpotLabel: Color (Flush)")
            }
            t.contains("PAIR") || t.contains("DUIZI") || t.contains("对子") -> {
                com.scenebot.app.rules.HandEvaluation(HandType.PAIR, 200000, "$defaultSpotLabel: Pair")
            }
            t.contains("HIGH") || t.contains("DANZHANG") || t.contains("单张") -> {
                com.scenebot.app.rules.HandEvaluation(HandType.HIGH_CARD, 100000, "$defaultSpotLabel: High Card")
            }
            else -> {
                // Try to parse raw card numbers or default to standard label
                val cards = mutableListOf<Card>()
                val matches = Regex("\\b([AKQJ]|10|[2-9])\\b").findAll(t)
                for (m in matches.take(3)) {
                    val rStr = m.value
                    val rank = when (rStr) {
                        "A" -> 14
                        "K" -> 13
                        "Q" -> 12
                        "J" -> 11
                        "10" -> 10
                        else -> rStr.toIntOrNull() ?: 8
                    }
                    cards.add(Card(rank, "♠"))
                }
                if (cards.size >= 3) {
                    GoldenFlowerRules.evaluate(cards)
                } else {
                    com.scenebot.app.rules.HandEvaluation(HandType.HIGH_CARD, 0, "$defaultSpotLabel: Cards Observed")
                }
            }
        }
    }
}
