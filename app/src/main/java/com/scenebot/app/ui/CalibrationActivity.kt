package com.scenebot.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.scenebot.app.calibration.CalibrationManager
import com.scenebot.app.databinding.ActivityCalibrationBinding

class CalibrationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var calibrationManager: CalibrationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        calibrationManager = CalibrationManager(this)

        binding.btnSaveCalibration.setOnClickListener {
            calibrationManager.saveCurrentCardRegion(0.08f, 0.19f, 0.84f, 0.25f)
            Toast.makeText(this, "Card Region Calibrated Successfully!", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnResetCalibration.setOnClickListener {
            calibrationManager.saveCurrentCardRegion(0.08f, 0.19f, 0.84f, 0.25f)
            Toast.makeText(this, "Reset to Poppo Live Golden Flower default", Toast.LENGTH_SHORT).show()
        }
    }
}
