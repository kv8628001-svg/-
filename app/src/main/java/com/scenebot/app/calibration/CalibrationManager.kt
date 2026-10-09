package com.scenebot.app.calibration

import android.content.Context
import android.graphics.Rect

class CalibrationManager(context: Context) {
    private val prefs = context.getSharedPreferences("scene_bot_calibration", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LEFT = "calib_left"
        private const val KEY_TOP = "calib_top"
        private const val KEY_RIGHT = "calib_right"
        private const val KEY_BOTTOM = "calib_bottom"
        private const val KEY_IS_CALIBRATED = "is_calibrated"
    }

    fun saveCalibration(rect: Rect) {
        prefs.edit()
            .putInt(KEY_LEFT, rect.left)
            .putInt(KEY_TOP, rect.top)
            .putInt(KEY_RIGHT, rect.right)
            .putInt(KEY_BOTTOM, rect.bottom)
            .putBoolean(KEY_IS_CALIBRATED, true)
            .apply()
    }

    fun getCurrentCardRegion(screenWidth: Int, screenHeight: Int): Rect {
        if (prefs.getBoolean(KEY_IS_CALIBRATED, false)) {
            return Rect(
                prefs.getInt(KEY_LEFT, 0),
                prefs.getInt(KEY_TOP, 0),
                prefs.getInt(KEY_RIGHT, screenWidth),
                prefs.getInt(KEY_BOTTOM, screenHeight)
            )
        }
        // Default Golden Flower showdown card area in Poppo Live
        // Centered vertically between 45% and 65% of screen height
        val left = (screenWidth * 0.15f).toInt()
        val top = (screenHeight * 0.45f).toInt()
        val right = (screenWidth * 0.85f).toInt()
        val bottom = (screenHeight * 0.65f).toInt()
        return Rect(left, top, right, bottom)
    }

    fun resetCalibration() {
        prefs.edit().clear().apply()
    }
}
