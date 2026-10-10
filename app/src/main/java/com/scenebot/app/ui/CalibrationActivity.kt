package com.scenebot.app.ui

import android.graphics.Rect
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

        val dm = resources.displayMetrics
        val currentRect = calibrationManager.getCurrentCardRegion(dm.widthPixels, dm.heightPixels)

        // Initialize sliders from current saved percentage
        val curLeftPct = ((currentRect.left.toFloat() / dm.widthPixels) * 100).toInt().coerceIn(0, 50)
        val curRightPct = ((currentRect.right.toFloat() / dm.widthPixels) * 100).toInt().coerceIn(50, 100)
        val curTopPct = ((currentRect.top.toFloat() / dm.heightPixels) * 100).toInt().coerceIn(0, 80)
        val curBottomPct = ((currentRect.bottom.toFloat() / dm.heightPixels) * 100).toInt().coerceIn(40, 100)

        seekLeft.progress = curLeftPct
        seekRight.progress = curRightPct
        seekTop.progress = curTopPct
        seekBottom.progress = curBottomPct

        fun updateLabels() {
            tvTopLabel.text = "Top Y Margin: ${seekTop.progress}%"
            tvBottomLabel.text = "Bottom Y Margin: ${seekBottom.progress}%"
            tvLeftLabel.text = "Left X Margin: ${seekLeft.progress}%"
            tvRightLabel.text = "Right X Margin: ${seekRight.progress}%"
            tvCoords.text = "Box: Left ${seekLeft.progress}% | Top ${seekTop.progress}% | Right ${seekRight.progress}% | Bottom ${seekBottom.progress}%"
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (seekBottom.progress <= seekTop.progress) {
                    seekBottom.progress = (seekTop.progress + 5).coerceAtMost(100)
                }
                if (seekRight.progress <= seekLeft.progress) {
                    seekRight.progress = (seekLeft.progress + 5).coerceAtMost(100)
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
            seekLeft.progress = 15
            seekRight.progress = 85
            seekTop.progress = 45
            seekBottom.progress = 65
            updateLabels()
        }

        btnPresetTablet.setOnClickListener {
            seekLeft.progress = 20
            seekRight.progress = 80
            seekTop.progress = 40
            seekBottom.progress = 60
            updateLabels()
        }

        btnSave.setOnClickListener {
            val left = (dm.widthPixels * (seekLeft.progress / 100f)).toInt()
            val right = (dm.widthPixels * (seekRight.progress / 100f)).toInt()
            val top = (dm.heightPixels * (seekTop.progress / 100f)).toInt()
            val bottom = (dm.heightPixels * (seekBottom.progress / 100f)).toInt()

            calibrationManager.saveCalibration(Rect(left, top, right, bottom))
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
