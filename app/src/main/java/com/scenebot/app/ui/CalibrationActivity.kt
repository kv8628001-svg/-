package com.scenebot.app.ui

import android.graphics.Rect
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.scenebot.app.R
import com.scenebot.app.calibration.CalibrationManager

class CalibrationActivity : AppCompatActivity() {
    private lateinit var calibrationManager: CalibrationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)
        calibrationManager = CalibrationManager(this)

        val tvInfo = findViewById<TextView>(R.id.tv_calibration_info)
        val btnSave = findViewById<Button>(R.id.btn_save_calibration)
        val btnReset = findViewById<Button>(R.id.btn_reset_calibration)

        tvInfo.text = "Drag or adjust the target box to align with the Golden Flower showdown card area in Poppo Live."

        btnSave.setOnClickListener {
            val dm = resources.displayMetrics
            val left = (dm.widthPixels * 0.15f).toInt()
            val top = (dm.heightPixels * 0.45f).toInt()
            val right = (dm.widthPixels * 0.85f).toInt()
            val bottom = (dm.heightPixels * 0.65f).toInt()
            calibrationManager.saveCalibration(Rect(left, top, right, bottom))
            Toast.makeText(this, "Calibration saved successfully!", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnReset.setOnClickListener {
            calibrationManager.resetCalibration()
            Toast.makeText(this, "Reset to default Golden Flower region", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
