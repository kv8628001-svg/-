package com.scenebot.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
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

        val tvCoords = findViewById<TextView>(R.id.tv_coords_preview)
        val tvTopLabel = findViewById<TextView>(R.id.tv_top_label)
        val tvBottomLabel = findViewById<TextView>(R.id.tv_bottom_label)
        val tvLeftLabel = findViewById<TextView>(R.id.tv_left_label)
        val tvRightLabel = findViewById<TextView>(R.id.tv_right_label)

        val seekTop = findViewById<SeekBar>(R.id.seek_top)
        val seekBottom = findViewById<SeekBar>(R.id.seek_bottom)
        val seekLeft = findViewById<SeekBar>(R.id.seek_left)
        val seekRight = findViewById<SeekBar>(R.id.seek_right)

        val btnPresetPhone = findViewById<Button>(R.id.btn_preset_poppo_phone)
        val btnPresetTablet = findViewById<Button>(R.id.btn_preset_tablet)
        val btnSave = findViewById<Button>(R.id.btn_save_calibration)
        val btnReset = findViewById<Button>(R.id.btn_reset_calibration)

        val norm = calibrationManager.getNormalizedRegion()

        seekLeft.progress = (norm.left * 100).toInt().coerceIn(0, 45)
        seekRight.progress = (norm.right * 100).toInt().coerceIn(55, 100)
        seekTop.progress = (norm.top * 100).toInt().coerceIn(0, 75)
        seekBottom.progress = (norm.bottom * 100).toInt().coerceIn(30, 100)

        fun updateLabels() {
            val l = seekLeft.progress
            val r = seekRight.progress
            val t = seekTop.progress
            val b = seekBottom.progress
            val span = (r - l) / 3
            tvTopLabel.text = "Top Y Margin: $t%"
            tvBottomLabel.text = "Bottom Y Margin: $b%"
            tvLeftLabel.text = "Left X Margin: $l%"
            tvRightLabel.text = "Right X Margin: $r%"
            tvCoords.text = "Game ROI: Left $l% | Top $t% | Right $r% | Bottom $b%\n(Zone A: $l-${l + span}%, Zone B: ${l + span}-${l + 2 * span}%, Zone C: ${l + 2 * span}-$r%)"
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (seekBottom.progress <= seekTop.progress) {
                    seekBottom.progress = (seekTop.progress + 10).coerceAtMost(100)
                }
                if (seekRight.progress <= seekLeft.progress) {
                    seekRight.progress = (seekLeft.progress + 15).coerceAtMost(100)
                }
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        seekTop.setOnSeekBarChangeListener(listener)
        seekBottom.setOnSeekBarChangeListener(listener)
        seekLeft.setOnSeekBarChangeListener(listener)
        seekRight.setOnSeekBarChangeListener(listener)

        updateLabels()

        btnPresetPhone.setOnClickListener {
            seekLeft.progress = (CalibrationManager.DEFAULT_LEFT_PCT * 100).toInt()
            seekRight.progress = (CalibrationManager.DEFAULT_RIGHT_PCT * 100).toInt()
            seekTop.progress = (CalibrationManager.DEFAULT_TOP_PCT * 100).toInt()
            seekBottom.progress = (CalibrationManager.DEFAULT_BOTTOM_PCT * 100).toInt()
            updateLabels()
            Toast.makeText(this, "Preset: Standard Poppo Golden Flower Table", Toast.LENGTH_SHORT).show()
        }

        btnPresetTablet.setOnClickListener {
            seekLeft.progress = 15
            seekRight.progress = 85
            seekTop.progress = 35
            seekBottom.progress = 68
            updateLabels()
            Toast.makeText(this, "Preset: Tablet / Wide Screen Table", Toast.LENGTH_SHORT).show()
        }

        btnSave.setOnClickListener {
            val leftF = seekLeft.progress / 100f
            val topF = seekTop.progress / 100f
            val rightF = seekRight.progress / 100f
            val bottomF = seekBottom.progress / 100f

            calibrationManager.saveCalibration(leftF, topF, rightF, bottomF)
            Toast.makeText(this, "Game region calibration saved!", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnReset.setOnClickListener {
            calibrationManager.resetCalibration()
            Toast.makeText(this, "Reset to default Poppo Live region", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
