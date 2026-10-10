package com.scenebot.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.scenebot.app.analysis.PatternAnalyzer
import com.scenebot.app.data.AppDatabase
import com.scenebot.app.data.RoundEntity
import com.scenebot.app.databinding.ActivityMainBinding
import com.scenebot.app.service.FloatingSceneService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Random

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var database: AppDatabase
    private val patternAnalyzer = PatternAnalyzer()
    private val historyAdapter = RoundHistoryAdapter()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            requestMediaProjection()
        } else {
            Toast.makeText(this, "Notification permission recommended for foreground bot", Toast.LENGTH_SHORT).show()
            requestMediaProjection()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionStatus()
        if (Settings.canDrawOverlays(this)) {
            checkNotificationAndProceed()
        } else {
            Toast.makeText(this, "Overlay permission required for Floating Bot Button", Toast.LENGTH_LONG).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startFloatingSceneService(result.resultCode, result.data!!)
        } else {
            binding.tvStatusCapture.text = "Capture Denied ✗"
            binding.tvStatusCapture.setTextColor(Color.parseColor("#EF4444"))
            Toast.makeText(this, "Screen capture permission is required for SCENE Bot", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        database = AppDatabase.getInstance(this)

        setupRecyclerView()
        setupClickListeners()
        observeDatabaseRounds()
        updatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupRecyclerView() {
        binding.rvRoundHistory.layoutManager = LinearLayoutManager(this)
        binding.rvRoundHistory.adapter = historyAdapter
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

        binding.btnCorrectRound.setOnClickListener {
            showCorrectLatestRoundDialog()
        }

        binding.btnClearHistory.setOnClickListener {
            showClearHistoryDialog()
        }

        binding.btnSimulateRounds.setOnClickListener {
            simulateVerifiedRounds()
        }
    }

    private fun updatePermissionStatus() {
        val hasOverlay = Settings.canDrawOverlays(this)
        if (hasOverlay) {
            binding.tvStatusOverlay.text = "Granted ✓"
            binding.tvStatusOverlay.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.tvStatusOverlay.text = "Not Granted ✗"
            binding.tvStatusOverlay.setTextColor(Color.parseColor("#EF4444"))
        }
    }

    private fun checkPermissionsAndStartBot() {
        if (!Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog()
            return
        }
        checkNotificationAndProceed()
    }

    private fun checkNotificationAndProceed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestMediaProjection()
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Overlay Permission Required")
            .setMessage("SCENE Bot needs 'Display over other apps' permission so the small floating button appears over Poppo Live.\n\nPlease toggle 'Allow display over other apps' on the next screen.")
            .setPositiveButton("Open Settings") { _, _ ->
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

        binding.tvStatusCapture.text = "Active (Foreground) ✓"
        binding.tvStatusCapture.setTextColor(Color.parseColor("#10B981"))

        Toast.makeText(this, "SCENE Bot started! Floating button is now active. Switch to Poppo Live.", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun stopFloatingSceneService() {
        val stopIntent = Intent(this, FloatingSceneService::class.java).apply {
            action = FloatingSceneService.ACTION_STOP
        }
        startService(stopIntent)

        binding.tvStatusCapture.text = "Stopped"
        binding.tvStatusCapture.setTextColor(Color.parseColor("#94A3B8"))

        Toast.makeText(this, "SCENE Bot stopped", Toast.LENGTH_SHORT).show()
    }

    private fun observeDatabaseRounds() {
        lifecycleScope.launch {
            database.roundDao().getAllRoundsFlow().collect { rounds ->
                historyAdapter.submitList(rounds)
                updateAnalyticsDashboard(rounds)
            }
        }
    }

    private fun updateAnalyticsDashboard(rounds: List<RoundEntity>) {
        val totalCount = rounds.size
        val realCount = rounds.count { it.source != "SIMULATION" }
        val simCount = rounds.count { it.source == "SIMULATION" }

        binding.tvTotalRounds.text = "$totalCount"
        binding.tvStatusRealRounds.text = "$realCount real ($simCount sim)"

        val prediction = patternAnalyzer.calculateStatisticalPrediction(rounds.reversed())

        binding.tvProbA.text = "${prediction.probA}%"
        binding.tvProbB.text = "${prediction.probB}%"
        binding.tvProbC.text = "${prediction.probC}%"

        binding.progressA.progress = prediction.probA
        binding.progressB.progress = prediction.probB
        binding.progressC.progress = prediction.probC

        binding.tvMostLikely.text = "Most Likely: Spot ${prediction.mostLikely}"
        binding.tvDataStatus.text = prediction.dataStatus

        val accStr = if (prediction.walkForwardAccuracy > 0f) {
            "${String.format("%.1f", prediction.walkForwardAccuracy)}%"
        } else {
            "Not Enough Data (<5)"
        }
        binding.tvVerifiedAccuracy.text = accStr
        binding.tvBrierScore.text = String.format("%.3f", prediction.brierScore)
        binding.tvBaselineComparison.text = prediction.baselineComparison
        binding.tvRationale.text = prediction.rationale
    }

    private fun showCorrectLatestRoundDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val latest = database.roundDao().getLatestRound()
            withContext(Dispatchers.Main) {
                if (latest == null) {
                    Toast.makeText(this@MainActivity, "No rounds to correct", Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                val spots = arrayOf("Spot A", "Spot B", "Spot C")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Correct Round #${latest.roundSequenceNumber} Winner")
                    .setMessage("Current recorded winner: Spot ${latest.actualWinner}\nIf the screen OCR misidentified the winner, select the actual winner:")
                    .setItems(spots) { _, which ->
                        val newWinner = when (which) {
                            0 -> "A"
                            1 -> "B"
                            else -> "C"
                        }
                        lifecycleScope.launch(Dispatchers.IO) {
                            val updated = latest.copy(
                                actualWinner = newWinner,
                                predictionCorrect = newWinner.equals(latest.predictedWinner, ignoreCase = true),
                                detectionStatus = "Manual Correction"
                            )
                            database.roundDao().updateRound(updated)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Round #${latest.roundSequenceNumber} corrected to Spot $newWinner", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun showClearHistoryDialog() {
        val options = arrayOf("Clear Only Simulated Rounds", "Clear All Rounds (Real & Sim)")
        AlertDialog.Builder(this)
            .setTitle("Clear History Options")
            .setItems(options) { _, which ->
                lifecycleScope.launch(Dispatchers.IO) {
                    if (which == 0) {
                        val current = database.roundDao().getAllRoundsChronological()
                        val realOnly = current.filter { it.source != "SIMULATION" }
                        database.roundDao().clearAllRounds()
                        for (r in realOnly) {
                            database.roundDao().insertRound(r)
                        }
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Simulated rounds removed; Real history preserved", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        database.roundDao().clearAllRounds()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "All history cleared", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun simulateVerifiedRounds() {
        lifecycleScope.launch(Dispatchers.IO) {
            val random = Random()
            val spots = listOf("A", "B", "C")
            val currentRounds = database.roundDao().getAllRoundsChronological().toMutableList()

            for (i in 1..15) {
                val winner = spots[random.nextInt(3)]
                val c1 = "Card ${random.nextInt(13) + 2}"
                val c2 = "Card ${random.nextInt(13) + 2}"
                val c3 = "Card ${random.nextInt(13) + 2}"

                val prediction = patternAnalyzer.calculateStatisticalPrediction(currentRounds)
                val isCorrect = winner.equals(prediction.mostLikely, ignoreCase = true)

                val maxSeq = database.roundDao().getMaxSequenceNumber() ?: 0L
                val nextSeq = maxSeq + 1L

                val round = RoundEntity(
                    roundSequenceNumber = nextSeq,
                    timestamp = System.currentTimeMillis() - (16 - i) * 15000L,
                    card1 = c1,
                    card2 = c2,
                    card3 = c3,
                    detectionStatus = "Verified",
                    actualWinner = winner,
                    predictedWinner = prediction.mostLikely,
                    predictedProbA = prediction.probA,
                    predictedProbB = prediction.probB,
                    predictedProbC = prediction.probC,
                    predictionCorrect = isCorrect,
                    historicalAccuracyAtRound = prediction.walkForwardAccuracy,
                    brierScoreAtRound = prediction.brierScore,
                    source = "SIMULATION",
                    fingerprint = "sim_${System.currentTimeMillis()}_$i"
                )

                database.roundDao().insertRound(round)
                currentRounds.add(round)
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Added 15 simulated rounds for walk-forward verification", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
