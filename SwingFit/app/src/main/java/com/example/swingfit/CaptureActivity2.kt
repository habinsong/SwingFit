package com.example.swingfit

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class CaptureActivity2 : AppCompatActivity() {

    // ===== XML views =====
    private lateinit var btnRecord: Button
    private lateinit var btnIsoToggle: Button
    private lateinit var btnZoomIn: Button
    private lateinit var btnZoomOut: Button
    private lateinit var tvShutter: TextView
    private lateinit var tvIso: TextView
    private var textureView: TextureView? = null

    // Countdown overlay
    private var tvCountdown: TextView? = null
    private var countdownRunnable: Runnable? = null
    private var countdownRemaining = 0
    private var pendingStart: Runnable? = null

    // System notification sound
    private var ringtone: Ringtone? = null

    // ===== Camera2 =====
    private lateinit var cameraManager: CameraManager
    private var cameraId: String = ""
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null
    private var previewSurface: Surface? = null

    // ===== Encoder / Muxer =====
    private var mediaCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrack = -1
    private var muxerStarted = false
    private var outputUri: Uri? = null
    private var recorderSurface: Surface? = null
    private var orientationHint: Int = 0

    // ===== Threads / Executors =====
    private val camThread = HandlerThread("cam2").apply { start() }
    private val camHandler = Handler(camThread.looper)
    private lateinit var cameraExecutor: Executor
    private val mainHandler = Handler(Looper.getMainLooper())

    // ===== State / Options =====
    private var supports120 = false
    private var hsSize: Size? = null
    private var hsRange: Range<Int>? = null

    private var recording = false
    private var autoIso = true
    private var currentIso = 200
    private var currentExposureNs: Long = 1_000_000L // ~1/1000
    private var linearZoom = 0f
    private var previewSize: Size = Size(1280, 720)
    private var previewFps: Range<Int> = Range(60, 60)

    // Manual presets
    private val shutterList = longArrayOf(
        500_000L,   // 1/2000
        1_000_000L, // 1/1000
        2_000_000L, // 1/500
        4_000_000L  // 1/250
    )
    private var shutterIndex = 1
    private val isoList = intArrayOf(100, 200, 400, 800, 1600)
    private var isoIndex = 1

    // Auto stop (8s)
    private var autoStop: Runnable? = null

    // Rebuild preview after session close (idle wait)
    @Volatile private var pendingPreviewRebuild = false

    // ===== Permissions =====
    private val reqPerm = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { g ->
        if (g.values.all { it }) startCameraFlow() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture2)

        textureView  = findViewById(R.id.textureView)
        btnRecord    = findViewById(R.id.btnRecord)
        btnIsoToggle = findViewById(R.id.btnIsoToggle)
        btnZoomIn    = findViewById(R.id.btnZoomIn)
        btnZoomOut   = findViewById(R.id.btnZoomOut)
        tvShutter    = findViewById(R.id.tvShutter)
        tvIso        = findViewById(R.id.tvIso)

        tvShutter.text = shutterText(currentExposureNs)
        updateIsoUi()

        cameraExecutor = Executors.newSingleThreadExecutor()

        textureView?.apply {
            surfaceTextureListener = surfaceListener
            isOpaque = true
        }

        // ISO AUTO/MANUAL toggle
        btnIsoToggle.setOnClickListener {
            autoIso = !autoIso
            updateIsoUi()
            camHandler.post { rebuildPreviewRepeating() }
        }

        // Manual shutter/ISO adjust (only in MANUAL mode)
        tvShutter.setOnClickListener {
            if (!autoIso) {
                shutterIndex = (shutterIndex + 1) % shutterList.size
                currentExposureNs = shutterList[shutterIndex]
                tvShutter.text = shutterText(currentExposureNs)
                camHandler.post { rebuildPreviewRepeating() }
            } else {
                toast("ISO AUTO 모드에서는 셔터 조정 불가")
            }
        }
        tvIso.setOnClickListener {
            if (!autoIso) {
                isoIndex = (isoIndex + 1) % isoList.size
                currentIso = isoList[isoIndex]
                tvIso.text = "ISO $currentIso"
                camHandler.post { rebuildPreviewRepeating() }
            } else {
                toast("ISO AUTO 모드에서는 ISO 조정 불가")
            }
        }

        // Zoom buttons (preserve linearZoom value; don't reset on recording)
        btnZoomIn.setOnClickListener { setLinearZoom((linearZoom + 0.1f).coerceIn(0f, 1f)) }
        btnZoomOut.setOnClickListener { setLinearZoom((linearZoom - 0.1f).coerceIn(0f, 1f)) }

        // Record with countdown overlay
        btnRecord.setOnClickListener {
            when {
                recording -> stopRecording()
                countdownRunnable != null || pendingStart != null -> cancelPendingStart()
                else -> scheduleStartAfterDelay()
            }
        }

        ensureCountdownView()

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        reqPerm.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingStart()
        stopRecording()
        stopRingtone()
        camHandler.post {
            closeSession()
            closeCamera()
        }
        camThread.quitSafely()
    }

    // ================= Camera init =================

    private fun startCameraFlow() {
        cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cameraManager.cameraIdList.first()

        characteristics = cameraManager.getCameraCharacteristics(cameraId)

        // --- 120fps 지원 확인 (HS API) ---
        val caps = characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val hasHS = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        supports120 = false
        hsSize = null
        hsRange = null

        if (hasHS && map != null) {
            val sizes = map.highSpeedVideoSizes
            val preferred = listOf(Size(1280, 720), Size(1920, 1080))
            val sizePick = when {
                preferred.any { sizes.contains(it) } -> preferred.first { sizes.contains(it) }
                sizes.isNotEmpty() -> sizes.minByOrNull { it.width * it.height }!!
                else -> null
            }
            sizePick?.let { s ->
                val ranges = map.getHighSpeedVideoFpsRangesFor(s)
                val r120 = ranges.firstOrNull { r -> (120 >= r.lower && 120 <= r.upper) }
                if (r120 != null) {
                    supports120 = true
                    hsSize = s
                    hsRange = if (r120.lower == 120 && r120.upper == 120) r120 else Range(120, 120)
                }
            }
        }

        // 프리뷰 사이즈/프레임 (REGULAR)
        previewSize = choosePreviewSize()
        previewFps  = pickRegularFpsRange(prefer = 60)

        // 현재 디스플레이 회전/센서로 orientationHint 계산
        orientationHint = computeOrientationHint()

        Log.d("CaptureActivity2", "supports120=$supports120, hsSize=$hsSize, hsRange=$hsRange, preview=${previewSize.width}x${previewSize.height}, previewFps=$previewFps")

        openCamera()
    }

    private fun choosePreviewSize(): Size {
        val chars = characteristics ?: return Size(1280, 720)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(1280, 720)
        val out = map.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1280, 720)

        val pref = listOf(Size(1280, 720), Size(1920, 1080))
        for (p in pref) if (out.contains(p)) return p
        val cand = out.filter { it.width * 9 == it.height * 16 }.sortedBy { it.width * it.height }
        return cand.firstOrNull() ?: out.minByOrNull { it.width * it.height } ?: Size(1280, 720)
    }

    private fun pickRegularFpsRange(prefer: Int): Range<Int> {
        val ranges = characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return Range(30, 30)
        ranges.firstOrNull { it.lower == prefer && it.upper == prefer }?.let { return it }
        ranges.firstOrNull { prefer >= it.lower && prefer <= it.upper }?.let { return it }
        ranges.firstOrNull { it.lower == 60 && it.upper == 60 }?.let { return it }
        ranges.firstOrNull { it.lower == 30 && it.upper == 30 }?.let { return it }
        return ranges.maxByOrNull { it.upper } ?: Range(30, 30)
    }

    private fun openCamera() {
        val stateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                Log.d("CaptureActivity2", "onOpened")
                cameraDevice = device
                camHandler.post { maybeCreatePreviewSession() }
            }
            override fun onDisconnected(device: CameraDevice) {
                Log.d("CaptureActivity2", "onDisconnected")
                device.close()
                cameraDevice = null
            }
            override fun onError(device: CameraDevice, error: Int) {
                Log.e("CaptureActivity2", "onError=$error")
                device.close()
                cameraDevice = null
                handleCameraDeviceError()
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cameraManager.openCamera(cameraId, cameraExecutor, stateCallback)
            } else {
                cameraManager.openCamera(cameraId, stateCallback, camHandler)
            }
        } catch (se: SecurityException) {
            Log.e("CaptureActivity2", "SecurityException on openCamera", se)
            toast("카메라 권한이 필요합니다."); finish()
        } catch (e: CameraAccessException) {
            Log.e("CaptureActivity2", "CameraAccessException on openCamera", e)
            toast("카메라 접근 실패: ${e.message}"); finish()
        }
    }

    private fun handleCameraDeviceError() {
        camHandler.post {
            try { closeSession() } catch (_: Exception) {}
            try { closeCamera() } catch (_: Exception) {}
            camHandler.postDelayed({
                try { openCamera() } catch (_: Exception) { mainHandler.post { toast("카메라 재시작 실패") } }
            }, 350)
        }
    }

    // ================= Preview (REGULAR) =================

    private fun maybeCreatePreviewSession() {
        val dev = cameraDevice ?: run {
            Log.d("CaptureActivity2", "maybeCreatePreviewSession: cameraDevice=null, wait")
            return
        }
        val tv = textureView ?: run { Log.d("CaptureActivity2", "maybeCreatePreviewSession: textureView=null"); return }
        val st = tv.surfaceTexture ?: run { Log.d("CaptureActivity2", "maybeCreatePreviewSession: surfaceTexture=null"); return }

        if (previewSurface == null) {
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(st)
        }

        if (captureSession != null) {
            Log.d("CaptureActivity2", "maybeCreatePreviewSession: reuse existing session")
            rebuildPreviewRepeating()
            return
        }

        val outputs = listOf(OutputConfiguration(previewSurface!!))
        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Log.d("CaptureActivity2", "Preview session onConfigured")
                captureSession = session
                rebuildPreviewRepeating()
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e("CaptureActivity2", "Preview session onConfigureFailed → retry once")
                // retry with fresh surface after short delay
                mainHandler.postDelayed({
                    previewSurface?.release(); previewSurface = null
                    createPreviewSessionAfterClosed()
                }, 250)
            }
        }

        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            cameraExecutor,
            stateCallback
        )
        Log.d("CaptureActivity2", "createCaptureSession PREVIEW size=${previewSize.width}x${previewSize.height}, fps=$previewFps")
        dev.createCaptureSession(config)
    }

    // Create preview session after full close / idle (for rebuilds)
    private fun createPreviewSessionAfterClosed() {
        val dev = cameraDevice ?: return
        val tv = textureView ?: return
        val st = tv.surfaceTexture ?: return

        try { st.setDefaultBufferSize(previewSize.width, previewSize.height) } catch (_: Exception) {}
        if (previewSurface == null) {
            previewSurface = Surface(st)
        }

        val outputs = listOf(OutputConfiguration(previewSurface!!))
        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                Log.d("CaptureActivity2", "Preview session(on rebuild) onConfigured")
                captureSession = session
                rebuildPreviewRepeating()
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e("CaptureActivity2", "Preview rebuild onConfigureFailed → retry")
                mainHandler.postDelayed({
                    previewSurface?.release(); previewSurface = null
                    createPreviewSessionAfterClosed()
                }, 300)
            }
        }

        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            cameraExecutor,
            stateCallback
        )
        Log.d("CaptureActivity2", "createCaptureSession (rebuild) PREVIEW size=${previewSize.width}x${previewSize.height}, fps=$previewFps")
        dev.createCaptureSession(config)
    }

    private fun rebuildPreviewRepeating() {
        val dev = cameraDevice ?: return
        val session = captureSession ?: return
        val pSurf = previewSurface ?: return

        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(pSurf)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)

                if (autoIso) {
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                } else {
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureNs)
                }
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, previewFps)
                applyZoomToRequest(this, linearZoom)
            }.build()
            Log.d("CaptureActivity2", "setRepeatingRequest PREVIEW fps=$previewFps")
            session.setRepeatingRequest(req, null, camHandler)
        } catch (e: Exception) {
            Log.e("CaptureActivity2", "Failed to set repeating request", e)
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            Log.d("CaptureActivity2", "onSurfaceTextureAvailable $width x $height")
            camHandler.post { maybeCreatePreviewSession() }
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun closeSession() {
        val s = captureSession ?: return
        captureSession = null
        try { s.close() } catch (e: Exception) { Log.e("CaptureActivity2", "Error closing session", e) }
    }

    private fun closeCamera() {
        try { cameraDevice?.close() } catch (e: Exception) { Log.e("CaptureActivity2", "Error closing camera", e) }
        cameraDevice = null
        try { previewSurface?.release() } catch (_: Exception) {}
        previewSurface = null
        try { recorderSurface?.release() } catch (_: Exception) {}
        recorderSurface = null
    }

    private fun stopRepeatingAndAbort() {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.abortCaptures() } catch (_: Exception) {}
    }

    // ================= Recording (with countdown / sound / auto stop) =================

    private fun scheduleStartAfterDelay() {
        btnRecord.text = "촬영"
        startCountdown(7) {
            pendingStart = null
            if (!recording) startRecording()
        }
        pendingStart = Runnable { } // marker
    }

    private fun cancelPendingStart() {
        stopCountdown()
        pendingStart?.let { mainHandler.removeCallbacks(it) }
        pendingStart = null
        if (!recording) btnRecord.text = "녹화"
    }

    private fun startRecording() {
        val dev = cameraDevice ?: return
        if (recording) return

        // Encoder/Muxer 준비
        val (codec, mx, inputSurface) = try {
            prepareEncoderAndMuxer()
        } catch (e: Exception) {
            Log.e("CaptureActivity2", "Failed to prepare encoder/muxer", e)
            toast("녹화 준비 실패"); return
        }
        mediaCodec = codec
        muxer = mx
        recorderSurface = inputSurface

        camHandler.post {
            if (supports120 && hsSize != null && hsRange != null) {
                // HS 세션
                val tv = textureView ?: return@post
                val st = tv.surfaceTexture ?: return@post

                stopRepeatingAndAbort()
                closeSession()
                previewSurface?.release(); previewSurface = null

                st.setDefaultBufferSize(hsSize!!.width, hsSize!!.height)
                previewSurface = Surface(st)

                val pOut = OutputConfiguration(previewSurface!!)
                val rOut = OutputConfiguration(recorderSurface!!)
                val outputs = listOf(pOut, rOut)

                val stateCallback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d("CaptureActivity2", "HS Record session onConfigured")
                        captureSession = session
                        try {
                            val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, hsRange)
                                addTarget(previewSurface!!)
                                addTarget(recorderSurface!!)
                                applyZoomToRequest(this, linearZoom)
                            }

                            mediaCodec?.start()
                            beginDraining()
                            mainHandler.post { playNotification() }

                            val hs = session as? CameraConstrainedHighSpeedCaptureSession
                            if (hs != null) {
                                val burst = hs.createHighSpeedRequestList(builder.build())
                                hs.setRepeatingBurst(burst, null, camHandler)
                                Log.d("CaptureActivity2", "HS setRepeatingBurst range=$hsRange")
                            } else {
                                session.setRepeatingRequest(builder.build(), null, camHandler)
                                Log.d("CaptureActivity2", "HS setRepeatingRequest (non-HS cast)")
                            }
                        } catch (e: Exception) {
                            Log.e("CaptureActivity2", "HS startRecording request failed", e)
                            mainHandler.post { toast("고속 녹화 시작 실패") }
                            stopRecording()
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("CaptureActivity2", "HS Record session onConfigureFailed")
                        mainHandler.post { toast("HS session configure failed") }
                        stopRecording()
                    }
                }

                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_HIGH_SPEED,
                    outputs,
                    cameraExecutor,
                    stateCallback
                )
                dev.createCaptureSession(config)
            } else {
                // REGULAR 녹화
                val pSurf = previewSurface ?: run {
                    mainHandler.post { toast("Preview surface null") }
                    stopRecording(); return@post
                }
                val pOut = OutputConfiguration(pSurf)
                val rOut = OutputConfiguration(recorderSurface!!)
                val outputs = listOf(pOut, rOut)
                val recordFps = pickRegularFpsRange(prefer = 60)

                val stateCallback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d("CaptureActivity2", "REG Record session onConfigured")
                        captureSession = session
                        try {
                            val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                                addTarget(pSurf)
                                addTarget(recorderSurface!!)
                                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                if (autoIso) {
                                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                                } else {
                                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                                    set(CaptureRequest.SENSOR_SENSITIVITY, currentIso)
                                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureNs)
                                }
                                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, recordFps)
                                applyZoomToRequest(this, linearZoom)
                            }

                            mediaCodec?.start()
                            beginDraining()
                            mainHandler.post { playNotification() }

                            session.setRepeatingRequest(builder.build(), null, camHandler)
                            Log.d("CaptureActivity2", "REG setRepeatingRequest recordFps=$recordFps")
                        } catch (e: Exception) {
                            Log.e("CaptureActivity2", "REG startRecording request failed", e)
                            mainHandler.post { toast("녹화 시작 실패") }
                            stopRecording()
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("CaptureActivity2", "REG Record session onConfigureFailed")
                        mainHandler.post { toast("Record session configure failed") }
                        stopRecording()
                    }
                }

                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs,
                    cameraExecutor,
                    stateCallback
                )
                dev.createCaptureSession(config)
            }
        }

        recording = true
        btnRecord.text = "저장 중"
        btnRecord.isEnabled = false
        scheduleAutoStop()
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        mainHandler.post { playNotification() }
        btnRecord.text = "녹화"
        btnRecord.isEnabled = true

        camHandler.post {
            stopRepeatingAndAbort()
            try { mediaCodec?.signalEndOfInputStream() } catch (e: Exception) {
                Log.e("CaptureActivity2", "signalEndOfInputStream failed", e)
            }
        }
        cancelAutoStop()
    }

    private fun scheduleAutoStop() {
        cancelAutoStop()
        autoStop = Runnable { if (recording) stopRecording() }
        mainHandler.postDelayed(autoStop!!, 8_000L)
    }

    private fun cancelAutoStop() {
        autoStop?.let { mainHandler.removeCallbacks(it) }
        autoStop = null
    }

    // ================= Encoder / Muxer =================
    // configure → createInputSurface (start는 나중에). 회전 힌트 준비.

    private fun prepareEncoderAndMuxer(): Triple<MediaCodec, MediaMuxer, Surface> {
        val name = "swingfit_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis()) + ".mp4"
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/swingfit")
        }
        outputUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
        val fd: FileDescriptor = contentResolver.openFileDescriptor(outputUri!!, "rw")!!.fileDescriptor

        val useSize = if (supports120 && hsSize != null) hsSize!! else previewSize
        val width = useSize.width
        val height = useSize.height
        val targetFps = if (supports120 && hsRange != null) 120 else previewFps.upper

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 10_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        val inputSurface = codec.createInputSurface()

        val mx = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        // ★ 회전 메타데이터를 start() 전에 설정
        try { if (orientationHint % 360 != 0) mx.setOrientationHint(orientationHint) } catch (_: Exception) {}

        muxerTrack = -1
        muxerStarted = false

        return Triple(codec, mx, inputSurface)
    }

    private fun beginDraining() {
        val codec = mediaCodec ?: return
        val mx = muxer ?: return

        Thread {
            val info = MediaCodec.BufferInfo()
            var finalResultUri: Uri? = null
            try {
                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!recording) break
                        continue
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            muxerTrack = mx.addTrack(codec.outputFormat)
                            mx.start()
                            muxerStarted = true
                        }
                    } else if (outIndex >= 0) {
                        val buf = codec.getOutputBuffer(outIndex) ?: continue
                        if (info.size > 0 && muxerStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            mx.writeSampleData(muxerTrack, buf, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                    if (!recording) break
                }
            } catch (e: Exception) {
                Log.e("CaptureActivity2", "Exception during draining", e)
            } finally {
                try { codec.stop(); codec.release() } catch (e: Exception) { Log.e("CaptureActivity2", "codec release failed", e) }
                try { if (muxerStarted) mx.stop(); mx.release() } catch (e: Exception) { Log.e("CaptureActivity2", "muxer release failed", e) }
                mediaCodec = null
                muxer = null

                // ★ 무조건 0.5x 슬로모션 출력으로 교체
                try {
                    mainHandler.post { toast("슬로우 영상 생성 중...") }
                    outputUri?.let { src ->
                        finalResultUri = makeSlowMotionCopy05x(src)
                    }
                } catch (e: Exception) {
                    Log.e("CaptureActivity2", "makeSlowMotionCopy05x failed", e)
                    // 실패 시 원본 반환
                    finalResultUri = outputUri
                }

                // 세션/스트림 정리 후 프리뷰 복구 (idle 대기 후 재생성)
                camHandler.post {
                    stopRepeatingAndAbort()
                    closeSession()
                    try { recorderSurface?.release() } catch (_: Exception) {}
                    recorderSurface = null

                    pendingPreviewRebuild = true
                    camHandler.postDelayed({
                        if (pendingPreviewRebuild) {
                            try {
                                textureView?.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
                            } catch (_: Exception) {}
                            try { previewSurface?.release() } catch (_: Exception) {}
                            previewSurface = null
                            createPreviewSessionAfterClosed()
                            pendingPreviewRebuild = false
                        }
                    }, 400)
                }

                // ★ 결과 Uri 돌려주기 (SwingActivity가 바로 플레이)
                mainHandler.post {
                    playNotification()
                    val res = finalResultUri ?: outputUri
                    if (res != null) {
                        setResult(RESULT_OK, Intent().setData(res))
                    } else {
                        setResult(RESULT_CANCELED)
                    }
                    btnRecord.text = "녹화"
                    btnRecord.isEnabled = true
                    Toast.makeText(this@CaptureActivity2, "저장 완료", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }.start()
    }

    // ================= 0.5x slow motion =================

    private fun makeSlowMotionCopy05x(input: Uri): Uri {
        val resolver = contentResolver
        val outName = "swingfit_slow05x_" +
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis()) + ".mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, outName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/swingfit")
        }
        val outUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create output Uri")

        val pfdIn  = resolver.openFileDescriptor(input, "r") ?: throw IllegalStateException("open input failed")
        val pfdOut = resolver.openFileDescriptor(outUri, "rw") ?: throw IllegalStateException("open output failed")

        val ex = MediaExtractor()
        var mx: MediaMuxer? = null
        try {
            ex.setDataSource(pfdIn.fileDescriptor)

            val vTrack = (0 until ex.trackCount).firstOrNull { idx ->
                ex.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalStateException("No video track")
            ex.selectTrack(vTrack)
            val inFmt = ex.getTrackFormat(vTrack)

            val mime = inFmt.getString(MediaFormat.KEY_MIME)!!
            val w = inFmt.getInteger(MediaFormat.KEY_WIDTH)
            val h = inFmt.getInteger(MediaFormat.KEY_HEIGHT)
            val rot = if (inFmt.containsKey(MediaFormat.KEY_ROTATION)) inFmt.getInteger(MediaFormat.KEY_ROTATION) else orientationHint

            val outFmt = MediaFormat.createVideoFormat(mime, w, h).apply {
                copyCsd(inFmt, this, "csd-0"); copyCsd(inFmt, this, "csd-1"); copyCsd(inFmt, this, "csd-2")
            }

            mx = MediaMuxer(pfdOut.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rot % 360 != 0) mx.setOrientationHint(rot)
            val outTrack = mx.addTrack(outFmt)
            mx.start()

            val buf = ByteBuffer.allocate(inFmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1_048_576))
            val info = MediaCodec.BufferInfo()
            var firstPts = -1L

            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val pts = ex.sampleTime
                if (firstPts < 0) firstPts = pts

                info.offset = 0
                info.size = size
                info.flags = if (Build.VERSION.SDK_INT >= 28) ex.sampleFlags else 0
                // 0.5x: PTS 2배
                info.presentationTimeUs = (pts - firstPts) * 2L

                mx.writeSampleData(outTrack, buf, info)
                ex.advance()
            }

            mx.stop()
        } finally {
            try { ex.release() } catch (_: Exception) {}
            try { mx?.release() } catch (_: Exception) {}
            try { pfdIn.close() } catch (_: Exception) {}
            try { pfdOut.close() } catch (_: Exception) {}
        }
        return outUri
    }

    private fun copyCsd(src: MediaFormat, dst: MediaFormat, key: String) {
        src.getByteBuffer(key)?.let {
            val dup = ByteBuffer.allocate(it.remaining())
            dup.put(it); dup.flip()
            dst.setByteBuffer(key, dup)
        }
    }

    // ================= Zoom =================

    private fun setLinearZoom(z: Float) {
        linearZoom = z.coerceIn(0f, 1f)
        // 버튼 상태 잠깐 비활성 → 세션 반영 후 다시 활성
        btnZoomIn.isEnabled = false; btnZoomOut.isEnabled = false
        camHandler.post {
            rebuildPreviewRepeating()
            mainHandler.post {
                btnZoomOut.isEnabled = linearZoom > 0f + 1e-3f
                btnZoomIn.isEnabled  = linearZoom < 1f - 1e-3f
            }
        }
    }

    private fun applyZoomToRequest(builder: CaptureRequest.Builder, z: Float) {
        val chars = characteristics ?: return
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val maxZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        if (maxZoom <= 1f) return

        val minW = (sensor.width() / maxZoom).roundToInt()
        val minH = (sensor.height() / maxZoom).roundToInt()
        val cropW = sensor.width() - minW
        val cropH = sensor.height() - minH
        val dx = (cropW * z / 2f).roundToInt()
        val dy = (cropH * z / 2f).roundToInt()
        val crop = Rect(sensor.left + dx, sensor.top + dy, sensor.right - dx, sensor.bottom - dy)
        builder.set(CaptureRequest.SCALER_CROP_REGION, crop)
    }

    // ================= Orientation Hint =================

    private fun computeOrientationHint(): Int {
        val chars = characteristics ?: return 0
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        val rotation: Int = try {
            @Suppress("DEPRECATION")
            (windowManager.defaultDisplay?.rotation ?: display?.rotation ?: Surface.ROTATION_0)
        } catch (_: Exception) {
            Surface.ROTATION_0
        }
        val deviceDegrees = when (rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + deviceDegrees) % 360
        } else {
            (sensorOrientation - deviceDegrees + 360) % 360
        }
    }

    // ================= UI / Overlay / Sound =================

    private fun shutterText(expNs: Long): String {
        val inv = (1_000_000_000.0 / expNs.toDouble())
        // round to nearest common fraction label
        return when (expNs) {
            500_000L   -> "1/2000"
            1_000_000L -> "1/1000"
            2_000_000L -> "1/500"
            4_000_000L -> "1/250"
            else -> String.format(Locale.US, "1/%.0f", inv)
        }
    }

    private fun updateIsoUi() {
        btnIsoToggle.text = if (autoIso) "ISO: AUTO" else "ISO: MANUAL"
        tvIso.text = if (autoIso) "ISO AUTO" else "ISO $currentIso"
        tvShutter.text = shutterText(currentExposureNs)
    }

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

    private fun playNotification() {
        if (ringtone == null) {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)
        }
        try { ringtone?.play() } catch (_: Exception) {}
    }

    private fun stopRingtone() {
        try { ringtone?.stop() } catch (_: Exception) {}
    }

    private fun toast(msg: String) = runOnUiThread {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}