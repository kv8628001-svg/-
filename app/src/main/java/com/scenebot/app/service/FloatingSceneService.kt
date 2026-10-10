package com.scenebot.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
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
import com.scenebot.app.ui.MainActivity
import com.scenebot.app.vision.CardVisionRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest

class FloatingSceneService : Service() {

    companion object {
        private const val TAG = "FloatingSceneService"
        const val CHANNEL_ID = "SceneBot_Channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val ACTION_STOP = "com.scenebot.app.ACTION_STOP"
        const val MIN_ROUND_INTERVAL_MS = 6000L
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

    // Deduplication & State Tracking
    private var lastSavedFingerprint = ""
    private var lastRecordedTime = 0L
    private var currentPredictedWinner = "A"
    private var currentProbA = 33
    private var currentProbB = 33
    private var currentProbC = 34

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        visionRecognizer = CardVisionRecognizer(this)
        database = AppDatabase.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 1. Promote to Foreground Service immediately
        val notification = buildForegroundNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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

        // 2. GUARANTEE: Setup floating overlay view immediately
        if (floatingView == null && Settings.canDrawOverlays(this)) {
            setupFloatingView()
        }

        // 3. Initialize ScreenCaptureManager safely if result data is present
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

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

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 240
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(deltaX) > 12 || Math.abs(deltaY) > 12) {
                        isClick = false
                    }
                    params.x = (initialX + deltaX).coerceAtLeast(0)
                    params.y = (initialY + deltaY).coerceAtLeast(0)
                    try {
                        windowManager.updateViewLayout(pillView, params)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
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

        val expParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchorX.coerceAtLeast(16)
            y = anchorY.coerceAtLeast(80)
        }

        expandedView?.findViewById<View>(R.id.btn_minimize)?.setOnClickListener {
            toggleExpandedPanel(anchorX, anchorY)
        }

        expandedView?.findViewById<View>(R.id.btn_scan)?.setOnClickListener {
            serviceScope.launch(Dispatchers.IO) {
                performSingleScan()
            }
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

        // If card detection status is "Card Not Detected", update panel status without saving fake round
        if (recognition.detectionStatus == "Card Not Detected") {
            serviceScope.launch(Dispatchers.Main) {
                expandedView?.findViewById<TextView>(R.id.tv_current_cards)?.text = "Cards: Card Not Detected"
            }
            return
        }

        // Deduplication fingerprint
        val rawFp = "${recognition.card1}_${recognition.card2}_${recognition.card3}_${recognition.detectedWinner ?: "none"}"
        val md = MessageDigest.getInstance("MD5")
        val fingerprint = md.digest(rawFp.toByteArray()).joinToString("") { "%02x".format(it) }

        val currentTime = System.currentTimeMillis()

        // Deduplication Gate
        if (fingerprint == lastSavedFingerprint || (currentTime - lastRecordedTime < MIN_ROUND_INTERVAL_MS)) {
            refreshExpandedUi()
            return
        }

        // STRICT TRUTHFULNESS: Only commit verified rounds with known winner
        if (recognition.confidence >= 0.70f && recognition.detectedWinner != null) {
            lastSavedFingerprint = fingerprint
            lastRecordedTime = currentTime

            val history = database.roundDao().getAllRoundsChronological()
            val prediction = patternAnalyzer.calculateStatisticalPrediction(history)
            val actualWinner = recognition.detectedWinner
            val isCorrect = actualWinner.equals(prediction.mostLikely, ignoreCase = true)

            val maxSeq = database.roundDao().getMaxSequenceNumber() ?: 0L
            val nextSeq = maxSeq + 1L

            val newRound = RoundEntity(
                roundSequenceNumber = nextSeq,
                timestamp = currentTime,
                card1 = recognition.card1,
                card2 = recognition.card2,
                card3 = recognition.card3,
                detectionStatus = recognition.detectionStatus,
                actualWinner = actualWinner,
                predictedWinner = prediction.mostLikely,
                predictedProbA = prediction.probA,
                predictedProbB = prediction.probB,
                predictedProbC = prediction.probC,
                predictionCorrect = isCorrect,
                historicalAccuracyAtRound = prediction.walkForwardAccuracy,
                brierScoreAtRound = prediction.brierScore,
                source = "AUTO_CAPTURE",
                fingerprint = fingerprint
            )

            database.roundDao().insertRound(newRound)

            // Update predictions for next round
            val updatedHistory = database.roundDao().getAllRoundsChronological()
            val nextPred = patternAnalyzer.calculateStatisticalPrediction(updatedHistory)
            currentPredictedWinner = nextPred.mostLikely
            currentProbA = nextPred.probA
            currentProbB = nextPred.probB
            currentProbC = nextPred.probC

            refreshExpandedUi()
        } else {
            serviceScope.launch(Dispatchers.Main) {
                expandedView?.findViewById<TextView>(R.id.tv_current_cards)?.text =
                    "Cards: ${recognition.card1} | ${recognition.card2} | ${recognition.card3} (Result Not Verified)"
            }
        }
    }

    private fun refreshExpandedUi() {
        val view = expandedView ?: return
        serviceScope.launch(Dispatchers.IO) {
            val history = database.roundDao().getAllRoundsChronological()
            val totalCount = history.size
            val prediction = patternAnalyzer.calculateStatisticalPrediction(history)
            val latest = history.lastOrNull()

            serviceScope.launch(Dispatchers.Main) {
                view.findViewById<TextView>(R.id.tv_round_count)?.text = "Round #$totalCount"
                view.findViewById<TextView>(R.id.tv_current_cards)?.text =
                    latest?.let { "Last: ${it.card1} ${it.card2} ${it.card3} (Winner: ${it.actualWinner})" }
                        ?: "Cards: Observing screen..."

                view.findViewById<TextView>(R.id.tv_possible_a)?.text = "A: ${prediction.probA}%"
                view.findViewById<TextView>(R.id.tv_possible_b)?.text = "B: ${prediction.probB}%"
                view.findViewById<TextView>(R.id.tv_possible_c)?.text = "C: ${prediction.probC}%"

                view.findViewById<ProgressBar>(R.id.progress_a)?.progress = prediction.probA
                view.findViewById<ProgressBar>(R.id.progress_b)?.progress = prediction.probB
                view.findViewById<ProgressBar>(R.id.progress_c)?.progress = prediction.probC

                view.findViewById<TextView>(R.id.tv_most_likely)?.text = "Most Likely: Spot ${prediction.mostLikely}"
                view.findViewById<TextView>(R.id.tv_data_status)?.text = "Status: ${prediction.dataStatus}"

                val accStr = if (prediction.walkForwardAccuracy > 0f) {
                    "${String.format("%.1f", prediction.walkForwardAccuracy)}%"
                } else {
                    "Not Enough Data (<5)"
                }
                view.findViewById<TextView>(R.id.tv_verified_accuracy)?.text = "Verified Accuracy: $accStr"
                view.findViewById<TextView>(R.id.tv_brier_score)?.text =
                    "Brier Score: ${String.format("%.3f", prediction.brierScore)}"
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SCENE Bot Active")
            .setContentText("Observing Poppo Live Golden Flower screen in background...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
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
