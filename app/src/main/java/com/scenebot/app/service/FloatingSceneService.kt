package com.scenebot.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.scenebot.app.R
import com.scenebot.app.analysis.PatternAnalyzer
import com.scenebot.app.capture.ScreenCaptureManager
import com.scenebot.app.data.AppDatabase
import com.scenebot.app.data.RoundEntity
import com.scenebot.app.ui.CalibrationActivity
import com.scenebot.app.ui.MainActivity
import com.scenebot.app.vision.CardVisionRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class GameRoundState {
    BETTING_ACTIVE,    // Countdown active (15s..0s), chips being placed -> Pre-round prediction active!
    DEALING_CARDS,     // Cards dealing / stop betting
    SHOWDOWN_REVEAL,   // Cards revealed, winner declared -> Winner detection active!
    ROUND_COMMITTED,   // Winner saved to DB, next round prediction prepared!
    IDLE_OBSERVING     // Table observing, waiting for round to begin
}

class FloatingSceneService : Service() {

    companion object {
        private const val TAG = "FloatingSceneService"
        const val CHANNEL_ID = "SceneBot_Channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val ACTION_STOP = "com.scenebot.app.ACTION_STOP"
        const val MIN_ROUND_INTERVAL_MS = 6000L
        const val AUTO_RESET_TIMEOUT_MS = 14000L // Guaranteed fail-safe reset after 14s
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var expandedView: View? = null
    private var screenCaptureManager: ScreenCaptureManager? = null
    private lateinit var visionRecognizer: CardVisionRecognizer
    private lateinit var database: AppDatabase
    private val patternAnalyzer = PatternAnalyzer()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var scanJob: Job? = null
    private var isScanning = false

    // Explicit Round State Machine
    private var currentRoundState = GameRoundState.IDLE_OBSERVING
    private var isRoundCommittedForCurrentShowdown = false
    private var lastCommittedTime = 0L
    private var lastCommittedFingerprint = ""
    private var lastRecordedRoundSeq = 0L

    // Live Prediction State
    private var currentPredictedWinner = "A"
    private var currentProbA = 33
    private var currentProbB = 33
    private var currentProbC = 34
    private var latestAuditLogString = "Awaiting first round..."

    // Screen Dimensions
    private var screenWidth = 1080
    private var screenHeight = 1920
    private var density = 2.0f

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        visionRecognizer = CardVisionRecognizer(this)
        database = AppDatabase.getInstance(this)

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(dm)
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        density = dm.density

        createNotificationChannel()

        // Load latest state from database upon starting
        serviceScope.launch(Dispatchers.IO) {
            val history = database.roundDao().getAllRoundsChronological()
            lastRecordedRoundSeq = database.roundDao().getMaxSequenceNumber() ?: 0L
            val pred = patternAnalyzer.calculateStatisticalPrediction(history)
            currentPredictedWinner = pred.mostLikely
            currentProbA = pred.probA
            currentProbB = pred.probB
            currentProbC = pred.probC
            Log.i(TAG, "[SCENEBOT-INIT] Loaded ${history.size} historical rounds. Initial prediction: Spot ${pred.mostLikely} (A:${pred.probA}% B:${pred.probB}% C:${pred.probC}%)")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        // 1. Promote to Foreground Service safely with proper type
        val notification = buildForegroundNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && resultData != null) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
        }

        // 2. GUARANTEE: Setup floating overlay view immediately over Poppo Live
        if (floatingView == null && Settings.canDrawOverlays(this)) {
            setupFloatingView()
        }

        // 3. Initialize ScreenCaptureManager safely if result data is present
        if (resultData != null && screenCaptureManager == null) {
            try {
                screenCaptureManager = ScreenCaptureManager(this, resultCode, resultData)
                startAutomaticScanLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Screen capture init error: ${e.message}", e)
            }
        }

        return START_STICKY
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun setupFloatingView() {
        if (!Settings.canDrawOverlays(this)) return

        val inflater = LayoutInflater.from(this)
        val pillView = inflater.inflate(R.layout.layout_floating_scene_pill, null)
        floatingView = pillView

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val pillSizePx = (60 * density).toInt()

        val params = WindowManager.LayoutParams(
            pillSizePx,
            pillSizePx,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - pillSizePx - (16 * density).toInt()).coerceAtLeast(0)
            y = (screenHeight * 0.35f).toInt()
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        pillView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    pillView.alpha = 0.85f
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                        isClick = false
                    }
                    params.x = (initialX + deltaX).coerceIn(0, screenWidth - pillSizePx)
                    params.y = (initialY + deltaY).coerceIn(0, screenHeight - pillSizePx)
                    try {
                        windowManager.updateViewLayout(pillView, params)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    pillView.alpha = 1.0f
                    if (isClick) {
                        toggleExpandedPanel(params.x, params.y)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(pillView, params)
            Log.i(TAG, "Floating bot button added at (${params.x}, ${params.y})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating view: ${e.message}", e)
        }
    }

    private fun toggleExpandedPanel(anchorX: Int, anchorY: Int) {
        if (expandedView != null) {
            safeRemoveView(expandedView)
            expandedView = null
            floatingView?.visibility = View.VISIBLE
            return
        }

        val inflater = LayoutInflater.from(this)
        expandedView = inflater.inflate(R.layout.layout_floating_scene_expanded, null)

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val expWidthPx = (290 * density).toInt()

        val expParams = WindowManager.LayoutParams(
            expWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchorX.coerceIn(16, (screenWidth - expWidthPx - 16).coerceAtLeast(16))
            y = anchorY.coerceIn(60, (screenHeight - (400 * density).toInt()).coerceAtLeast(60))
        }

        expandedView?.findViewById<View>(R.id.btn_minimize)?.setOnClickListener {
            toggleExpandedPanel(anchorX, anchorY)
        }

        expandedView?.findViewById<View>(R.id.btn_scan)?.setOnClickListener {
            Toast.makeText(this, "Scanning screen...", Toast.LENGTH_SHORT).show()
            serviceScope.launch(Dispatchers.IO) {
                performSingleScan()
            }
        }

        expandedView?.findViewById<View>(R.id.btn_calibrate)?.setOnClickListener {
            val calibIntent = Intent(this, CalibrationActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(calibIntent)
        }

        expandedView?.findViewById<View>(R.id.btn_history)?.setOnClickListener {
            val historyIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("open_history", true)
            }
            startActivity(historyIntent)
        }

        expandedView?.findViewById<View>(R.id.btn_stop)?.setOnClickListener {
            Toast.makeText(this, "SCENE Bot Stopped", Toast.LENGTH_SHORT).show()
            stopSelf()
        }

        floatingView?.visibility = View.GONE
        try {
            windowManager.addView(expandedView, expParams)
            refreshExpandedUi()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add expanded view: ${e.message}", e)
            floatingView?.visibility = View.VISIBLE
        }
    }

    private fun safeRemoveView(view: View?) {
        if (view != null && view.isAttachedToWindow) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {}
        }
    }

    private fun startAutomaticScanLoop() {
        isScanning = true
        scanJob?.cancel()
        scanJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (isScanning) {
                    performSingleScan()
                }
                delay(1200)
            }
        }
    }

    private suspend fun performSingleScan() {
        val bitmap = screenCaptureManager?.acquireLatestScreenshot() ?: return
        val recognition = visionRecognizer.processScreen(bitmap)
        bitmap.recycle()

        if (recognition == null) return

        val now = System.currentTimeMillis()

        // FAIL-SAFE UNLOCK: If 14s passed since last showdown commit,
        // automatically unlock commitment so next round is NEVER dropped!
        if (isRoundCommittedForCurrentShowdown && (now - lastCommittedTime >= AUTO_RESET_TIMEOUT_MS)) {
            Log.i(TAG, "[SCENEBOT-TIMEOUT] 14s elapsed since last round commit. Unlocking commitment state for next round.")
            isRoundCommittedForCurrentShowdown = false
            currentRoundState = GameRoundState.BETTING_ACTIVE
        }

        // 1. Update Game Round State according to vision detection
        when (recognition.gamePhase) {
            "BETTING" -> {
                currentRoundState = GameRoundState.BETTING_ACTIVE
                isRoundCommittedForCurrentShowdown = false // Reset: New betting round has begun!
            }
            "DEALING" -> {
                currentRoundState = GameRoundState.DEALING_CARDS
            }
            "SHOWDOWN" -> {
                currentRoundState = GameRoundState.SHOWDOWN_REVEAL
            }
            else -> {
                if (!isRoundCommittedForCurrentShowdown) {
                    currentRoundState = GameRoundState.IDLE_OBSERVING
                }
            }
        }

        // 2. Update HUD live detection feedback immediately
        serviceScope.launch(Dispatchers.Main) {
            expandedView?.let { view ->
                val stateText = when (currentRoundState) {
                    GameRoundState.BETTING_ACTIVE -> "BETTING TIME (Place Bets)"
                    GameRoundState.DEALING_CARDS -> "DEALING CARDS..."
                    GameRoundState.SHOWDOWN_REVEAL -> "SHOWDOWN: ${recognition.detectionStatus}"
                    GameRoundState.ROUND_COMMITTED -> "ROUND COMMITTED ✓ (Preparing Next)"
                    GameRoundState.IDLE_OBSERVING -> "Observing Table"
                }
                view.findViewById<TextView>(R.id.tv_current_cards)?.text =
                    "Spots: A: ${recognition.card1} | B: ${recognition.card2} | C: ${recognition.card3}"
                view.findViewById<TextView>(R.id.tv_detection_status)?.text =
                    "State: $stateText | ${(recognition.confidence * 100).toInt()}% Conf"
                view.findViewById<TextView>(R.id.tv_debug_audit)?.text = latestAuditLogString
            }
        }

        // 3. SHOWDOWN WINNER COMMITMENT:
        // Only trigger when winner is identified with confidence >= 0.70f and has not already been committed
        val winner = recognition.detectedWinner
        if (recognition.gamePhase == "SHOWDOWN" && winner != null && recognition.confidence >= 0.70f) {
            val rawFp = "${recognition.card1}_${recognition.card2}_${recognition.card3}_$winner"
            val md = MessageDigest.getInstance("MD5")
            val fingerprint = md.digest(rawFp.toByteArray()).joinToString("") { "%02x".format(it) }

            // Deduplication Check:
            // Block only if already committed for this exact round showdown window (< 10 seconds)
            if (isRoundCommittedForCurrentShowdown) {
                return
            }
            if (fingerprint == lastCommittedFingerprint && (now - lastCommittedTime < MIN_ROUND_INTERVAL_MS)) {
                return
            }

            // --- COMMIT NEW REAL ROUND ---
            isRoundCommittedForCurrentShowdown = true
            currentRoundState = GameRoundState.ROUND_COMMITTED
            lastCommittedTime = now
            lastCommittedFingerprint = fingerprint

            val historyBefore = database.roundDao().getAllRoundsChronological()
            val priorPrediction = patternAnalyzer.calculateStatisticalPrediction(historyBefore)
            val isPredictionCorrect = winner.equals(priorPrediction.mostLikely, ignoreCase = true)

            val maxSeq = database.roundDao().getMaxSequenceNumber() ?: 0L
            val nextSeq = maxSeq + 1L
            lastRecordedRoundSeq = nextSeq

            val newRound = RoundEntity(
                roundSequenceNumber = nextSeq,
                timestamp = now,
                card1 = recognition.card1,
                card2 = recognition.card2,
                card3 = recognition.card3,
                detectionStatus = recognition.detectionStatus,
                actualWinner = winner,
                predictedWinner = priorPrediction.mostLikely,
                predictedProbA = priorPrediction.probA,
                predictedProbB = priorPrediction.probB,
                predictedProbC = priorPrediction.probC,
                predictionCorrect = isPredictionCorrect,
                historicalAccuracyAtRound = priorPrediction.walkForwardAccuracy,
                brierScoreAtRound = priorPrediction.brierScore,
                source = "AUTO_CAPTURE",
                fingerprint = fingerprint
            )

            val insertedDbId = database.roundDao().insertRound(newRound)

            // --- RECALCULATE PREDICTIONS FOR NEXT ROUND WITH FRESH DATABASE DATA ---
            val freshHistory = database.roundDao().getAllRoundsChronological()
            val totalReal = freshHistory.count { it.source == "AUTO_CAPTURE" }
            val nextPrediction = patternAnalyzer.calculateStatisticalPrediction(freshHistory)

            currentPredictedWinner = nextPrediction.mostLikely
            currentProbA = nextPrediction.probA
            currentProbB = nextPrediction.probB
            currentProbC = nextPrediction.probC

            val timeStr = timeFormat.format(Date(now))
            latestAuditLogString = "Audit [$timeStr]: State=COMMITTED | Winner=$winner | DbId=$insertedDbId | Real=$totalReal | Next=Spot $currentPredictedWinner ($currentProbA%/$currentProbB%/$currentProbC%)"

            // Comprehensive Debug Logging as specified in User Requirement #7
            Log.i(
                TAG,
                "[SCENEBOT-ROUND-AUDIT] RoundState: ROUND_COMMITTED | DetectedWinner: $winner | DbSave: SUCCESS(id=$insertedDbId) | TotalRealRounds: $totalReal | Timestamp: $timeStr | Prob: A=${nextPrediction.probA}% B=${nextPrediction.probB}% C=${nextPrediction.probC}% | MostLikely: Spot ${nextPrediction.mostLikely}"
            )

            // Refresh UI immediately
            refreshExpandedUi()
        }
    }

    private fun refreshExpandedUi() {
        val view = expandedView ?: return
        serviceScope.launch(Dispatchers.IO) {
            val history = database.roundDao().getAllRoundsChronological()
            val totalCount = history.size
            val realCount = history.count { it.source == "AUTO_CAPTURE" }
            val prediction = patternAnalyzer.calculateStatisticalPrediction(history)
            val latest = history.lastOrNull()

            val displayRoundNum = if (latest != null) latest.roundSequenceNumber + 1 else 1L
            val isBetting = (currentRoundState == GameRoundState.BETTING_ACTIVE)
            val roundTitle = if (isBetting) {
                "Round #$displayRoundNum (Betting Time)"
            } else {
                "Round #$displayRoundNum (Live)"
            }

            serviceScope.launch(Dispatchers.Main) {
                view.findViewById<TextView>(R.id.tv_round_count)?.text = roundTitle
                latest?.let {
                    view.findViewById<TextView>(R.id.tv_current_cards)?.text =
                        "Last Round #${it.roundSequenceNumber}: Winner Spot ${it.actualWinner} (${it.card1})"
                }

                view.findViewById<TextView>(R.id.tv_possible_a)?.text = "Spot A: ${prediction.probA}%"
                view.findViewById<TextView>(R.id.tv_possible_b)?.text = "Spot B: ${prediction.probB}%"
                view.findViewById<TextView>(R.id.tv_possible_c)?.text = "Spot C: ${prediction.probC}%"

                view.findViewById<ProgressBar>(R.id.progress_a)?.progress = prediction.probA
                view.findViewById<ProgressBar>(R.id.progress_b)?.progress = prediction.probB
                view.findViewById<ProgressBar>(R.id.progress_c)?.progress = prediction.probC

                val mostLikelyText = if (isBetting) {
                    "★ CURRENT BETTING SIGNAL: Spot ${prediction.mostLikely} (${prediction.confidence}%)"
                } else {
                    "Next Round Signal: Spot ${prediction.mostLikely} (${prediction.confidence}%)"
                }
                view.findViewById<TextView>(R.id.tv_most_likely)?.text = mostLikelyText

                val timeStr = timeFormat.format(Date(prediction.calculationTimestamp))
                view.findViewById<TextView>(R.id.tv_data_status)?.text =
                    "Data: $realCount Real Observed Rounds ($totalCount total) | Updated: $timeStr"

                val accStr = if (prediction.walkForwardAccuracy > 0f) {
                    "${String.format("%.1f", prediction.walkForwardAccuracy)}%"
                } else {
                    "Calculating (<5 rounds)"
                }
                view.findViewById<TextView>(R.id.tv_verified_accuracy)?.text = "Verified Accuracy: $accStr"
                view.findViewById<TextView>(R.id.tv_brier_score)?.text =
                    "Brier Score: ${String.format("%.3f", prediction.brierScore)} (Baseline: 0.667)"

                view.findViewById<TextView>(R.id.tv_debug_audit)?.text = latestAuditLogString
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SCENE Bot Active")
            .setContentText("Observing Poppo Live Golden Flower screen in background...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SCENE Bot Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows persistent status while SCENE Bot is observing screen"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanJob?.cancel()
        screenCaptureManager?.stop()
        safeRemoveView(floatingView)
        safeRemoveView(expandedView)
        floatingView = null
        expandedView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
