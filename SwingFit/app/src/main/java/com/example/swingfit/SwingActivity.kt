package com.example.swingfit

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.math.min
import android.content.ContentValues
import android.graphics.Canvas
import android.provider.MediaStore
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

class SwingActivity : ComponentActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var overlayView: Swing_OverlayView
    private lateinit var tvStatus: TextView
    private lateinit var btnPick: Button
    private lateinit var btnAnalyze: Button

    // 분석 카드 & 바텀시트 뷰들
    private lateinit var tvSwingType: TextView
    private lateinit var tvOverallFeedback: TextView
    private lateinit var tvTakeaway: TextView
    private lateinit var tvTransition: TextView
    private lateinit var tvImpact: TextView
    private lateinit var tvFollow: TextView
    private lateinit var tvKeyStrength: TextView
    private lateinit var tvImprovement: TextView

    private lateinit var spClub2: Spinner

    // 분석 기록 저장 위치 (Android Q+ : MediaStore RELATIVE_PATH)
    private val HISTORY_RELATIVE_PATH = "Pictures/SwingFit"

    private var player: ExoPlayer? = null
    private lateinit var tflite_swing: Swing_TFLiteHelper

    private val scope_swing = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var analyzeJob_swing: Job? = null
    private var tickerJob: Job? = null

    private var videoUri_swing: Uri? = null

    // 안정화 캐시(필요 시)
    private var videoW_swing_cache: Int = 0
    private var videoH_swing_cache: Int = 0

    private val clubOptions = listOf(
        "드라이버", "3번 우드", "5번 우드",
        "아이언 3", "아이언 4", "아이언 5", "아이언 6", "아이언 7", "아이언 8", "아이언 9",
        "피칭 웨지", "갭 웨지", "샌드 웨지", "로브 웨지"
    )

    // 타임라인(분석 결과 저장: 비디오 좌표계)
    private val headTimelineVideo = mutableListOf<TimedPoint_swing>()
    private var videoDurationUs: Long = 0L

    // ── 스냅샷(루프 내 1장 캐시) ─────────────────────────────
    private var snapshotBmpCached: Bitmap? = null
    private var snapshotTargetUs: Long = 1_000_000L
    // ────────────────────────────────────────────────────────

    private val pickVideoLauncher_swing = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            videoUri_swing = uri
            tvStatus.text = "상태: 영상 선택됨"
            startPlayer_swing(uri)
        } else {
            tvStatus.text = "상태: 영상 선택 취소"
        }
    }

    // ★ CaptureActivity 연동 런처
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri: Uri? = result.data?.data
            if (uri != null) {
                videoUri_swing = uri
                tvStatus.text = "상태: 촬영 영상 로드됨"
                startPlayer_swing(uri)
            } else {
                tvStatus.text = "상태: 촬영 파일을 찾지 못했습니다"
            }
        } else {
            tvStatus.text = "상태: 촬영 취소"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_swing)

        playerView = findViewById(R.id.playerView)
        overlayView = findViewById(R.id.overlayView)
        tvStatus = findViewById(R.id.tvStatus)
        btnPick = findViewById(R.id.btnPickVideo)
        btnAnalyze = findViewById(R.id.btnAnalyze)
        spClub2 = findViewById(R.id.spClub2)
        spClub2.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, clubOptions)

        // 분석 카드 & 바텀시트 바인딩
        tvSwingType = findViewById(R.id.tvSwingType)
        tvOverallFeedback = findViewById(R.id.tvOverallFeedback)
        tvTakeaway = findViewById(R.id.tvTakeaway)
        tvTransition = findViewById(R.id.tvTransition)
        tvImpact = findViewById(R.id.tvImpact)
        tvFollow = findViewById(R.id.tvFollow)
        tvKeyStrength = findViewById(R.id.tvKeyStrength)
        tvImprovement = findViewById(R.id.tvImprovement)

        tflite_swing = Swing_TFLiteHelper(
            ctx = this,
            modelAssetName_swing = "hand_fp16.tflite",
            inputSize_swing = 640,
            threads_swing = 6,
            confThresh_swing = 0.15f,
            iouThresh_swing = 0.45f
        )

        btnPick.setOnClickListener { pickVideo_swing() }
        btnAnalyze.setOnClickListener { runAnalysis_swing() }

        // ★ 촬영 버튼 클릭 → CaptureActivity 실행
        findViewById<View>(R.id.btnCapture).setOnClickListener {
            captureLauncher.launch(Intent(this, CaptureActivity2::class.java))
        }
    }

    private fun pickVideo_swing() {
        pickVideoLauncher_swing.launch(arrayOf("video/*"))
    }

    private fun startPlayer_swing(uri: Uri) {
        player?.release()
        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            p.setMediaItem(MediaItem.fromUri(uri))
            p.repeatMode = Player.REPEAT_MODE_ALL // 루프 재생
            p.prepare()
            p.playWhenReady = true
        }
        headTimelineVideo.clear()
        overlayView.clearTrails()
        stopOverlayTicker()
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun runAnalysis_swing() {
        val uri = videoUri_swing
        val p = player
        if (uri == null || p == null) {
            tvStatus.text = "상태: 먼저 영상을 선택하세요"
            return
        }
        if (analyzeJob_swing?.isActive == true) {
            tvStatus.text = "상태: 이미 분석 중"
            return
        }

        // 초기화
        headTimelineVideo.clear()
        overlayView.clearTrails()
        tvStatus.text = "상태: 분석 초기화…"

        // 스냅샷 캐시 초기화
        snapshotBmpCached = null
        snapshotTargetUs = 1_000_000L

        analyzeJob_swing = scope_swing.launch(Dispatchers.Default) {
            val decoder = Swing_FrameDecoder(this@SwingActivity, uri)
            try {
                decoder.prepare()

                // 회전/크기 반영
                val rotation_swing = decoder.rotationDegrees
                val vidW_swing = if (rotation_swing == 90 || rotation_swing == 270) decoder.height else decoder.width
                val vidH_swing = if (rotation_swing == 90 || rotation_swing == 270) decoder.width  else decoder.height
                videoDurationUs = decoder.durationUs

                // 스냅샷 타겟상한 보정(영상 끝 근처 방지)
                val safeTail = (videoDurationUs - 16_000L).coerceAtLeast(0L)
                snapshotTargetUs = 1_000_000L.coerceAtMost(safeTail)

                val viewW_swing = awaitMeasuredWidth_swing(playerView)
                val viewH_swing = awaitMeasuredHeight_swing(playerView)
                val scale_view_swing = min(
                    viewW_swing.toFloat() / vidW_swing,
                    viewH_swing.toFloat() / vidH_swing
                )
                val offX_view_swing = (viewW_swing - vidW_swing * scale_view_swing) / 2f
                val offY_view_swing = (viewH_swing - vidH_swing * scale_view_swing) / 2f

                withContext(Dispatchers.Main) {
                    overlayView.viewScale = scale_view_swing
                    overlayView.viewOffX = offX_view_swing
                    overlayView.viewOffY = offY_view_swing
                    tvStatus.text = "상태: 분석 중…"
                }

                videoW_swing_cache = vidW_swing
                videoH_swing_cache = vidH_swing

                var nextTs = 0L
                var processed = 0
                val tStart = System.nanoTime()

                while (isActive) {
                    val f = decoder.nextFrameBitmap() ?: break
                    val ts = f.presentationUs

                    // 구간별 샘플링 간격 (0.5배속 기준)
                    val stepUsDynamic = when {
                        ts < 4_000_000L -> 600_000L // 처음 4초
                        ts > (videoDurationUs - 4_000_000L) -> 600_000L // 끝 4초
                        else -> 80_000L // 중간 구간
                    }

                    if (ts >= nextTs) {
                        val bmp = f.bitmap
                        // 기존 로직 유지: 분석용 회전 보정
                        val frameBmp = ensureRotation_swing(bmp, rotation_swing)

                        // ★ 스냅샷 캐시: 아직 없고, 목표 시각을 처음 넘는 시점의 프레임을 1회만 보관
                        if (snapshotBmpCached == null && ts >= snapshotTargetUs) {
                            // 드로잉 대비: 가변 Bitmap 보장
                            snapshotBmpCached = if (frameBmp.isMutable) {
                                frameBmp.copy(Bitmap.Config.ARGB_8888, true)
                            } else {
                                frameBmp.copy(Bitmap.Config.ARGB_8888, true)
                            }
                        }

                        try {
                            val detections: List<Detection_swing> = tflite_swing.detect(frameBmp)
                            // 헤드 중심 좌표 추출
                            pushCentersToOverlay_swing(detections, ts)
                        } catch (e: Throwable) {
                            Log.e("SwingActivity", "detect error @$ts us", e)
                        } finally {
                            frameBmp.recycle()
                        }

                        processed++
                        if (processed % 5 == 0) {
                            withContext(Dispatchers.Main) {
                                tvStatus.text = "상태: 분석 중… (${processed}프레임 처리)"
                            }
                        }
                        nextTs = ts + stepUsDynamic
                    } else {
                        // 샘플 대상이 아니면 폐기
                        f.bitmap.recycle()
                    }
                }

                val elapsedMs = (System.nanoTime() - tStart) / 1_000_000
                withContext(Dispatchers.Main) {
                    tvStatus.text = "상태: 분석 완료 (${processed}프레임, ${elapsedMs}ms)"
                    overlayView.setAnalyzedTimeline(headTimelineVideo, videoDurationUs)
                    startOverlayTicker()
                    scope_swing.launch(Dispatchers.Default) {
                        saveSnapshotWithOverlayUsingCache()
                    }
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { tvStatus.text = "상태: 오류 - ${e.message}" }
                Log.e("SwingActivity", "analyze failed", e)
            } finally {
                decoder.close()
            }
        }
    }

    /** 분석 완료 후: 루프에서 캐싱한 프레임에 '전체 스윙 궤적'을 오버레이하여 이미지로 저장 */
    private suspend fun saveSnapshotWithOverlayUsingCache() {
        try {
            // 캐시가 없으면 저장 스킵
            val baseBmp = snapshotBmpCached ?: return
            val workBmp = if (baseBmp.isMutable) baseBmp else baseBmp.copy(Bitmap.Config.ARGB_8888, true) ?: return

            val canvas = Canvas(workBmp)
            withContext(Dispatchers.Main) {
                overlayView.drawTrajectoryForExport(canvas, headTimelineVideo)
            }

            val savedUri = saveBitmapToPictures(workBmp, prefix = "swingfit_overlay_")
            // 메모리 해제
            if (workBmp !== snapshotBmpCached) {
                workBmp.recycle()
            }
            snapshotBmpCached?.recycle()
            snapshotBmpCached = null

            withContext(Dispatchers.Main) {
                tvStatus.text = if (savedUri != null) {
                    "분석 완료되었습니다 (${savedUri})"
                } else {
                    "분석에 오류가 발생했습니다."
                }
                savedUri?.let { uriForAi ->
                    analyzeImageWithGemini(uriForAi)
                }
            }
        } catch (e: Throwable) {
            Log.e("SwingActivity", "saveSnapshotWithOverlayUsingCache failed", e)
            withContext(Dispatchers.Main) {
                tvStatus.text = "상태: 스냅샷 저장 오류 - ${e.message}"
            }
        }
    }

    /** MediaStore에 '숨김' 형태로 저장 (갤러리 비노출: 숨김 폴더 + IS_PENDING=1) */
    private fun saveBitmapToPictures(
        bmp: Bitmap,
        prefix: String = "swingfit_"
    ): Uri? {
        return try {
            val filename = prefix + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.WIDTH, bmp.width)
                put(MediaStore.Images.Media.HEIGHT, bmp.height)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/.SwingFitHidden")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        null,
                        null
                    )
                }
            }
            uri
        } catch (e: Throwable) {
            Log.e("SwingActivity", "saveBitmapToPictures failed", e)
            null
        }
    }

    /** 헤드만: 최고 스코어 박스 중심을 (1)라이브 표시 + (2)타임라인에 저장 */
    private suspend fun pushCentersToOverlay_swing(dets: List<Detection_swing>, tsUs: Long) {
        var bestHead: Detection_swing? = null
        for (d in dets) {
            if (d.cls_swing != 1) continue
            if (bestHead == null || d.score_swing > bestHead!!.score_swing) {
                bestHead = d
            }
        }
        withContext(Dispatchers.Main) {
            bestHead?.let { d ->
                val cx = (d.x1_swing + d.x2_swing) * 0.5f
                val cy = (d.y1_swing + d.y2_swing) * 0.5f
                overlayView.addPoint(1, cx, cy)
                headTimelineVideo.add(TimedPoint_swing(cx, cy, tsUs))
            }
        }
    }

    /** 회전 보정 */
    private fun ensureRotation_swing(src: Bitmap, rotation_swing: Int): Bitmap {
        if (rotation_swing == 0) return src
        val mat = Matrix().apply { postRotate(rotation_swing.toFloat()) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, mat, true)
        src.recycle()
        return out
    }

    // ─────────────────────────────────────────────
    // 오버레이 재생 위치 전달용 틱커
    // ─────────────────────────────────────────────

    private fun startOverlayTicker() {
        stopOverlayTicker()
        val p = player ?: return
        tickerJob = scope_swing.launch(Dispatchers.Main.immediate) {
            while (isActive) {
                val posMs = p.currentPosition
                overlayView.updatePlaybackPositionUs(posMs * 1000L)
                delay(16)
            }
        }
    }

    private fun stopOverlayTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    // ─────────────────────────────────────────────

    private suspend fun awaitMeasuredWidth_swing(v: View): Int =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            if (v.width > 0) {
                cont.resume(v.width) {}
                return@suspendCancellableCoroutine
            }
            val l = object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (v.width > 0) {
                        v.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        cont.resume(v.width) {}
                    }
                }
            }
            v.viewTreeObserver.addOnGlobalLayoutListener(l)
            cont.invokeOnCancellation { v.viewTreeObserver.removeOnGlobalLayoutListener(l) }
        }

    private suspend fun awaitMeasuredHeight_swing(v: View): Int =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            if (v.height > 0) {
                cont.resume(v.height) {}
                return@suspendCancellableCoroutine
            }
            val l = object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (v.height > 0) {
                        v.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        cont.resume(v.height) {}
                    }
                }
            }
            v.viewTreeObserver.addOnGlobalLayoutListener(l)
            cont.invokeOnCancellation { v.viewTreeObserver.removeOnGlobalLayoutListener(l) }
        }

    override fun onDestroy() {
        super.onDestroy()
        analyzeJob_swing?.cancel()
        stopOverlayTicker()
        scope_swing.cancel()
        player?.release()
        tflite_swing.close()
        // 스냅샷 캐시 정리
        snapshotBmpCached?.recycle()
        snapshotBmpCached = null
    }

    /** 저장된 스냅샷 이미지를 Gemini로 분석하여 카드/바텀시트에 출력 */
    private fun analyzeImageWithGemini(imageUri: Uri) {
        scope_swing.launch {
            try {
                tvStatus.text = "상태: 업로드 시작"

                val api = com.example.gemini_demo.GeminiAPi_image()

                // 1) 이미지 업로드
                val fileUri = try {
                    withContext(Dispatchers.IO) {
                        api.uploadImage(contentResolver, imageUri, "image/png")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SwingActivity", "이미지 업로드 실패, 다시 시도해주세요.", e)
                    Toast.makeText(this@SwingActivity, "업로드 실패, 다시 시도해주세요.: ${e.message}", Toast.LENGTH_LONG).show()
                    tvStatus.text = "상태: 업로드 실패, 다시 시도해주세요."
                    return@launch
                }

                tvStatus.text = "상태: 업로드 완료 · 분석 요청"

                // 2) 시스템/프롬프트
                val sys = """
                    당신은 골프 스윙 메커니즘과 운동 역학에 대한 세계 최고 수준의 퍼포먼스 분석가입니다.
                    당신의 주된 임무는 시각화된 클럽 헤드 궤적 데이터를 해석하여 스윙의 장점과 단점, 그리고 잠재적 개선점을 진단하는 것입니다.
                    규칙:
                    1. 분석은 반드시 이미지에 나타난 시각적 정보에만 근거해야 합니다.
                    2. 이미지에서 절대 파악할 수 없는 정량적 데이터(예: 클럽 스피드, 볼 스피드, 비거리, 스핀량, 템포)는 절대로 추정하거나 언급하지 마십시오.
                    3. 전문 용어(예: 샬로잉, 스윙 플레인)를 사용하되, 누구나 이해할 수 있도록 쉽고 명확하게 설명해야 합니다.
                """.trimIndent()

                val club = spClub2.selectedItem?.toString() ?: "드라이버"
                val prompt = """
                    사용자는 현재 "$club"을(를) 사용하고 있다고 명시했습니다. 분석은 반드시 이 클럽을 기반으로 하십시오.
                    사용자가 골프 클럽 헤드의 스윙 궤적을 시각화한 단일 이미지를 업로드했습니다.
                    사용자는 각 색상 구간이 다음 스윙 단계를 의미한다고 명시했습니다.
                    - 초록색: 어드레스 이후 테이크어웨이 구간
                    - 빨간색: 백스윙 상승부터 다운스윙으로의 전환 구간
                    - 하늘색: 임팩트 직전부터 임팩트 직후까지의 구간
                    - 파란색: 팔로우스루 및 피니쉬로 향하는 구간

                    이 정보를 바탕으로, 아래에 제시된 형식에 맞춰 스윙 궤적을 심층 분석하고 최종 피드백을 한국어로 제공하십시오.
                    결과는 반드시 지정된 키(Key)와 순서를 따라야 하며, 마크다운이나 JSON 형식을 사용하지 마십시오.

                    swingType: [분석을 통해 파악된 가장 가능성이 높은 스윙 타입 또는 구질 (예: 파워 드로우 스윙)]
                    overallFeedback: [스윙에 대한 1~2 문장의 핵심 총평]
                    takeawayAnalysis: [초록색 구간 분석: 궤도의 안정성, 경로 등]
                    transitionAnalysis: [빨간색 구간 분석: 백스윙 탑의 위치, 다운스윙 전환 시의 샬로잉 여부 등 핵심 특징]
                    impactAnalysis: [하늘색 구간 분석: 인-아웃 궤도, 임팩트 존의 형태 등]
                    followThroughAnalysis: [파란색 구간 분석: 릴리스의 자연스러움, 아크의 크기 등]
                    keyStrength: [이 스윙의 가장 큰 강점 한 가지]
                    improvementPoint: [일관성 향상 또는 잠재적 위험 관리를 위한 핵심 피드백 한 가지]
                """.trimIndent()

                // 3) 생성 호출
                val text = try {
                    withContext(Dispatchers.IO) {
                        api.generateWithImage(
                            fileUri = fileUri,
                            mime = "image/png",
                            prompt = prompt,
                            systemInstruction = sys
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SwingActivity", "분석 요청 실패", e)
                    Toast.makeText(this@SwingActivity, "분석 요청 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    tvStatus.text = "상태: 분석 요청 실패, 다시 시도해주세요."
                    return@launch
                }

                // 4) 결과 반영
                val parsed = parseGeminiAnalysis(text)
                tvSwingType.text = parsed.swingType
                tvOverallFeedback.text = parsed.overallFeedback
                tvTakeaway.text = parsed.takeaway
                tvTransition.text = parsed.transition
                tvImpact.text = parsed.impact
                tvFollow.text = parsed.followThrough
                tvKeyStrength.text = parsed.keyStrength
                tvImprovement.text = parsed.improvement

                // 5) 저장/업로드
                val ts = System.currentTimeMillis()
                saveAnalysisRecord(
                    imageUri = imageUri,
                    club = club,
                    result = parsed,
                    timestampMs = ts
                )
                tvStatus.text = "상태: 분석 완료 · 기록 저장됨 · Firestore 저장 중…"
                uploadAnalysisToFirebase(
                    imageUri = imageUri,
                    club = club,
                    result = parsed,
                    timestampMs = ts
                )
            } catch (e: Exception) {
                android.util.Log.e("SwingActivity", "Gemini 분석 실패", e)
                Toast.makeText(this@SwingActivity, "오류: ${e.message}", Toast.LENGTH_LONG).show()
                tvStatus.text = "상태: 분석 오류 - ${e.message}"
            }
        }
    }

    private data class AnalysisResult(
        val swingType: String = "-",
        val overallFeedback: String = "-",
        val takeaway: String = "-",
        val transition: String = "-",
        val impact: String = "-",
        val followThrough: String = "-",
        val keyStrength: String = "-",
        val improvement: String = "-"
    )

    private fun parseGeminiAnalysis(raw: String): AnalysisResult {
        fun find(key: String): String {
            val regex = Regex("(?im)^" + Regex.escape(key) + ":\\s*(.*)")
            val m = regex.find(raw)
            return m?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { "-" }
        }
        return AnalysisResult(
            swingType = find("swingType"),
            overallFeedback = find("overallFeedback"),
            takeaway = find("takeawayAnalysis"),
            transition = find("transitionAnalysis"),
            impact = find("impactAnalysis"),
            followThrough = find("followThroughAnalysis"),
            keyStrength = find("keyStrength"),
            improvement = find("improvementPoint")
        )
    }

    /** 분석 결과를 이미지와 함께 Pictures/SwingFit 폴더에 사이드카(JSON)로 저장 */
    private fun saveAnalysisRecord(imageUri: Uri, club: String, result: AnalysisResult, timestampMs: Long) {
        try {
            val baseName = "swingfit_overlay_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestampMs))
            val json = JSONObject().apply {
                put("timestampMs", timestampMs)
                put("imageUri", imageUri.toString())
                put("club", club)
                put("swingType", result.swingType)
                put("overallFeedback", result.overallFeedback)
                put("takeaway", result.takeaway)
                put("transition", result.transition)
                put("impact", result.impact)
                put("followThrough", result.followThrough)
                put("keyStrength", result.keyStrength)
                put("improvement", result.improvement)
            }

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$baseName.json")
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/SwingFit")
                }
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toString(2).toByteArray(Charsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            Log.e("SwingActivity", "saveAnalysisRecord failed", e)
        }
    }

    /** Firestore(텍스트만) 저장 */
    private fun uploadAnalysisToFirebase(imageUri: Uri, club: String, result: AnalysisResult, timestampMs: Long) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: run {
                Log.e("SwingActivity", "uploadAnalysisToFirebase: no auth user")
                scope_swing.launch(Dispatchers.Main) {
                    tvStatus.text = "상태: 업로드 실패(로그인 필요)"
                }
                return
            }
        val firestore = FirebaseFirestore.getInstance()
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(timestampMs))

        val doc = hashMapOf(
            "timestampMs" to timestampMs,
            "day" to day,
            "ownerUid" to uid,
            "imageUriLocal" to imageUri.toString(),
            "club" to club,
            "swingType" to result.swingType,
            "overallFeedback" to result.overallFeedback,
            "takeaway" to result.takeaway,
            "transition" to result.transition,
            "impact" to result.impact,
            "followThrough" to result.followThrough,
            "keyStrength" to result.keyStrength,
            "improvement" to result.improvement,
            "createdAt" to Date(timestampMs)
        )

        firestore.collection("users").document(uid)
            .collection("swing-analyses").document(timestampMs.toString())
            .set(doc)
            .addOnSuccessListener {
                scope_swing.launch(Dispatchers.Main) {
                    tvStatus.text = "상태: 분석 완료 · Firestore 저장 완료"
                }
            }
            .addOnFailureListener { e ->
                Log.e("SwingActivity", "Firestore set failed", e)
                scope_swing.launch(Dispatchers.Main) {
                    tvStatus.text = "상태: 분석 완료 · Firestore 저장 실패"
                }
            }
    }
}