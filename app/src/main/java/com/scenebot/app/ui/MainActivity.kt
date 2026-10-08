package com.scenebot.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.scenebot.app.databinding.ActivityMainBinding
import com.scenebot.app.service.FloatingSceneService

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkPermissionsAndStartBot()
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startFloatingSceneService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission is required for SCENE Bot", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        setupClickListeners()
    }

    private fun setupClickListeners() {
        binding.btnStartBot.setOnClickListener {
            checkPermissionsAndStartBot()
        }
        binding.btnStopBot.setOnClickListener {
            stopFloatingSceneService()
        }
        binding.btnCalibrateRegions.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
    }

    private fun checkPermissionsAndStartBot() {
        // Step 1: Check Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog()
            return
        }
        // Step 2: Request MediaProjection token
        requestMediaProjection()
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Overlay Permission Required")
            .setMessage("SCENE Bot needs overlay permission to display the draggable floating SCENE button on top of your game.")
            .setPositiveButton("Grant Permission") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestMediaProjection() {
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(captureIntent)
    }

    private fun startFloatingSceneService(resultCode: Int, data: Intent) {
        val serviceIntent = Intent(this, FloatingSceneService::class.java).apply {
            putExtra(FloatingSceneService.EXTRA_RESULT_CODE, resultCode)
            putExtra(FloatingSceneService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "SCENE Bot is running. Switch to Poppo Live!", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun stopFloatingSceneService() {
        val stopIntent = Intent(this, FloatingSceneService::class.java).apply {
            action = FloatingSceneService.ACTION_STOP
        }
        startService(stopIntent)
        Toast.makeText(this, "SCENE Bot stopped", Toast.LENGTH_SHORT).show()
    }
}
