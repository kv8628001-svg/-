package com.scenebot.app.calibration

import android.content.Context
import android.graphics.Rect

class CalibrationManager(context: Context) {
    private val prefs = context.getSharedPreferences("scene_bot_calibration", Context.MODE_PRIVATE)

    fun getCurrentCardRegion(screenWidth: Int, screenHeight: Int): Rect {
        val leftPct = prefs.getFloat("c_left", 0.08f)
        val topPct = prefs.getFloat("c_top", 0.19f)
        val widthPct = prefs.getFloat("c_width", 0.84f)
        val heightPct = prefs.getFloat("c_height", 0.25f)
        return Rect(
            (screenWidth * leftPct).toInt(),
            (screenHeight * topPct).toInt(),
            (screenWidth * (leftPct + widthPct)).toInt(),
            (screenHeight * (topPct + heightPct)).toInt()
        )
    }

    fun saveCurrentCardRegion(leftPct: Float, topPct: Float, widthPct: Float, heightPct: Float) {
        prefs.edit()
            .putFloat("c_left", leftPct)
            .putFloat("c_top", topPct)
            .putFloat("c_width", widthPct)
            .putFloat("c_height", heightPct)
            .apply()
    }
}
