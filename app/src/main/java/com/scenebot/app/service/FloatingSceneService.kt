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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
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

class FloatingSceneService : Service() {
    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var expandedView: View? = null
    private var screenCaptureManager: ScreenCaptureManager? = null
    private lateinit var visionRecognizer: CardVisionRecognizer
    private lateinit var database: AppDatabase
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var scanJob: Job? = null
    private var isScanning = false
    private var lastSavedFingerprint = ""

    companion object {
        const val CHANNEL_ID = "SceneBot_Channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val ACTION_STOP = "com.scenebot.app.ACTION_STOP"
    }

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

        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultData != null && screenCaptureManager == null) {
            screenCaptureManager = ScreenCaptureManager(this, resultCode, resultData)
            screenCaptureManager?.start()
        }

        setupFloatingView()
        startAutomaticScanLoop()
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingView() {
        if (floatingView != null) return
        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.layout_floating_scene_pill, null)

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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        floatingView?.setOnTouchListener { _, event ->
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
                    if (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10) {
                        isClick = false
                    }
                    params.x = initialX + deltaX
                    params.y = initialY + deltaY
                    if (floatingView?.isAttachedToWindow == true) {
                        windowManager.updateViewLayout(floatingView, params)
                    }
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

        windowManager.addView(floatingView, params)
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = anchorX.coerceAtLeast(20)
            y = anchorY.coerceAtLeast(100)
        }

        expandedView?.findViewById<View>(R.id.btn_minimize)?.setOnClickListener {
            toggleExpandedPanel(anchorX, anchorY)
        }
        expandedView?.findViewById<View>(R.id.btn_scan)?.setOnClickListener {
            performSingleScan()
        }
        expandedView?.findViewById<View>(R.id.btn_history)?.setOnClickListener {
            val historyIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("open_history", true)
            }
            startActivity(historyIntent)
        }

        floatingView?.visibility = View.GONE
        windowManager.addView(expandedView, expParams)
        refreshExpandedUi()
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

    private fun performSingleScan() {
        val bitmap = screenCaptureManager?.acquireLatestScreenshot() ?: return
        val recognition = visionRecognizer.processScreen(bitmap)
        bitmap.recycle()
        if (recognition == null) return

        val fingerprint = "fp-\${recognition.card1}\${recognition.card2}\${recognition.card3}"
        if (fingerprint != lastSavedFingerprint && recognition.confidence >= 0.70f) {
            lastSavedFingerprint = fingerprint
            val round = RoundEntity(
                card1 = recognition.card1,
                card2 = recognition.card2,
                card3 = recognition.card3,
                sequence = "\${recognition.card1}\${recognition.card2}\${recognition.card3}",
                source = "AUTO_CV",
                confidence = recognition.confidence,
                fingerprint = fingerprint,
                timestamp = System.currentTimeMillis()
            )
            serviceScope.launch(Dispatchers.IO) {
                database.roundDao().insertRound(round)
                refreshExpandedUi()
            }
        }
    }

    private fun refreshExpandedUi() {
        val view = expandedView ?: return
        serviceScope.launch(Dispatchers.IO) {
            val latest = database.roundDao().getLatestRound()
            val allRounds = database.roundDao().getRecentRounds(100)
            val totalCount = database.roundDao().getTotalRoundsCount()
            val analyzer = PatternAnalyzer()
            val currentSeq = latest?.sequence ?: "ACB"
            val prediction = analyzer.calculateStatisticalPrediction(allRounds, currentSeq)

            serviceScope.launch(Dispatchers.Main) {
                view.findViewById<TextView>(R.id.tv_current_cards)?.text =
                    latest?.let { "\${it.card1} \${it.card2} \${it.card3}" } ?: "A C B"

                if (prediction.hasEnoughData) {
                    view.findViewById<TextView>(R.id.tv_possible_a)?.text = "A -> \${prediction.probA}%"
                    view.findViewById<TextView>(R.id.tv_possible_b)?.text = "B -> \${prediction.probB}%"
                    view.findViewById<TextView>(R.id.tv_possible_c)?.text = "C -> \${prediction.probC}%"
                    view.findViewById<TextView>(R.id.tv_most_likely)?.text = "Most Likely: \${prediction.mostLikely}"
                    view.findViewById<TextView>(R.id.tv_confidence)?.text = "Confidence: \${prediction.confidence}%"
                } else {
                    view.findViewById<TextView>(R.id.tv_most_likely)?.text = "Need 5+ rounds"
                }
                view.findViewById<TextView>(R.id.tv_round_count)?.text = "Rounds: $totalCount"
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SCENE Bot Active")
            .setContentText("Observing Poppo Live game scene and analyzing cards...")
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
                description = "Shows persistent status while SCENE Bot is capturing cards"
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
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
