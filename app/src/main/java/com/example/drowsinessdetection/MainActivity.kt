package com.example.drowsinessdetection

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private const val EAR_THRESHOLD = 0.20f
        private const val YAWN_THRESHOLD = 0.50f
        private const val WARNING_DELAY_MS = 2_000L
        private const val CRITICAL_DELAY_MS = 5_000L
        private const val SOS_COUNTDOWN_MS = 10_000L
        private const val FOLLOW_UP_DELAY_MS = 45_000L
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.VIBRATE
        )
    }

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var alertStatusText: TextView
    private lateinit var fatigueLevelText: TextView
    private lateinit var sessionBadge: TextView
    private lateinit var sessionDurationText: TextView
    private lateinit var sessionMetricsText: TextView
    private lateinit var sessionButton: Button
    private lateinit var imAwakeButton: Button
    private lateinit var contactsButton: Button
    private lateinit var reportButton: Button
    private lateinit var driverNameButton: Button

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    private var faceLandmarker: FaceLandmarker? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameCaptureRunnable: Runnable? = null
    private var sessionTimerRunnable: Runnable? = null
    private var sosCountdownRunnable: Runnable? = null
    private var followUpRunnable: Runnable? = null

    private var sessionActive = false
    private var sessionStartedAt = 0L
    private var sessionDrowsinessEvents = 0
    private var sessionYawns = 0
    private var sessionCriticalAlerts = 0
    private var sessionEyesClosedMillis = 0L

    private var drowsyEventActive = false
    private var drowsyStartedAt = 0L
    private var yawnInProgress = false
    private var warningAnnounced = false
    private var criticalRecorded = false
    private var sosSentForCurrentEvent = false
    private var isSendingMessages = false

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var smsStatusReceiverRegistered = false

    private val smsStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(SmsSentReceiver.EXTRA_SUCCESS, false)
            val message = intent.getStringExtra(SmsSentReceiver.EXTRA_STATUS_MESSAGE) ?: "SMS status unavailable"
            updateAlertStatus(message, if (success) R.color.success_green else R.color.error_red)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        wireActions()

        cameraExecutor = Executors.newSingleThreadExecutor()
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        Utils.initContext(applicationContext)
        setupFaceLandmarker()
        setupTextToSpeech()
        registerSmsStatusReceiver()
        refreshDriverName()
        renderPausedState()

        if (hasCameraPermission()) startCamera() else requestMissingPermissions()
    }

    private fun bindViews() {
        previewView = findViewById(R.id.preview_view)
        statusText = findViewById(R.id.status_text)
        alertStatusText = findViewById(R.id.alert_status_text)
        fatigueLevelText = findViewById(R.id.fatigue_level_text)
        sessionBadge = findViewById(R.id.session_badge)
        sessionDurationText = findViewById(R.id.session_duration_text)
        sessionMetricsText = findViewById(R.id.session_metrics_text)
        sessionButton = findViewById(R.id.session_button)
        imAwakeButton = findViewById(R.id.im_awake_button)
        contactsButton = findViewById(R.id.manage_contacts_button)
        reportButton = findViewById(R.id.safety_report_button)
        driverNameButton = findViewById(R.id.driver_name_button)
    }

    private fun wireActions() {
        sessionButton.setOnClickListener {
            if (sessionActive) endDrivingSession() else startDrivingSession()
        }
        imAwakeButton.setOnClickListener { acknowledgeAwake() }
        contactsButton.setOnClickListener { startActivity(Intent(this, ContactsActivity::class.java)) }
        reportButton.setOnClickListener { startActivity(Intent(this, SafetyReportActivity::class.java)) }
        driverNameButton.setOnClickListener { showDriverNameDialog() }
    }

    private fun setupFaceLandmarker() {
        try {
            faceLandmarker = FaceLandmarker.createFromOptions(
                this,
                FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setNumFaces(1)
                    .build()
            )
        } catch (exception: Exception) {
            Log.e(TAG, "Face Landmarker setup failed", exception)
            Toast.makeText(this, "Face detection could not start", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) textToSpeech?.language = Locale.getDefault()
        }
    }

    private fun registerSmsStatusReceiver() {
        ContextCompat.registerReceiver(
            this,
            smsStatusReceiver,
            IntentFilter(SmsSentReceiver.ACTION_SMS_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        smsStatusReceiverRegistered = true
    }

    private fun startCamera() {
        if (!hasCameraPermission() || ::cameraProviderFuture.isInitialized) return
        updateStatus("Starting selfie camera…")
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                val selector = CameraSelector.DEFAULT_FRONT_CAMERA
                if (!provider.hasCamera(selector)) {
                    updateStatus("Selfie camera unavailable")
                    updateAlertStatus("No front camera is available to this app.", R.color.error_red)
                    return@addListener
                }
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview)
                startFrameCapture()
                if (!sessionActive) updateStatus("Selfie camera ready. Press Start Driving.")
            } catch (exception: Exception) {
                Log.e(TAG, "Camera could not start", exception)
                updateStatus("Selfie camera could not start")
                updateAlertStatus(exception.localizedMessage ?: "Close other camera apps and try again.", R.color.error_red)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startFrameCapture() {
        if (frameCaptureRunnable != null) return
        frameCaptureRunnable = object : Runnable {
            override fun run() {
                previewView.bitmap?.let(::processFrame)
                mainHandler.postDelayed(this, 200)
            }
        }
        mainHandler.post(frameCaptureRunnable!!)
    }

    private fun processFrame(bitmap: Bitmap) {
        if (!sessionActive) return
        val landmarker = faceLandmarker ?: return
        try {
            val result = landmarker.detect(BitmapImageBuilder(bitmap).build() as MPImage)
            processDetectionResult(result)
        } catch (exception: Exception) {
            Log.e(TAG, "Frame processing failed", exception)
        }
    }

    private fun processDetectionResult(result: FaceLandmarkerResult) {
        if (result.faceLandmarks().isEmpty()) {
            updateStatus("Face not visible — align with the selfie camera")
            finishDrowsyEvent("Face tracking lost")
            return
        }

        val landmarks = result.faceLandmarks()[0]
        val leftEar = calculateEAR(landmarks[33], landmarks[159], landmarks[160], landmarks[133], landmarks[144], landmarks[145])
        val rightEar = calculateEAR(landmarks[362], landmarks[386], landmarks[387], landmarks[263], landmarks[373], landmarks[374])
        val ear = (leftEar + rightEar) / 2f
        val mar = calculateMAR(landmarks[13], landmarks[14], landmarks[78], landmarks[308], landmarks[61], landmarks[291])

        updateYawnState(mar > YAWN_THRESHOLD)
        if (ear < EAR_THRESHOLD) {
            handleEyesClosed()
        } else {
            finishDrowsyEvent("Driver awake — monitoring continues")
            if (mar <= YAWN_THRESHOLD) {
                setFatigueLevel("● ALERT", R.color.success_green)
                updateStatus("Eyes open · staying alert")
            }
        }
    }

    private fun updateYawnState(isYawning: Boolean) {
        if (isYawning && !yawnInProgress) {
            yawnInProgress = true
            sessionYawns++
            updateSessionMetrics()
            if (!drowsyEventActive) {
                setFatigueLevel("● SLIGHTLY FATIGUED", R.color.warning_yellow)
                updateStatus("Yawn detected · take a break if tired")
            }
        } else if (!isYawning) {
            yawnInProgress = false
        }
    }

    private fun handleEyesClosed() {
        if (!drowsyEventActive) {
            drowsyEventActive = true
            drowsyStartedAt = System.currentTimeMillis()
            warningAnnounced = false
            criticalRecorded = false
            sosSentForCurrentEvent = false
            sessionDrowsinessEvents++
            updateSessionMetrics()
        }

        val duration = System.currentTimeMillis() - drowsyStartedAt
        when {
            duration < WARNING_DELAY_MS -> {
                setFatigueLevel("● FATIGUE WATCH", R.color.warning_yellow)
                updateStatus("Eyes closed · monitoring")
            }
            duration < CRITICAL_DELAY_MS -> {
                setFatigueLevel("● FATIGUED", R.color.warning_yellow)
                updateStatus("WAKE UP! DROWSINESS DETECTED")
                playAlarm()
                vibratePhone()
                if (!warningAnnounced) {
                    warningAnnounced = true
                    speak("Warning. You appear drowsy. Please take a break.")
                }
            }
            else -> {
                setFatigueLevel("● CRITICAL", R.color.error_red)
                playAlarm()
                vibratePhone()
                if (!criticalRecorded) {
                    criticalRecorded = true
                    sessionCriticalAlerts++
                    updateSessionMetrics()
                }
                startSosCountdown()
            }
        }
    }

    private fun startSosCountdown() {
        if (sosCountdownRunnable != null || sosSentForCurrentEvent) return
        imAwakeButton.visibility = View.VISIBLE
        imAwakeButton.text = "I'M AWAKE — CANCEL SOS"
        speak("Critical warning. Emergency alert will be sent in ten seconds.")
        val countdownStartedAt = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                if (!sessionActive || !drowsyEventActive || sosSentForCurrentEvent) {
                    sosCountdownRunnable = null
                    return
                }
                val secondsLeft = ((SOS_COUNTDOWN_MS - (System.currentTimeMillis() - countdownStartedAt)).coerceAtLeast(0) + 999) / 1000
                updateStatus("CRITICAL — SOS will be sent in $secondsLeft seconds")
                updateAlertStatus("Tap I’M AWAKE to cancel the emergency alert.", R.color.warning_yellow)
                if (secondsLeft > 0) {
                    mainHandler.postDelayed(this, 250)
                } else {
                    sosCountdownRunnable = null
                    sosSentForCurrentEvent = true
                    imAwakeButton.text = "I'M AWAKE — STOP ESCALATION"
                    speak("No response. Sending emergency alert now.")
                    sendSosMessages(false)
                    scheduleFollowUp()
                }
            }
        }
        sosCountdownRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun scheduleFollowUp() {
        followUpRunnable?.let(mainHandler::removeCallbacks)
        followUpRunnable = Runnable {
            if (sessionActive && drowsyEventActive) {
                updateAlertStatus("No response after initial SOS — sending urgent follow-up.", R.color.error_red)
                speak("Driver has not responded. Sending a follow up emergency alert.")
                sendSosMessages(true)
            }
            followUpRunnable = null
        }.also { mainHandler.postDelayed(it, FOLLOW_UP_DELAY_MS) }
    }

    private fun acknowledgeAwake() {
        val hadSentSos = sosSentForCurrentEvent
        finishDrowsyEvent(if (hadSentSos) "Driver response logged — follow-up stopped" else "SOS cancelled — driver is awake")
        setFatigueLevel("● ALERT", R.color.success_green)
        updateAlertStatus(
            if (hadSentSos) "Initial SMS cannot be recalled; further escalation was stopped."
            else "Emergency alert cancelled by driver response.",
            R.color.success_green
        )
        speak("Driver response received. Emergency escalation cancelled.")
    }

    private fun finishDrowsyEvent(recoveryMessage: String) {
        if (drowsyEventActive) {
            sessionEyesClosedMillis += (System.currentTimeMillis() - drowsyStartedAt).coerceAtLeast(0)
            drowsyEventActive = false
        }
        drowsyStartedAt = 0L
        warningAnnounced = false
        criticalRecorded = false
        sosSentForCurrentEvent = false
        sosCountdownRunnable?.let(mainHandler::removeCallbacks)
        sosCountdownRunnable = null
        followUpRunnable?.let(mainHandler::removeCallbacks)
        followUpRunnable = null
        imAwakeButton.visibility = View.GONE
        stopAlarm()
        stopVibration()
        if (sessionActive) updateStatus(recoveryMessage)
    }

    private fun sendSosMessages(isFollowUp: Boolean) {
        val contacts = Utils.getContacts(this)
        if (contacts.isEmpty()) {
            updateAlertStatus("No emergency contacts saved. SOS was not sent.", R.color.error_red)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            updateAlertStatus("SMS permission is required to send the SOS.", R.color.error_red)
            return
        }
        Utils.smsReadinessProblem(this)?.let { problem ->
            updateAlertStatus(problem, R.color.error_red)
            return
        }
        if (isSendingMessages) return

        isSendingMessages = true
        updateAlertStatus("Getting current location for the emergency alert…", R.color.warning_yellow)
        Utils.getLocationInfo { locationInfo ->
            val eventDurationSeconds = ((System.currentTimeMillis() - drowsyStartedAt).coerceAtLeast(0) / 1000)
            val message = if (isFollowUp) {
                "URGENT: ${Utils.getDriverName(this)} has not responded to the DrowzeFree drowsiness alert. Time: ${Utils.formatTimestamp(System.currentTimeMillis())}. $locationInfo"
            } else {
                "EMERGENCY ALERT: ${Utils.getDriverName(this)} may be drowsy for ${eventDurationSeconds} seconds. Time: ${Utils.formatTimestamp(System.currentTimeMillis())}. $locationInfo"
            }
            Thread {
                var queued = 0
                contacts.forEachIndexed { index, contact ->
                    try {
                        val requestCode = ((System.currentTimeMillis() % 1_000_000L) * 10L + index * 10L).toInt()
                        Utils.sendSMS(applicationContext, contact.phoneNumber, message, requestCode)
                        queued++
                    } catch (exception: Exception) {
                        Log.e(TAG, "Could not queue SOS for ${contact.phoneNumber}", exception)
                    }
                    try {
                        Thread.sleep(350)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                runOnUiThread {
                    isSendingMessages = false
                    if (queued == contacts.size) {
                        updateAlertStatus("Emergency SMS queued for ${contacts.size} contact(s); waiting for carrier result…", R.color.warning_yellow)
                    } else {
                        updateAlertStatus("SMS queued for $queued of ${contacts.size} contact(s). Check SMS SIM and network.", R.color.error_red)
                    }
                }
            }.start()
        }
    }

    private fun startDrivingSession() {
        if (!hasCameraPermission()) {
            requestMissingPermissions()
            return
        }
        sessionActive = true
        sessionStartedAt = System.currentTimeMillis()
        sessionDrowsinessEvents = 0
        sessionYawns = 0
        sessionCriticalAlerts = 0
        sessionEyesClosedMillis = 0L
        sessionButton.text = "End session"
        sessionBadge.text = "● DRIVING"
        sessionBadge.setTextColor(ContextCompat.getColor(this, R.color.success_green))
        setFatigueLevel("● ALERT", R.color.success_green)
        updateStatus("Driving session started · stay safe")
        updateAlertStatus("Warnings begin after 2 seconds of eye closure.", R.color.ink_muted)
        updateSessionMetrics()
        startSessionTimer()
        speak("Driving session started. I will monitor your alertness.")
    }

    private fun endDrivingSession() {
        if (!sessionActive) return
        finishDrowsyEvent("Session ended")
        val endedAt = System.currentTimeMillis()
        SafetyStats.saveSession(
            this,
            SafetySession(
                startedAt = sessionStartedAt,
                endedAt = endedAt,
                drowsinessEvents = sessionDrowsinessEvents,
                yawns = sessionYawns,
                criticalAlerts = sessionCriticalAlerts,
                eyesClosedMillis = sessionEyesClosedMillis
            )
        )
        sessionActive = false
        sessionTimerRunnable?.let(mainHandler::removeCallbacks)
        sessionTimerRunnable = null
        sessionButton.text = "Start driving"
        sessionBadge.text = "● COMPLETE"
        sessionBadge.setTextColor(ContextCompat.getColor(this, R.color.success_green))
        setFatigueLevel("● SESSION COMPLETE", R.color.success_green)
        updateStatus("Safety summary saved on this device")
        updateAlertStatus("Review your driving report before the next trip.", R.color.ink_muted)
        startActivity(Intent(this, SafetyReportActivity::class.java))
    }

    private fun startSessionTimer() {
        sessionTimerRunnable?.let(mainHandler::removeCallbacks)
        sessionTimerRunnable = object : Runnable {
            override fun run() {
                if (!sessionActive) return
                sessionDurationText.text = formatDuration(System.currentTimeMillis() - sessionStartedAt)
                mainHandler.postDelayed(this, 1000)
            }
        }
        mainHandler.post(sessionTimerRunnable!!)
    }

    private fun updateSessionMetrics() {
        sessionMetricsText.text = "$sessionDrowsinessEvents / $sessionYawns"
    }

    private fun showDriverNameDialog() {
        val input = EditText(this).apply {
            setText(Utils.getDriverName(this@MainActivity))
            hint = "Driver name"
            setSingleLine(true)
            setPadding(48, 8, 48, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("Driver profile")
            .setMessage("The name is included in emergency alerts.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                Utils.saveDriverName(this, input.text.toString())
                refreshDriverName()
            }
            .show()
    }

    private fun refreshDriverName() {
        driverNameButton.text = Utils.getDriverName(this)
    }

    private fun renderPausedState() {
        sessionDurationText.text = "00:00:00"
        sessionMetricsText.text = "0 / 0"
        sessionBadge.text = "● READY"
        sessionBadge.setTextColor(ContextCompat.getColor(this, R.color.ink_muted))
        setFatigueLevel("● SESSION PAUSED", R.color.ink_muted)
        updateStatus("Press Start Driving when you are ready.")
        updateAlertStatus("Voice, safety scoring, and emergency response are ready.", R.color.ink_muted)
    }

    private fun setFatigueLevel(label: String, colorRes: Int) {
        runOnUiThread {
            fatigueLevelText.text = label
            fatigueLevelText.setTextColor(ContextCompat.getColor(this, colorRes))
        }
    }

    private fun updateStatus(message: String) {
        runOnUiThread { statusText.text = message }
    }

    private fun updateAlertStatus(message: String, colorRes: Int) {
        runOnUiThread {
            alertStatusText.text = message
            alertStatusText.setTextColor(ContextCompat.getColor(this, colorRes))
        }
    }

    private fun speak(message: String) {
        if (ttsReady) textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "drowzefree-alert")
    }

    private fun playAlarm() {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer.create(this, R.raw.alarm_sound)?.apply { isLooping = true }
        }
        if (mediaPlayer?.isPlaying != true) mediaPlayer?.start()
    }

    private fun stopAlarm() {
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
    }

    private fun vibratePhone() {
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 250), 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(1000)
        }
    }

    private fun stopVibration() = vibrator?.cancel()

    private fun calculateEAR(
        p1: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p2: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p3: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p4: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p5: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p6: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Float = (distance(p2, p6) + distance(p3, p5)) / (2f * distance(p1, p4))

    private fun calculateMAR(
        p1: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p2: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p3: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p4: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p5: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p6: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Float = (distance(p1, p2) + distance(p3, p4)) / (2f * distance(p5, p6))

    private fun distance(
        p1: com.google.mediapipe.tasks.components.containers.NormalizedLandmark,
        p2: com.google.mediapipe.tasks.components.containers.NormalizedLandmark
    ): Float = sqrt((p1.x() - p2.x()).pow(2) + (p1.y() - p2.y()).pow(2))

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestMissingPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMISSIONS)
    }

    private fun formatDuration(durationMs: Long): String {
        val seconds = durationMs.coerceAtLeast(0) / 1000
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (hasCameraPermission()) startCamera()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                updateAlertStatus("Camera works, but allow SMS permission to send emergency alerts.", R.color.warning_yellow)
            }
        }
    }

    override fun onDestroy() {
        frameCaptureRunnable?.let(mainHandler::removeCallbacks)
        sessionTimerRunnable?.let(mainHandler::removeCallbacks)
        sosCountdownRunnable?.let(mainHandler::removeCallbacks)
        followUpRunnable?.let(mainHandler::removeCallbacks)
        stopAlarm()
        stopVibration()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        faceLandmarker?.close()
        cameraExecutor.shutdown()
        if (smsStatusReceiverRegistered) unregisterReceiver(smsStatusReceiver)
        super.onDestroy()
    }
}
