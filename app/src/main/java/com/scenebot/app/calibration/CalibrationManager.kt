package com.scenebot.app.calibration

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF

class CalibrationManager(context: Context) {

    private val prefs = context.getSharedPreferences("scene_bot_calibration", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LEFT_PCT = "calib_left_pct"
        private const val KEY_TOP_PCT = "calib_top_pct"
        private const val KEY_RIGHT_PCT = "calib_right_pct"
        private const val KEY_BOTTOM_PCT = "calib_bottom_pct"
        private const val KEY_IS_CALIBRATED = "is_calibrated"

        // Default Poppo Live Golden Flower table crop:
        // Spans across horizontally (10% to 90%) and lower-middle vertically (38% to 72%)
        const val DEFAULT_LEFT_PCT = 0.10f
        const val DEFAULT_TOP_PCT = 0.38f
        const val DEFAULT_RIGHT_PCT = 0.90f
        const val DEFAULT_BOTTOM_PCT = 0.72f
    }

    fun saveCalibration(leftPct: Float, topPct: Float, rightPct: Float, bottomPct: Float) {
        val safeLeft = leftPct.coerceIn(0.0f, 0.45f)
        val safeTop = topPct.coerceIn(0.0f, 0.75f)
        val safeRight = rightPct.coerceIn(safeLeft + 0.1f, 1.0f)
        val safeBottom = bottomPct.coerceIn(safeTop + 0.1f, 1.0f)

        prefs.edit()
            .putFloat(KEY_LEFT_PCT, safeLeft)
            .putFloat(KEY_TOP_PCT, safeTop)
            .putFloat(KEY_RIGHT_PCT, safeRight)
            .putFloat(KEY_BOTTOM_PCT, safeBottom)
            .putBoolean(KEY_IS_CALIBRATED, true)
            .apply()
    }

    fun getNormalizedRegion(): RectF {
        if (prefs.getBoolean(KEY_IS_CALIBRATED, false)) {
            val left = prefs.getFloat(KEY_LEFT_PCT, DEFAULT_LEFT_PCT)
            val top = prefs.getFloat(KEY_TOP_PCT, DEFAULT_TOP_PCT)
            val right = prefs.getFloat(KEY_RIGHT_PCT, DEFAULT_RIGHT_PCT)
            val bottom = prefs.getFloat(KEY_BOTTOM_PCT, DEFAULT_BOTTOM_PCT)
            return RectF(left, top, right, bottom)
        }
        return RectF(DEFAULT_LEFT_PCT, DEFAULT_TOP_PCT, DEFAULT_RIGHT_PCT, DEFAULT_BOTTOM_PCT)
    }

    fun getCurrentCardRegion(screenWidth: Int, screenHeight: Int): Rect {
        val norm = getNormalizedRegion()
        val left = (screenWidth * norm.left).toInt().coerceIn(0, screenWidth - 10)
        val top = (screenHeight * norm.top).toInt().coerceIn(0, screenHeight - 10)
        val right = (screenWidth * norm.right).toInt().coerceIn(left + 10, screenWidth)
        val bottom = (screenHeight * norm.bottom).toInt().coerceIn(top + 10, screenHeight)
        return Rect(left, top, right, bottom)
    }

    fun resetCalibration() {
        prefs.edit().clear().apply()
    }
}
