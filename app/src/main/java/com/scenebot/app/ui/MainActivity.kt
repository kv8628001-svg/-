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
            Toast.makeText(this, "Screen capture permission required for SCENE Bot", Toast.LENGTH_LONG).show()
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
        binding.btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Clear Round History")
                .setMessage("Are you sure you want to delete all saved rounds?")
                .setPositiveButton("Clear") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        database.roundDao().clearAllRounds()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnSimulateRounds.setOnClickListener {
            simulateVerifiedRounds()
        }
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
        val count = rounds.size
        binding.tvTotalRounds.text = "$count"

        val prediction = patternAnalyzer.calculateStatisticalPrediction(rounds.reversed())

        binding.tvProbA.text = "${prediction.probA}%"
        binding.tvProbB.text = "${prediction.probB}%"
        binding.tvProbC.text = "${prediction.probC}%"

        binding.progressA.progress = prediction.probA
        binding.progressB.progress = prediction.probB
        binding.progressC.progress = prediction.probC

        binding.tvMostLikely.text = "Most Likely: Spot ${prediction.mostLikely}"
        binding.tvDataStatus.text = prediction.dataStatus

        val accStr = if (prediction.walkForwardAccuracy > 0f) "${String.format("%.1f", prediction.walkForwardAccuracy)}%" else "Calculating..."
        binding.tvVerifiedAccuracy.text = accStr
        binding.tvBrierScore.text = String.format("%.3f", prediction.brierScore)
        binding.tvBaselineComparison.text = prediction.baselineComparison
        binding.tvRationale.text = prediction.rationale
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

    private fun checkPermissionsAndStartBot() {
        if (!Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog()
            return
        }
        requestMediaProjection()
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Overlay Permission Required")
            .setMessage("SCENE Bot needs overlay permission to display the floating panel over Poppo Live.")
            .setPositiveButton("Grant") { _, _ ->
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
        Toast.makeText(this, "SCENE Bot is running! Switch to Poppo Live.", Toast.LENGTH_LONG).show()
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
