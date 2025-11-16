package com.example.swingfit

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CaptureActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnRecord: Button
    private lateinit var btnIsoToggle: Button
    private lateinit var btnZoomIn: Button
    private lateinit var btnZoomOut: Button
    private lateinit var tvShutter: TextView
    private lateinit var tvIso: TextView
    private lateinit var spShutter: Spinner
    private lateinit var spIso: Spinner

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var analysisExecutor: ExecutorService

    private val shutterOptions = listOf(  // 표기 -> ns
        "1/2000" to 500_000L,
        "1/1600" to 625_000L,
        "1/1000" to 1_000_000L,
        "1/800"  to 1_250_000L,
        "1/500"  to 2_000_000L,
        "1/250"  to 4_000_000L,
        "1/125"  to 8_000_000L,
        "1/60"   to 16_666_667L
    )
    private val isoOptions = listOf(100, 200, 400, 800, 1600, 3200)

    private val permissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.READ_MEDIA_VIDEO)
    }.toTypedArray()

    private val reqPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) startCamera() else finish()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingStart: Runnable? = null

    // ===== 카운트다운 =====
    private var tvCountdown: TextView? = null
    private var countdownRunnable: Runnable? = null
    private var countdownRemaining = 0

    // ===== 자동 정지 =====
    private var autoStopRunnable: Runnable? = null

    // ===== 알림음 =====
    private var ringtone: Ringtone? = null

    // ====== 줌 관련 ======
    private var scaleDetector: ScaleGestureDetector? = null
    private var currentLinearZoom: Float = 0f
    private var desiredLinearZoom: Float = 0f

    // ====== ISO AUTO ======
    private var autoIsoEnabled = true
    private var lastAppliedIso = 200
    private var lastAppliedExposureNs: Long = 1_000_000L
    private var lastIsoUpdateTimeNs = 0L
    private val isoUpdateIntervalNs = 120_000_000L
    private val ISO_MIN = 50
    private val ISO_MAX = 1600
    private val autoIsoTargetLuma = 0.18f

    private var boundFpsRange: Range<Int> = Range(60, 60)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)

        previewView = findViewById(R.id.previewView)
        btnRecord = findViewById(R.id.btnRecord)
        btnIsoToggle = findViewById(R.id.btnIsoToggle)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        tvShutter = findViewById(R.id.tvShutter)
        tvIso = findViewById(R.id.tvIso)
        spShutter = Spinner(this)
        spIso = Spinner(this)

        cameraExecutor = Executors.newSingleThreadExecutor()
        analysisExecutor = Executors.newSingleThreadExecutor()

        // 초기 텍스트
        tvShutter.text = "1/1000"
        tvIso.text = "ISO 200"
        updateIsoToggleButtonUi()

        // ISO 토글
        btnIsoToggle.setOnClickListener {
            autoIsoEnabled = !autoIsoEnabled
            if (autoIsoEnabled) {
                Toast.makeText(this, "ISO AUTO 모드", Toast.LENGTH_SHORT).show()
                bindUseCases()
            } else {
                lastAppliedIso = 200
                applyIsoRuntime(lastAppliedIso)
                Toast.makeText(this, "ISO MANUAL 모드", Toast.LENGTH_SHORT).show()
                updateIsoToggleButtonUi()
            }
        }

        // 줌 인/아웃
        btnZoomIn.setOnClickListener { stepLinearZoom(+0.1f) }
        btnZoomOut.setOnClickListener { stepLinearZoom(-0.1f) }

        // 핀치 줌
        scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val delta = (detector.scaleFactor - 1f) * 0.5f
                    stepLinearZoom(delta)
                    return true
                }
            }
        )
        previewView.setOnTouchListener { _, event -> scaleDetector?.onTouchEvent(event); true }

        // 녹화 버튼
        btnRecord.setOnClickListener {
            when {
                recording != null -> stopRecording()
                countdownRunnable != null || pendingStart != null -> cancelPendingStart()
                else -> scheduleStartAfterDelay()
            }
        }

        ensureCountdownView()
        reqPerm.launch(permissions)

        // 셔터 터치로 팝업
        tvShutter.setOnClickListener {
            if (autoIsoEnabled) {
                Toast.makeText(this, "ISO MANUAL 모드에서 조정 가능합니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showShutterPopup(it)
        }


        tvIso.setOnClickListener {
            if (autoIsoEnabled) {
                Toast.makeText(this, "ISO MANUAL 모드에서 조정 가능합니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showIsoPopup(it)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingStart()
        cancelAutoStop()
        stopRingtone()
        cameraExecutor.shutdown()
        analysisExecutor.shutdown()
    }

    // ===== Camera =====
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            bindUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .build()

        val videoBuilder = VideoCapture.Builder(recorder)
        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        // 초기 노출값
        lastAppliedExposureNs = 1_000_000L
        lastAppliedIso = 200

        // Camera2 설정
        val interop = Camera2Interop.Extender(videoBuilder)
        interop.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
        interop.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, lastAppliedExposureNs)
        if (!autoIsoEnabled) {
            interop.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, lastAppliedIso)
        }
        interop.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, boundFpsRange)

        videoCapture = videoBuilder
            .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
            .build()

        val analysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(analysisExecutor) { image ->
                    try { onAnalyzeFrameForAutoIso(image) } finally { image.close() }
                }
            }

        camera = if (autoIsoEnabled) {
            provider.bindToLifecycle(this, selector, preview, videoCapture, analysis)
        } else {
            provider.bindToLifecycle(this, selector, preview, videoCapture)
        }

        applyExposureTimeRuntime(lastAppliedExposureNs)
        if (!autoIsoEnabled) applyIsoRuntime(lastAppliedIso)

        // 줌 상태 반영
        camera?.cameraInfo?.zoomState?.observe(this) { zs ->
            currentLinearZoom = zs.linearZoom
            updateZoomButtons(zs.linearZoom)
        }

        updateIsoToggleButtonUi()
    }

    // ===== ISO AUTO =====
    private fun onAnalyzeFrameForAutoIso(image: ImageProxy) {
        if (!autoIsoEnabled || camera == null) return
        val now = System.nanoTime()
        if (now - lastIsoUpdateTimeNs < isoUpdateIntervalNs) return
        val yPlane = image.planes.firstOrNull() ?: return
        val avg = computeAvgLuma(yPlane.buffer, yPlane.rowStride, image.width, image.height)
        if (avg <= 0f) return

        val ratio = (autoIsoTargetLuma / avg).coerceIn(0.5f, 2.0f)
        var newIso = (lastAppliedIso * ratio).toInt()
        if (abs(newIso - lastAppliedIso) < max(8, (lastAppliedIso * 0.05f).toInt())) return
        newIso = newIso.coerceIn(ISO_MIN, ISO_MAX)
        lastAppliedIso = newIso
        lastIsoUpdateTimeNs = now
        applyIsoRuntime(newIso)
        runOnUiThread { tvIso.text = "ISO $newIso" }
    }

    private fun computeAvgLuma(buf: ByteBuffer, rowStride: Int, w: Int, h: Int): Float {
        val stepX = 8; val stepY = 8
        var sum = 0L; var count = 0L
        val arr = if (buf.hasArray()) buf.array() else ByteArray(buf.remaining()).also {
            buf.mark(); buf.get(it); buf.reset()
        }
        val base = if (buf.hasArray()) buf.arrayOffset() + buf.position() else 0
        for (y in 0 until h step stepY) {
            val row = base + y * rowStride
            var x = 0
            while (x < w) {
                val idx = row + x
                if (idx in arr.indices) { sum += arr[idx].toInt() and 0xFF; count++ }
                x += stepX
            }
        }
        if (count == 0L) return 0f
        return (sum.toFloat() / count) / 255f
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyIsoRuntime(iso: Int) {
        camera?.cameraControl?.let {
            val ctrl = Camera2CameraControl.from(it)
            val opts = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                .build()
            ctrl.setCaptureRequestOptions(opts)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyExposureTimeRuntime(expNs: Long) {
        camera?.cameraControl?.let {
            val ctrl = Camera2CameraControl.from(it)
            val opts = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                .build()
            ctrl.setCaptureRequestOptions(opts)
        }
    }

    // ====== 녹화 관련 ======
    private fun scheduleStartAfterDelay() {
        if (camera == null) bindUseCases()
        btnRecord.text = ""
        startCountdown(7) {
            pendingStart = null
            if (recording == null) startRecordingNow()
        }
        pendingStart = Runnable { }
    }

    private fun startRecordingNow() {
        val vc = videoCapture ?: return
        val name = "swingfit_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".mp4"
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/swingfit")
        }
        val output = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(cv).build()

        recording = vc.output.prepareRecording(this, output)
            .apply { withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        btnRecord.text = ""
                        playNotification()
                        scheduleAutoStop()
                    }
                    is VideoRecordEvent.Finalize -> {
                        cancelAutoStop()
                        val uri = event.outputResults.outputUri
                        setResult(RESULT_OK, Intent().setData(uri))
                        finish()
                    }
                }
            }
    }

    private fun stopRecording() {
        recording?.let {
            playNotification()
            cancelAutoStop()
            it.stop(); it.close()
            recording = null
            btnRecord.text = ""
        }
    }

    // ===== 유틸 =====
    private fun stepLinearZoom(delta: Float) {
        val zs = camera?.cameraInfo?.zoomState?.value ?: return
        val next = (zs.linearZoom + delta).coerceIn(0f, 1f)
        camera?.cameraControl?.setLinearZoom(next)
    }

    private fun updateZoomButtons(linearZoom: Float) {
        btnZoomOut.isEnabled = linearZoom > 0f + 1e-3f
        btnZoomIn.isEnabled = linearZoom < 1f - 1e-3f
    }

    private fun updateIsoToggleButtonUi() {
        btnIsoToggle.text = if (autoIsoEnabled) "ISO: AUTO" else "ISO: MANUAL"
    }

    // ===== 카운트다운 =====
    private fun ensureCountdownView() {
        if (tvCountdown != null) return
        val root = findViewById<ViewGroup>(android.R.id.content)
        val tv = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER
            textSize = 96f
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(12f, 0f, 0f, 0x99000000.toInt())
            visibility = View.GONE
        }
        root.addView(tv)
        tvCountdown = tv
    }

    private fun startCountdown(sec: Int, onFinished: () -> Unit) {
        stopCountdown()
        ensureCountdownView()
        countdownRemaining = sec
        tvCountdown?.visibility = View.VISIBLE
        tickCountdown(onFinished)
    }

    private fun tickCountdown(onFinished: () -> Unit) {
        val v = tvCountdown ?: return
        if (countdownRemaining <= 0) {
            stopCountdown()
            onFinished()
            return
        }
        v.text = countdownRemaining.toString()
        v.scaleX = 0.6f; v.scaleY = 0.6f; v.alpha = 1f
        v.animate().scaleX(1.2f).scaleY(1.2f).alpha(0.2f)
            .setDuration(900)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                countdownRemaining -= 1
                countdownRunnable = Runnable { tickCountdown(onFinished) }
                mainHandler.postDelayed(countdownRunnable!!, 100L)
            }.start()
    }

    private fun stopCountdown() {
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        countdownRunnable = null
        tvCountdown?.visibility = View.GONE
    }

    private fun cancelPendingStart() {
        stopCountdown()
        pendingStart?.let { mainHandler.removeCallbacks(it) }
        pendingStart = null
        btnRecord.text = ""
    }

    private fun scheduleAutoStop() {
        cancelAutoStop()
        autoStopRunnable = Runnable { if (recording != null) stopRecording() }
        mainHandler.postDelayed(autoStopRunnable!!, 8_000L)
    }

    private fun cancelAutoStop() {
        autoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        autoStopRunnable = null
    }

    private fun playNotification() {
        if (ringtone == null) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)
        }
        ringtone?.play()
    }

    private fun stopRingtone() {
        try { ringtone?.stop() } catch (_: Exception) {}
    }

    private fun showShutterPopup(anchor: View) {
        val popup = PopupMenu(this, anchor)
        shutterOptions.forEachIndexed { idx, (label, _) -> popup.menu.add(0, idx, idx, label) }
        popup.setOnMenuItemClickListener { item ->
            val (label, ns) = shutterOptions[item.itemId]
            lastAppliedExposureNs = ns
            applyExposureTimeRuntime(ns)
            tvShutter.text = label
            Toast.makeText(this, "셔터: $label 적용", Toast.LENGTH_SHORT).show()
            true
        }
        popup.show()
    }

    private fun showIsoPopup(anchor: View) {
        val popup = PopupMenu(this, anchor)
        isoOptions.forEachIndexed { idx, iso -> popup.menu.add(0, idx, idx, "ISO $iso") }
        popup.setOnMenuItemClickListener { item ->
            val iso = isoOptions[item.itemId]
            lastAppliedIso = iso
            applyIsoRuntime(iso)
            tvIso.text = "ISO $iso"
            Toast.makeText(this, "ISO: $iso 적용", Toast.LENGTH_SHORT).show()
            true
        }
        popup.show()
    }
}