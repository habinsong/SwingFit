package com.example.swingfit

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.auth.FirebaseAuth
import android.content.ContentValues
import android.provider.MediaStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentSkipListMap

import android.graphics.PointF
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.*

// ★ 추가: 캐리 기준 불러오기용(DB)
import com.google.firebase.database.FirebaseDatabase

// ★ 추가: 색상 강조(±) 표현
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.graphics.Color
import android.graphics.PathMeasure
import android.graphics.RectF
import android.widget.AdapterView

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainAct"
    }

    // ====== SwingFit 기본 UI ======
    private lateinit var playerView: PlayerView
    private lateinit var overlay: OverlayView
    private lateinit var pickBtn: Button
    private lateinit var analyzeBtn: Button
    private lateinit var captureBtn: Button   // 촬영 버튼
    private lateinit var progressBar: ProgressBar
    private lateinit var status: TextView
    private lateinit var bottomSheetLayout: LinearLayout

    // ====== (선택) Gemini 관련 UI ======
    private var spClub: Spinner? = null
    private var txtInfo: TextView? = null   // 화면에는 숨김 처리

    // ====== 고정 카드뷰 바인딩 대상 ======
    private lateinit var tvTotalDistance: TextView
    private lateinit var tvCarryDistance: TextView
    private lateinit var tvClubHeadSpeed: TextView
    private lateinit var tvBallSpeed: TextView
    private lateinit var tvSmashFactor: TextView
    private lateinit var tvLaunchAngle: TextView
    private lateinit var tvBackspin: TextView
    private lateinit var tvApexHeight: TextView
    private lateinit var tvSwingTempo: TextView
    private lateinit var tvSwingPath: TextView
    private lateinit var tvFeedback: TextView

    // ====== 상태 ======
    private var player: ExoPlayer? = null
    private val results = ConcurrentSkipListMap<Long, Detection>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var analyzer: BallAnalyzer
    private var selectedUri: Uri? = null
    private var currentRoi = RoiConfig()

    private var impactCount = 0
    private var lastImpactUsSeen: Long? = null

    // BottomSheetBehavior
    private lateinit var sheetBehavior: BottomSheetBehavior<LinearLayout>

    // ====== Gemini 메타 ======
    private var pickedMime: String = "video/mp4"
    private var originalFps: Double = 0.0
    private var lastImpactUs: Long? = null
    private var geminiTriggered = false

    // ★ 최근 생성한 썸네일 URI(숨김 폴더)
    private var thumbUri: Uri? = null
    // ★ 트레이서가 그려진 썸네일 URI(숨김 폴더)
    private var thumbTracerUri: Uri? = null

    private val clubOptions = listOf(
        "드라이버", "3번 우드", "5번 우드",
        "아이언 3", "아이언 4", "아이언 5", "아이언 6", "아이언 7", "아이언 8", "아이언 9",
        "피칭 웨지", "갭 웨지", "샌드 웨지", "로브 웨지"
    )

    // 갤러리에서 영상 선택
    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { onVideoPicked(it) } }

    // 촬영 액티비티 실행/결과 수신
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode == RESULT_OK && uri != null) {
            onVideoPicked(uri)
        } else {
            Toast.makeText(this, "촬영이 취소되었거나 저장되지 않았습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensurePermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        val granted =
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        if (!granted) ActivityCompat.requestPermissions(this, arrayOf(p), 100)
        return granted
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == 100 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            pickBtn.performClick()
        }
    }

    // ====== ★ 추가: 기준 캐리 관련 상태 ======
    private var baselineCarries: MutableMap<String, Int> = mutableMapOf()
    private var baselineLoaded: Boolean = false

    // ★ 마지막 결과 캐시(클럽 변경 시 재계산 용)
    private var lastResultMap: Map<String, String>? = null

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 뷰 바인딩
        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlayView)
        pickBtn = findViewById(R.id.btnPick)
        analyzeBtn = findViewById(R.id.btnReplay)
        captureBtn = findViewById(R.id.btnCapture)
        progressBar = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        bottomSheetLayout = findViewById(R.id.bottomSheet)

        // (선택) Firebase 익명 로그인
        if (FirebaseAuth.getInstance().currentUser == null) {
            FirebaseAuth.getInstance().signInAnonymously()
                .addOnSuccessListener { status.text = "상태: 로그인됨(익명)" }
                .addOnFailureListener { e ->
                    Log.e(TAG, "익명 로그인 실패", e)
                    status.text = "상태: 로그인 실패 (${e.message})"
                }
        }

        // (선택) Gemini UI 바인딩
        spClub = findViewById<Spinner?>(R.id.spClub)?.apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                clubOptions
            )
            // baseline 로드 후 onItemSelectedListener를 설정하므로 여기선 생략
        }
        // 파일 정보 텍스트는 화면에 표시하지 않음 (GONE 처리)
        txtInfo = findViewById<TextView?>(R.id.txtVideoInfo)?.apply { visibility = View.GONE }

        // 고정 카드뷰 바인딩
        tvTotalDistance = findViewById(R.id.tvtotalDistance)
        tvCarryDistance = findViewById(R.id.tvcarryDistance)
        tvClubHeadSpeed = findViewById(R.id.tvclubHeadSpeed)
        tvBallSpeed = findViewById(R.id.tvballSpeed)
        tvSmashFactor = findViewById(R.id.tvsmashFactor)
        tvLaunchAngle = findViewById(R.id.tvlaunchAngle)
        tvBackspin = findViewById(R.id.tvbackspin)
        tvApexHeight = findViewById(R.id.tvapexHeight)
        tvSwingTempo = findViewById(R.id.tvswingTempo)
        tvSwingPath = findViewById(R.id.tvswingPath)
        tvFeedback = findViewById(R.id.txtfeedback)

        sheetBehavior = BottomSheetBehavior.from(bottomSheetLayout)
        overlay.setBottomSheetPeekHeight(sheetBehavior.peekHeight)

        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        analyzer = BallAnalyzer(this)

        overlay.isClickable = true
        overlay.bringToFront()
        overlay.setOnRoiChangedListener { roi ->
            currentRoi = roi.clampPortrait()
            overlay.setRoi(currentRoi)
            status.text = "ROI 지정됨. [분석] 버튼을 누르세요."
        }
        analyzeBtn.text = "분석"

        // 갤러리 선택
        pickBtn.setOnClickListener {
            if (!ensurePermission()) return@setOnClickListener
            pickVideo.launch(arrayOf("video/*"))
        }

        // 분석 실행
        analyzeBtn.setOnClickListener {
            analyzer.setClubByName(spClub?.selectedItem?.toString())
            val uri = selectedUri
            if (uri == null) {
                Toast.makeText(this, "먼저 영상을 선택하거나 촬영하세요.", Toast.LENGTH_SHORT).show()
            } else {
                geminiTriggered = false
                lastImpactUs = null
                runAnalysis(uri)
            }
        }

        // 촬영 실행: CaptureActivity 호출
        captureBtn.setOnClickListener {
            captureLauncher.launch(Intent(this, CaptureActivity::class.java))
        }

        // ★ 회원 기준 캐리 로드
        loadBaselineCarries()
    }

    private fun onVideoPicked(uri: Uri) {
        selectedUri = uri
        // 퍼시스턴트 권한(갤러리 선택 시에만)
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) { }

        // MIME/FPS/파일명
        val name = displayName(uri)
        val resolverMime = contentResolver.getType(uri)
        pickedMime = resolverMime ?: guessMimeFromName(name)
        originalFps = extractFps(contentResolver, uri)

        // 화면에는 표시하지 않고, 로그로만 남김
        Log.d(TAG, "선택된 파일: $name, MIME=$pickedMime, 원본FPS=${formatFps(originalFps)}")

        // 플레이어 세팅
        player?.release()
        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            p.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    super.onVideoSizeChanged(videoSize)
                    overlay.setVideoInfo(videoSize.width, videoSize.height)
                }
            })
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.playWhenReady = true
            p.play()
        }

        // 상태 초기화
        results.clear()
        overlay.setDetections(results)
        overlay.setResult(null)
        overlay.setRoi(currentRoi)
        status.text = "영상 로드됨. 화면에서 ROI 드래그 후 [분석]을 누르세요."
        startUiTicker()

        // ★ 1초 썸네일 생성/저장 (숨김 폴더, 비동기)
        lifecycleScope.launch(Dispatchers.Default) {
            val saved = generateAndSaveThumbAt1sHidden(uri)
            withContext(Dispatchers.Main) {
                thumbUri = saved
                if (saved != null) {
                    // 화면 표시 대신 로그만
                    Log.d(TAG, "썸네일(숨김) 저장됨: $saved")
                } else {
                    Log.w(TAG, "썸네일 생성 실패")
                }
                // 상태 텍스트 갱신 없음
            }
        }
    }

    /** 로컬 분석: 임팩트 검출 시 Gemini를 자동 트리거 */
    private fun runAnalysis(uri: Uri) {
        results.clear()
        overlay.setDetections(results)
        overlay.setResult(null)
        analyzeBtn.isEnabled = false
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        status.text = "분석 시작..."

        impactCount = 0
        lastImpactUsSeen = null

        // 고정 카드뷰 초기화
        if (this::tvTotalDistance.isInitialized) {
            listOf(
                tvTotalDistance, tvCarryDistance, tvClubHeadSpeed, tvBallSpeed, tvSmashFactor,
                tvLaunchAngle, tvBackspin, tvApexHeight, tvSwingTempo, tvSwingPath
            ).forEach { it.text = "-" }
            tvFeedback.text = "피드백"
        }

        val mainHandler = Handler(Looper.getMainLooper())
        scope.launch {
            val tStart = SystemClock.elapsedRealtime()

            analyzer.analyzeVideo(
                uri = uri,
                initialRoi = currentRoi,
                onMeta = { w, h, _, durUs ->
                    Log.d(TAG, "onMeta w=$w h=$h dur=%.3fs".format(durUs / 1_000_000.0))
                },
                onProgress = { cur, total ->
                    val pct = if (total > 0) ((cur * 100L) / total).toInt().coerceIn(0, 100) else 0
                    mainHandler.post {
                        progressBar.progress = pct
                        status.text = "분석 중... $pct%"
                    }
                },
                onDetection = { ts, det ->
                    results[ts] = det
                },
                onRoiFixed = { roi ->
                    currentRoi = roi
                    mainHandler.post { overlay.setRoi(roi) }
                },
                onImpact = { tImpact ->
                    // 같은 타임스탬프(혹은 50ms 이내 중복) 중복 방지
                    val prev = lastImpactUsSeen
                    if (prev == null || kotlin.math.abs(tImpact - prev) > 50_000L) {
                        impactCount += 1
                        lastImpactUsSeen = tImpact

                        Log.d(TAG, "IMPACT #$impactCount at %.3fs".format(tImpact / 1_000_000.0))
                        mainHandler.post {
                            Toast.makeText(
                                this@MainActivity,
                                "임팩트 #$impactCount: %.3fs".format(tImpact / 1_000_000.0),
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        // ★ 두 번째 임팩트가 찍힌 ‘직후’에만 업로드/분석 즉시 시작
                        if (impactCount == 2 && !geminiTriggered) {
                            geminiTriggered = true
                            val club = spClub?.selectedItem?.toString() ?: "드라이버"
                            analyzeWithGemini(
                                uri = uri,
                                mime = pickedMime,
                                club = club,
                                impactUs = tImpact  // 두 번째 임팩트 타임스탬프 사용
                            )
                        }
                    } else {
                        Log.d(TAG, "IMPACT duplicate ignored at ${tImpact}us")
                    }
                },
                onResult = { flightData ->
                    Log.d(
                        TAG,
                        "SUCCESS: Flight data calculated. Carry: ${flightData.estimatedCarryMeters}m"
                    )
                    mainHandler.post { overlay.setResult(flightData) }
                },
                onError = { msg ->
                    Log.e(TAG, "analyze error: $msg")
                    mainHandler.post {
                        progressBar.visibility = View.GONE
                        status.text = "에러: $msg (로그캣 확인)"
                        analyzeBtn.isEnabled = true
                    }
                }
            )

            val elapsedMs = SystemClock.elapsedRealtime() - tStart
            val sec = elapsedMs / 1000.0
            Log.i(TAG, "분석 : ${results.size}건, ${elapsedMs}ms (${String.format("%.2f", sec)}s)")

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                status.text = "분석 : 검출 ${results.size}건"
                Toast.makeText(this@MainActivity, "gemini 에서 연산을 진행중입니다", Toast.LENGTH_SHORT).show()
                overlay.setDetections(results)
                overlay.invalidate()
                analyzeBtn.isEnabled = true
            }
        }
    }

    /** 임팩트 정보를 포함해 Gemini 분석 실행 */
    private fun analyzeWithGemini(uri: Uri, mime: String, club: String, impactUs: Long) {
        val api = GeminiApi()

        lifecycleScope.launch {
            try {
                // 업로드 시작 상태 표시
                withContext(Dispatchers.Main) {
                    status.text = "상태: 업로드 시작"
                }

                // 1) 업로드
                val fileUri = try {
                    api.uploadVideo(contentResolver, uri, mime) // gs:// … 또는 https://…/files/{id}
                } catch (e: Exception) {
                    Log.e("Gemini API Error", "업로드 오류: ${e.message ?: e.toString()}", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "업로드 실패: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        status.text = "상태: 업로드 실패. 다시 시도해주세요."
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    status.text = "상태: 업로드 완료 · 분석 요청"
                }

                // 2) 시스템/프롬프트 구성
                val sys = """
                    당신은 생체 역학과 물리학에 대한 전문적인 지식을 갖춘 세계 최고 수준의 골프 퍼포먼스 분석가입니다.
                    반드시 사실에 근거하여 계산하고, 숫자 단위와 형식을 엄격히 지키며, 존재하지 않는 값을 임의로 추정하지 마십시오.
                """.trimIndent()

                val impactSec = impactUs / 1_000_000.0
                val prompt = """
                    사용자는 현재 "$club"을(를) 사용하고 있다고 명시했습니다. 분석은 반드시 이 클럽을 기반으로 하십시오.
                    이 영상의 원본 프레임 속도는 ${formatFps(originalFps)} fps입니다. 입력은 단일 비디오 파일입니다.

                    중요: 임팩트(클럽-볼 접촉) 시점은 약 ${String.format("%.3f", impactSec)} 초입니다. (원시 타임스탬프: ${impactUs} μs)
                    임팩트 직전/직후 구간을 중점적으로 해석하여 속도, 발사각, 스매시 팩터 추정을 정교화하십시오.

                    영상을 분석하고, 아래 순서로 결과를 한 줄(key: value)씩 한국어로만 출력하세요. JSON/마크다운 금지. 단위 표기 포함.
                    totalDistance: [총 비거리(미터)]
                    carryDistance: [캐리 비거리(미터)]
                    clubHeadSpeed: [클럽 헤드 스피드(m/s)]
                    ballSpeed: [볼 스피드(m/s)]
                    smashFactor: [스매시 팩터]
                    launchAngle: [발사각(도)]
                    backspin: [백스핀(RPM)]
                    apexHeight: [최고 높이(미터)]
                    swingTempo: [스윙 템포(예: "3:1")]
                    swingPath: [스윙 궤도(예: "인-아웃")]
                    clubType: ["$club"]
                    environment: [식별된 환경(예: "인도어 연습장")]
                    feedback: [개선을 위한 한두 문장]
                """.trimIndent()

                // 3) 생성 호출
                val text = try {
                    api.generateWithVideo(
                        fileUri = fileUri,
                        mime = mime,
                        prompt = prompt,
                        systemInstruction = sys
                    )
                } catch (e: Exception) {
                    Log.e("Gemini API Error", "분석 요청 오류: ${e.message ?: e.toString()}", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "분석 요청 실패: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        status.text = "분석 요청 실패. 다시 시도해주세요."
                    }
                    return@launch
                }

                // 4) 결과 반영
                val map = parseKeyValueLines(text)
                withContext(Dispatchers.Main) {
                    applyResultsToFixedViews(map)
                }

                // 5) 트레이서 썸네일 생성
                val tracer = try {
                    createAndSaveTracerThumbnail(
                        videoUri = uri,
                        impactUs = impactUs,
                        result = map
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "createAndSaveTracerThumbnail failed", e)
                    null
                }
                if (tracer != null) {
                    thumbTracerUri = tracer
                    thumbUri = tracer // 기본 썸네일로 사용
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "썸네일 생성 완료", Toast.LENGTH_SHORT).show()
                    }
                }

                // 6) 저장/업로드
                val ts = System.currentTimeMillis()
                saveAnalysisRecordBall(club = club, result = map, timestampMs = ts)
                uploadAnalysisToFirebaseBall(club = club, result = map, timestampMs = ts)
            } catch (e: Exception) {
                Log.e("Gemini API Error", "오류: ${e.message ?: e.toString()}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "오류: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    status.text = "상태: 오류 발생"
                }
            }
        }
    }

    private fun startUiTicker() {
        val handler = Handler(Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                val posUs = (player?.currentPosition ?: 0L) * 1000L
                overlay.setCurrentTimestamp(posUs)
                handler.postDelayed(this, 16L)
            }
        }
        handler.post(r)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        player?.release()
    }

    /** 분석 결과(텍스트만)를 로컬 Download/SwingFit 폴더에 JSON으로 저장 (썸네일 URI 포함) */
    private fun saveAnalysisRecordBall(club: String, result: Map<String, String>, timestampMs: Long) {
        try {
            val baseName = "ballfit_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestampMs))
            val json = JSONObject().apply {
                put("timestampMs", timestampMs)
                put("club", club)
                put("totalDistance", result["totalDistance"] ?: "-")
                put("carryDistance", result["carryDistance"] ?: "-")
                put("clubHeadSpeed", result["clubHeadSpeed"] ?: "-")
                put("ballSpeed", result["ballSpeed"] ?: "-")
                put("smashFactor", result["smashFactor"] ?: "-")
                put("launchAngle", result["launchAngle"] ?: "-")
                put("backspin", result["backspin"] ?: "-")
                put("apexHeight", result["apexHeight"] ?: "-")
                put("swingTempo", result["swingTempo"] ?: "-")
                put("swingPath", result["swingPath"] ?: "-")
                put("environment", result["environment"] ?: "-")
                put("feedback", result["feedback"] ?: "-")
                put("thumbUriLocal", thumbUri?.toString() ?: "-") // 숨김 썸네일
                put("thumbUriTracer", thumbTracerUri?.toString() ?: "-") // 트레이서 오버레이 썸네일
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
            Log.e(TAG, "saveAnalysisRecordBall failed", e)
        }
    }

    /** 텍스트 결과 저장 (컬렉션 경로: users/{uid}/ball-analyses/{timestampMs}) - 썸네일 URI 포함 */
    private fun uploadAnalysisToFirebaseBall(club: String, result: Map<String, String>, timestampMs: Long) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: run {
                Log.e(TAG, "uploadAnalysisToFirebaseBall: no auth user")
                runOnUiThread { status.text = "상태: 업로드 실패(로그인 필요)" }
                return
            }
        val firestore = FirebaseFirestore.getInstance()
        val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(timestampMs))

        val doc = hashMapOf(
            "timestampMs" to timestampMs,
            "day" to day,
            "ownerUid" to uid,
            "club" to club,
            "totalDistance" to (result["totalDistance"] ?: "-"),
            "carryDistance" to (result["carryDistance"] ?: "-"),
            "clubHeadSpeed" to (result["clubHeadSpeed"] ?: "-"),
            "ballSpeed" to (result["ballSpeed"] ?: "-"),
            "smashFactor" to (result["smashFactor"] ?: "-"),
            "launchAngle" to (result["launchAngle"] ?: "-"),
            "backspin" to (result["backspin"] ?: "-"),
            "apexHeight" to (result["apexHeight"] ?: "-"),
            "swingTempo" to (result["swingTempo"] ?: "-"),
            "swingPath" to (result["swingPath"] ?: "-"),
            "environment" to (result["environment"] ?: "-"),
            "feedback" to (result["feedback"] ?: "-"),
            "thumbUriLocal" to (thumbUri?.toString() ?: "-"),
            "thumbUriTracer" to (thumbTracerUri?.toString() ?: "-"),
            "createdAt" to Date(timestampMs)
        )

        firestore.collection("users").document(uid)
            .collection("ball-analyses").document(timestampMs.toString())
            .set(doc)
            .addOnSuccessListener {
                runOnUiThread { status.text = "" }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Firestore set failed", e)
                runOnUiThread { status.text = "상태: 분석 결과 저장 완료 · Firestore 업로드 실패" }
            }
    }

    // ====== 유틸 ======
    private fun guessMimeFromName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".mov") -> "video/mov"
            lower.endsWith(".mp4") -> "video/mp4"
            lower.endsWith(".mpeg") || lower.endsWith(".mpg") -> "video/mpeg"
            lower.endsWith(".avi") -> "video/avi"
            lower.endsWith(".flv") -> "video/x-flv"
            lower.endsWith(".webm") -> "video/webm"
            lower.endsWith(".wmv") -> "video/wmv"
            lower.endsWith(".3gp") || lower.endsWith(".3gpp") -> "video/3gpp"
            else -> "application/octet-stream"
        }
    }

    private fun displayName(uri: Uri): String {
        var name = "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        return name
    }

    private fun extractFps(cr: ContentResolver, uri: Uri): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            val frameRate = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE
            )?.toDoubleOrNull()
            if (frameRate != null && frameRate > 0) return frameRate

            val durMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: return 0.0
            val videoFrameCount = if (Build.VERSION.SDK_INT >= 28) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toLongOrNull()
            } else null
            if (videoFrameCount != null && durMs > 0) {
                (videoFrameCount.toDouble() / (durMs.toDouble() / 1000.0))
            } else 0.0
        } catch (_: Exception) {
            0.0
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun formatFps(fps: Double): String =
        if (fps <= 0.1) "알 수 없음" else String.format("%.2f", fps)

    private fun parseKeyValueLines(text: String): Map<String, String> {
        val map = linkedMapOf<String, String>()
        text.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (key.isNotEmpty() && value.isNotEmpty()) {
                    map[key] = value
                }
            }
        }
        return map
    }

    /** Gemini 결과를 고정 카드뷰(TextView들)에 반영 */
    private fun applyResultsToFixedViews(result: Map<String, String>) {
        fun setOrDash(tv: TextView, key: String) {
            val v = result[key]
            tv.text = if (!v.isNullOrBlank()) v else "-"
        }
        setOrDash(tvTotalDistance, "totalDistance")
        setOrDash(tvCarryDistance, "carryDistance")
        setOrDash(tvClubHeadSpeed, "clubHeadSpeed")
        setOrDash(tvBallSpeed, "ballSpeed")
        setOrDash(tvSmashFactor, "smashFactor")
        setOrDash(tvLaunchAngle, "launchAngle")
        setOrDash(tvBackspin, "backspin")
        setOrDash(tvApexHeight, "apexHeight")
        setOrDash(tvSwingTempo, "swingTempo")
        setOrDash(tvSwingPath, "swingPath")
        tvFeedback.text = result["feedback"] ?: tvFeedback.text

        // ★ 결과 캐시 & delta 갱신
        lastResultMap = result
        updateTotalDistanceDelta()

        if (this::sheetBehavior.isInitialized) {
            bottomSheetLayout.post {
                try { sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED } catch (_: Exception) {}
            }
        }
    }

    // ====== ★★★ 기준 캐리 로드 & 매핑 / delta 표시 로직 ★★★ ======

    private fun loadBaselineCarries() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = FirebaseDatabase.getInstance().getReference("users").child(uid)

        ref.get().addOnSuccessListener { snap ->
            try {
                val carries = snap.child("carries")
                val driver = carries.child("driver").getValue(Int::class.java)
                val auto   = carries.child("auto")
                val woods  = auto.child("woods")
                val irons  = auto.child("irons")
                val wedges = auto.child("wedges")

                baselineCarries.clear()

                // 1) 드라이버/우드
                driver?.let { baselineCarries["드라이버"] = it }
                woods.child("3W").getValue(Int::class.java)?.let { baselineCarries["3번 우드"] = it }
                woods.child("5W").getValue(Int::class.java)?.let { baselineCarries["5번 우드"] = it }

                // 2) 아이언 ("아이언 n" ↔ "ni")
                fun putIron(label: String, key: String) {
                    irons.child(key).getValue(Int::class.java)?.let { baselineCarries[label] = it }
                }
                putIron("아이언 3", "3i")
                putIron("아이언 4", "4i")
                putIron("아이언 5", "5i")
                putIron("아이언 6", "6i")
                putIron("아이언 7", "7i")
                putIron("아이언 8", "8i")
                putIron("아이언 9", "9i")

                // 3) 웨지
                wedges.child("PW").getValue(Int::class.java)?.let { baselineCarries["피칭 웨지"] = it }
                wedges.child("52°").getValue(Int::class.java)?.let { baselineCarries["갭 웨지"] = it }
                    ?: wedges.child("50°").getValue(Int::class.java)?.let { baselineCarries["갭 웨지"] = it }
                wedges.child("56°").getValue(Int::class.java)?.let { baselineCarries["샌드 웨지"] = it }
                wedges.child("60°").getValue(Int::class.java)?.let { baselineCarries["로브 웨지"] = it }

                baselineLoaded = baselineCarries.isNotEmpty()
                Log.d(TAG, "Baseline carries loaded: $baselineCarries")

                // spClub 변경 시 즉시 재계산되도록 리스너 연결
                spClub?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?, view: View?, position: Int, id: Long
                    ) { updateTotalDistanceDelta() }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }

                // 이미 결과가 있다면 즉시 반영
                updateTotalDistanceDelta()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse baseline carries", e)
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "loadBaselineCarries failed", e)
        }
    }

    private fun parseMetersToInt(str: String?): Int? {
        if (str.isNullOrBlank()) return null
        // "170", "170m", "170 m", "170.4 m" 등 처리
        val regex = Regex("""(-?\d+(\.\d+)?)""")
        val m = regex.find(str) ?: return null
        val v = m.value.toDoubleOrNull() ?: return null
        return round(v).toInt()
    }

    private fun getBaselineCarryForSelectedClub(): Int? {
        if (!baselineLoaded) return null
        val selected = spClub?.selectedItem?.toString() ?: return null
        return baselineCarries[selected]
    }

    private fun updateTotalDistanceDelta() {
        val result = lastResultMap ?: return
        val total = parseMetersToInt(result["totalDistance"]) ?: return
        val base  = getBaselineCarryForSelectedClub() ?: return

        val diff = total - base
        val signStr = if (diff >= 0) "+${kotlin.math.abs(diff)}m" else "-${kotlin.math.abs(diff)}m"
        // 👉 괄호 앞에 \n 넣어서 줄바꿈
        val display = "총 비거리: ${total}m\n(${signStr})"

        val ss = SpannableString(display)
        val start = display.indexOf('(')
        val end = display.indexOf(')') + 1
        if (start in 0 until end && end <= display.length) {
            val color = if (diff >= 0) Color.RED else Color.BLUE
            ss.setSpan(ForegroundColorSpan(color), start, end, 0)
        }
        tvTotalDistance.text = ss
    }

    // ====== 썸네일(숨김) 생성/저장 유틸 ======

    /** 영상의 1초 지점 썸네일을 추출해 숨김 위치에 저장하고 Uri 반환 */
    private fun generateAndSaveThumbAt1sHidden(videoUri: Uri): Uri? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, videoUri)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val targetUs = minOf(1_000_000L, (durMs * 1000L - 16_000L).coerceAtLeast(0L))
            val frame: Bitmap? = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
            if (frame == null) return null
            saveBitmapToHiddenPictures(frame, prefix = "ballfit_thumb_")
        } catch (e: Exception) {
            Log.e(TAG, "generateAndSaveThumbAt1sHidden failed", e)
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /** 숨김 저장:
     *  - Q+ : Pictures/.SwingFitHidden 에 저장 + IS_PENDING 처리
     *  - Q- : 파일명을 .으로 시작시켜 갤러리 노출 최소화(완전 보장은 아님)
     */
    private fun saveBitmapToHiddenPictures(bmp: Bitmap, prefix: String = "ballfit_"): Uri? {
        return try {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "$prefix$ts.png"
            val resolver = contentResolver

            val values = ContentValues().apply {
                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) fileName else ".$fileName" // Q-: 숨김 효과
                )
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.WIDTH, bmp.width)
                put(MediaStore.Images.Media.HEIGHT, bmp.height)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/.SwingFitHidden")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

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
        } catch (e: Exception) {
            Log.e(TAG, "saveBitmapToHiddenPictures failed", e)
            null
        }
    }

    /** 트레이서 썸네일 생성 */
    private fun createAndSaveTracerThumbnail(
        videoUri: Uri,
        impactUs: Long,
        result: Map<String, String>,
        preOffsetUs: Long = 80_000L,
        windowBeforeUs: Long = 300_000L,
        windowAfterUs: Long = 200_000L,
        targetWidthPx: Int = 1280
    ): Uri? {
        val frame = getScaledFrameAt(
            videoUri = videoUri,
            tsUs = (impactUs - preOffsetUs).coerceAtLeast(500_000L), // 너무 초기 프레임은 피함
            targetWidth = targetWidthPx
        ) ?: return null

        try {
            // 1) 트랙 포인트 수집
            val traj = buildTrajectoryPointsForWindow(
                bmpW = frame.width,
                bmpH = frame.height,
                impactUs = impactUs,
                beforeUs = windowBeforeUs,
                afterUs = windowAfterUs
            )
            val ensuredTraj = if (traj.isNotEmpty()) {
                traj
            } else {
                getImpactPointPx(frame.width, frame.height, impactUs)?.let { listOf(it) } ?: emptyList()
            }

            // 2) 캔버스에 경로/임팩트/레이블 그리기
            val canvas = Canvas(frame)
            drawTracerOnCanvas(
                canvas = canvas,
                bmpW = frame.width,
                bmpH = frame.height,
                traj = ensuredTraj,
                labels = mapOf(
                    "총 비거리" to (result["totalDistance"] ?: "-"),
                    "발사각" to (result["launchAngle"] ?: "-"),
                    "최고점" to (result["apexHeight"] ?: "-")
                )
            )

            // 3) 저장
            return saveBitmapToHiddenPictures(frame, prefix = "ballfit_thumb_tracer_")
        } finally {
            // Bitmap 자원은 OS에 위임
        }
    }

    private fun getImpactPointPx(bmpW: Int, bmpH: Int, impactUs: Long): PointF? {
        val ent = results.floorEntry(impactUs) ?: results.ceilingEntry(impactUs)
        return ent?.value?.center?.let { c -> PointF(c.x * bmpW, c.y * bmpH) }
    }

    private fun getScaledFrameAt(videoUri: Uri, tsUs: Long, targetWidth: Int): Bitmap? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(this, videoUri)
            val w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val vw = if (rot % 180 == 0) w else h
            val vh = if (rot % 180 == 0) h else w
            val scaledH = if (vw > 0 && vh > 0) (vh.toLong() * targetWidth / vw).toInt() else targetWidth
            mmr.getScaledFrameAtTime(tsUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC, targetWidth, scaledH)
                ?: mmr.getScaledFrameAtTime(tsUs, MediaMetadataRetriever.OPTION_CLOSEST, targetWidth, scaledH)
                ?: mmr.getFrameAtTime(tsUs, MediaMetadataRetriever.OPTION_CLOSEST)
        } catch (e: Throwable) {
            Log.e(TAG, "getScaledFrameAt failed", e); null
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    private fun buildTrajectoryPointsForWindow(
        bmpW: Int,
        bmpH: Int,
        impactUs: Long,
        beforeUs: Long,
        afterUs: Long
    ): List<PointF> {
        val start = (impactUs - beforeUs).coerceAtLeast(0L)
        val end = (impactUs + afterUs)
        val sub = try {
            results.subMap(start, true, end, true)
        } catch (_: Throwable) {
            results.filterKeys { it in start..end }.toSortedMap()
        }

        val pts = ArrayList<PointF>(sub.size)
        for ((_, det) in sub) {
            val c = det.center
            pts.add(PointF(c.x * bmpW, c.y * bmpH))
        }
        if (pts.isEmpty()) {
            results.floorEntry(impactUs)?.value?.trail?.let { t ->
                for (p in t) pts.add(PointF(p.x * bmpW, p.y * bmpH))
            }
        }
        return pts
    }

    private fun fitParabolaYasX2(points: List<PointF>): ParabolaCoeffs? {
        if (points.size < 3) return null
        var sX = 0.0; var sX2 = 0.0; var sX3 = 0.0; var sX4 = 0.0
        var sY = 0.0; var sXY = 0.0; var sX2Y = 0.0
        for (p in points) {
            val x = p.x.toDouble()
            val y = p.y.toDouble()
            val x2 = x * x
            sX += x
            sX2 += x2
            sX3 += x2 * x
            sX4 += x2 * x2
            sY += y
            sXY += x * y
            sX2Y += x2 * y
        }
        val n = points.size.toDouble()
        val a00 = sX4; val a01 = sX3; val a02 = sX2
        val a10 = sX3; val a11 = sX2; val a12 = sX
        val a20 = sX2; val a21 = sX;  val a22 = n
        val b0 = sX2Y; val b1 = sXY; val b2 = sY

        fun det3(
            m00: Double, m01: Double, m02: Double,
            m10: Double, m11: Double, m12: Double,
            m20: Double, m21: Double, m22: Double
        ): Double {
            return m00*(m11*m22 - m12*m21) - m01*(m10*m22 - m12*m20) + m02*(m10*m21 - m11*m20)
        }

        val d  = det3(a00,a01,a02, a10,a11,a12, a20,a21,a22)
        if (abs(d) < 1e-9) return null
        val da = det3(b0,a01,a02, b1,a11,a12, b2,a21,a22)
        val db = det3(a00,b0,a02, a10,b1,a12, a20,b2,a22)
        val dc = det3(a00,a01,b0, a10,a11,b1, a20,a21,b2)
        return ParabolaCoeffs((da/d).toFloat(), (db/d).toFloat(), (dc/d).toFloat())
    }

    private data class ParabolaCoeffs(val a: Float, val b: Float, val c: Float)

    private fun findPointNearPath(path: android.graphics.Path, tip: PointF, bmpW: Int, bmpH: Int, seekDownPx: Float): PointF? {
        val pm = android.graphics.PathMeasure(path, false)
        if (pm.length <= 0f) return null
        val steps = 64
        var best: PointF? = null
        var bestDy = Float.POSITIVE_INFINITY
        val pos = FloatArray(2)
        for (i in 0..steps) {
            val dist = pm.length * (i / steps.toFloat())
            if (pm.getPosTan(dist, pos, null)) {
                val y = pos[1]
                val dy = abs((tip.y + seekDownPx) - y)
                if (dy < bestDy) {
                    bestDy = dy
                    best = PointF(pos[0], pos[1])
                }
            }
        }
        return best
    }

    private fun drawTracerOnCanvas(
        canvas: Canvas,
        bmpW: Int,
        bmpH: Int,
        traj: List<PointF>,
        labels: Map<String, String>
    ) {
        if (traj.isEmpty()) return

        // ---- Paints ----
        val shaftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLUE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = (bmpW * 0.010f).coerceAtLeast(6f)   // 굵게
        }
        val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLUE
            style = Paint.Style.FILL
        }
        val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.RED
            style = Paint.Style.FILL
        }
        val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (bmpW * 0.028f).coerceAtLeast(28f)
        }

        // ---- 임팩트 점 ----
        val impact = traj.first()
        canvas.drawCircle(impact.x, impact.y, (bmpW * 0.010f).coerceAtLeast(7f), ballPaint)

        // ---- 12시 방향(위쪽) 화살표: 짧게 ----
        val baseLen = (min(bmpW, bmpH) * 0.18f).coerceAtLeast(60f)    // 이전보다 짧게
        val endX = impact.x
        val endY = (impact.y - baseLen).coerceAtLeast(0f)

        // 몸통
        canvas.drawLine(impact.x, impact.y, endX, endY, shaftPaint)

        // 화살촉
        val headLen  = (bmpW * 0.030f).coerceAtLeast(18f)
        val headWide = (bmpW * 0.018f).coerceAtLeast(10f)
        val ux = 0f; val uy = -1f
        val leftX  = endX - ux * headLen + (-uy) * headWide
        val leftY  = endY - uy * headLen + ( ux) * headWide
        val rightX = endX - ux * headLen + ( uy) * headWide
        val rightY = endY - uy * headLen + (-ux) * headWide
        val headPath = Path().apply {
            moveTo(endX, endY); lineTo(leftX, leftY); lineTo(rightX, rightY); close()
        }
        canvas.drawPath(headPath, tipPaint)

        // ---- 정보 카드 (화살표 끝 바로 위) ----
        val total = labels["총 비거리"] ?: labels["totalDistance"] ?: "-"
        val launch = labels["발사각"] ?: labels["launchAngle"] ?: "-"
        val apex = labels["최고점"] ?: labels["apexHeight"] ?: "-"

        val lines = listOf(
            "총 비거리: $total",
            "발사각: $launch",
            "최고점: $apex"
        )

        val pad = (bmpW * 0.018f).coerceAtLeast(14f)
        val spacing = (textPaint.textSize * 1.25f)
        val cardW = lines.maxOf { textPaint.measureText(it) } + pad * 2
        val cardH = spacing * lines.size + pad * 1.5f

        val gap = (bmpW * 0.012f).coerceAtLeast(8f) // 화살촉과 카드 사이 여백
        var left = (endX - cardW / 2f)
        var top  = (endY - gap - cardH)

        // 화면 밖 방지 클램프
        left = left.coerceIn(pad, bmpW - cardW - pad)
        top  = top.coerceIn(pad, bmpH - cardH - pad)

        val rect = RectF(left, top, left + cardW, top + cardH)
        canvas.drawRoundRect(rect, pad, pad, cardBg)

        var ty = rect.top + pad + textPaint.textSize
        for (ln in lines) {
            canvas.drawText(ln, rect.left + pad, ty, textPaint)
            ty += spacing
        }
    }
}