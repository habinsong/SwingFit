package com.example.swingfit

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MixedModeActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null

    // 상단 우측 컨트롤
    private lateinit var spClub: Spinner
    private lateinit var tvStatus: TextView

    // 하단 컨트롤바
    private lateinit var btnPick: Button
    private lateinit var btnCapture: Button
    private lateinit var btnAnalyze: Button
    private lateinit var tvStatus2: TextView

    // 분석 카드들
    private lateinit var tvSwingType: TextView
    private lateinit var tvOverall: TextView
    private lateinit var tvTakeaway: TextView
    private lateinit var tvTransition: TextView
    private lateinit var tvImpact: TextView
    private lateinit var tvFollow: TextView

    // 바텀시트(강점/개선)
    private lateinit var bottomSheet: LinearLayout
    private lateinit var sheetBehavior: BottomSheetBehavior<LinearLayout>
    private lateinit var tvKeyStrength: TextView
    private lateinit var tvImprovement: TextView

    private lateinit var progress: ProgressBar

    private var selectedUri: Uri? = null
    private var pickedMime: String = "video/mp4"
    private var originalFps: Double = 0.0

    private val clubOptions = listOf(
        "드라이버", "3번 우드", "5번 우드",
        "아이언 3", "아이언 4", "아이언 5", "아이언 6", "아이언 7", "아이언 8", "아이언 9",
        "피칭 웨지", "갭 웨지", "샌드 웨지", "로브 웨지"
    )

    // ===== 대화형 분석(혼합 모드) UI (옵셔널) =====
    private var rvChat: RecyclerView? = null
    private var chipSuggestions: ChipGroup? = null
    private var chatProgress: LinearProgressIndicator? = null
    private var etUserPrompt: TextInputEditText? = null
    private var btnSendPrompt: View? = null
    private var btnClearChat: View? = null

    private var chatAdapter: com.example.adapter.ChatMessageAdapter? = null
    private val chatHistory = mutableListOf<com.example.adapter.ChatMessage>()

    // 갤러리
    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onVideoPicked(it) } }

    // 촬영
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val uri = res.data?.data
        if (res.resultCode == RESULT_OK && uri != null) onVideoPicked(uri)
        else Toast.makeText(this, "촬영 취소 또는 저장 실패", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mixed)

        // 뷰 바인딩
        playerView   = findViewById(R.id.playerView)
        spClub       = findViewById(R.id.spClub2)
        tvStatus     = findViewById(R.id.tvStatus)
        btnPick      = findViewById(R.id.btnPickVideo)
        btnCapture   = findViewById(R.id.btnCapture)
        btnAnalyze   = findViewById(R.id.btnAnalyze)
        tvStatus2    = findViewById(R.id.tvStatus2)
        progress     = findViewById(R.id.progress)

        tvSwingType  = findViewById(R.id.tvSwingType)
        tvOverall    = findViewById(R.id.tvOverallFeedback)
        tvTakeaway   = findViewById(R.id.tvTakeaway)
        tvTransition = findViewById(R.id.tvTransition)
        tvImpact     = findViewById(R.id.tvImpact)
        tvFollow     = findViewById(R.id.tvFollow)

        bottomSheet   = findViewById(R.id.bottomSheet)
        sheetBehavior = BottomSheetBehavior.from(bottomSheet)
        tvKeyStrength = findViewById(R.id.tvKeyStrength)
        tvImprovement = findViewById(R.id.tvImprovement)

        spClub.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, clubOptions)
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

        // ===== 대화형 섹션(옵셔널) 뷰 바인딩 =====
        rvChat         = findViewById(R.id.rvChatMixed)
        chipSuggestions= findViewById(R.id.chipSuggestionsMixed)
        chatProgress   = findViewById(R.id.chatProgressMixed)
        etUserPrompt   = findViewById(R.id.etUserPromptMixed)
        btnSendPrompt  = findViewById(R.id.btnSendPromptMixed)
        btnClearChat   = findViewById(R.id.btnClearChatMixed)

        initChatIfPresent()

        btnPick.setOnClickListener {
            if (!ensurePermission()) return@setOnClickListener
            pickVideo.launch(arrayOf("video/*"))
        }
        btnCapture.setOnClickListener { captureLauncher.launch(Intent(this, CaptureActivity::class.java)) }
        btnAnalyze.setOnClickListener {
            val uri = selectedUri ?: return@setOnClickListener Toast.makeText(this, "먼저 영상을 선택/촬영하세요.", Toast.LENGTH_SHORT).show()
            analyzeWithGeminiMixed(uri, pickedMime, spClub.selectedItem?.toString() ?: "드라이버")
        }
    }

    // ===== 대화형 섹션 초기화 (XML 존재 시에만 활성화) =====
    private fun initChatIfPresent() {
        val rv = rvChat ?: return  // 대화형 UI가 없는 레이아웃이면 무시

        // 어댑터
        chatAdapter = com.example.adapter.ChatMessageAdapter()
        rv.apply {
            layoutManager = LinearLayoutManager(this@MixedModeActivity).apply { stackFromEnd = true }
            adapter = chatAdapter
        }

        // 추천 칩 → 입력창 채우기
        chipSuggestions?.let { group ->
            for (i in 0 until group.childCount) {
                (group.getChildAt(i) as? Chip)?.setOnClickListener { chip ->
                    val text = (chip as Chip).text ?: return@setOnClickListener
                    etUserPrompt?.setText(text)
                    etUserPrompt?.setSelection(text.length)
                    // 시트 확장
                    expandBottomSheet()
                }
            }
        }

        // IME 액션/Enter 로 전송
        etUserPrompt?.setOnEditorActionListener { _, actionId, event ->
            val byIme = actionId == EditorInfo.IME_ACTION_SEND
            val byEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (byIme || byEnter) {
                btnSendPrompt?.performClick()
                true
            } else false
        }

        // 포커스 시 바텀시트 확장 + 스크롤 여유
        etUserPrompt?.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) expandBottomSheet() }

        // 전송
        btnSendPrompt?.setOnClickListener {
            val text = etUserPrompt?.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            val sysPrompt = buildSystemPromptForMixed()
            sendChatMessageMixed(text, sysPrompt)
        }

        // 지우기
        btnClearChat?.setOnClickListener {
            chatHistory.clear()
            chatAdapter?.clearAll()
        }
    }

    private fun expandBottomSheet() {
        try {
            sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        } catch (_: Exception) {}
    }

    // ===== 기존 권한/영상 처리 =====

    private fun ensurePermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        val ok = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        if (!ok) ActivityCompat.requestPermissions(this, arrayOf(p), 200)
        return ok
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == 200 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            btnPick.performClick()
        }
    }

    private fun onVideoPicked(uri: Uri) {
        selectedUri = uri
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}

        // MIME/FPS
        val resolverMime = contentResolver.getType(uri)
        pickedMime = resolverMime ?: guessMimeFromName(displayName(uri))
        originalFps = extractFps(contentResolver, uri)

        // 플레이어
        player?.release()
        player = ExoPlayer.Builder(this).build().also { p ->
            playerView.player = p
            p.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) { /* no-op */ }
            })
            p.setMediaItem(MediaItem.fromUri(uri))
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.prepare()
            p.playWhenReady = true
        }

        tvStatus.text = "상태: 영상 로드됨"
        tvStatus2.visibility = View.GONE
        clearAllTexts()
    }

    private fun clearAllTexts() {
        listOf(tvSwingType, tvOverall, tvTakeaway, tvTransition, tvImpact, tvFollow, tvKeyStrength, tvImprovement)
            .forEach { it.text = "-" }
        // 대화형 로그도 초기화 옵션(분석 새로 시작할 때 깔끔하게)
        chatHistory.clear()
        chatAdapter?.clearAll()
    }

    private fun analyzeWithGeminiMixed(uri: Uri, mime: String, club: String) {
        val api = GeminiApi()

        progress.visibility = View.VISIBLE
        tvStatus.text = "상태: 업로드 중…"
        tvStatus2.visibility = View.GONE
        clearAllTexts()

        lifecycleScope.launch {
            // 1) 업로드
            val fileUri = try {
                withContext(Dispatchers.IO) { api.uploadVideo(contentResolver, uri, mime) }
            } catch (e: Exception) {
                Log.e("MixedMode", "업로드 실패", e)
                val hint =
                    if (" 503" in (e.message ?: "") || " 429" in (e.message ?: "")) "서버 혼잡. 잠시 후 재시도해 주세요."
                    else "업로드 실패: ${e.message}"
                progress.visibility = View.GONE
                tvStatus.text = "상태: $hint"
                Toast.makeText(this@MixedModeActivity, hint, Toast.LENGTH_LONG).show()
                return@launch
            }

            tvStatus.text = "상태: 분석 요청 중…"

            // 2) 정량 수치 금지 + 텍스트만 요구하는 시스템/프롬프트
            val sys = """
                당신은 골프 스윙/비거리 코칭 전문가입니다.
                수치(속도, 각도, 거리, rpm 등)나 단위를 제시하지 않습니다.
                한국어로 간결하고 구체적으로 설명합니다.
                불필요한 형식/강조/마크다운/특수기호를 사용하지 않습니다.
            """.trimIndent()

            val prompt = """
                사용 클럽: "$club".
                입력: 단일 비디오. 원본 FPS(참고정보): ${formatFps(originalFps)}.
                다음 키를 순서대로 한 줄씩 "key: 내용" 포맷으로만 출력.
                JSON/마크다운/리스트 금지. 각 항목 최대 2문장. 텍스트(정성)만.

                swingType: 스윙 성향/구질/리듬
                overallFeedback: 한두 문장 총평
                takeawayAnalysis: 테이크어웨이 핵심 관찰
                transitionAnalysis: 탑→다운 전환 핵심 관찰
                impactAnalysis: 임팩트 전후 핵심 관찰
                followThroughAnalysis: 릴리스/팔로우 흐름
                keyStrength: 가장 큰 강점 한 가지
                improvementPoint: 최우선 개선 포인트 한 가지
            """.trimIndent()

            // 3) 생성 호출
            val text = try {
                withContext(Dispatchers.IO) {
                    api.generateWithVideo(
                        fileUri = fileUri,
                        mime = mime,
                        prompt = prompt,
                        systemInstruction = sys
                    )
                }
            } catch (e: Exception) {
                Log.e("MixedMode", "분석 실패", e)
                progress.visibility = View.GONE
                tvStatus.text = "상태: 분석 실패 - ${e.message}"
                Toast.makeText(this@MixedModeActivity, "분석 실패: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }

            // 4) 파싱 & 반영
            val parsed = parseTextBlocks(text)
            tvSwingType.text   = parsed.swingType
            tvOverall.text     = parsed.overall
            tvTakeaway.text    = parsed.takeaway
            tvTransition.text  = parsed.transition
            tvImpact.text      = parsed.impact
            tvFollow.text      = parsed.follow
            tvKeyStrength.text = parsed.keyStrength
            tvImprovement.text = parsed.improvement

            // 바텀시트 펼치기
            expandBottomSheet()

            progress.visibility = View.GONE
            tvStatus.text = "상태: 분석 완료"
            tvStatus2.text = "결과가 카드와 바텀시트에 반영되었습니다."
            tvStatus2.visibility = View.VISIBLE
        }
    }

    // ===== 대화형 분석: 텍스트 질의 =====

    private fun sendChatMessageMixed(userText: String, systemPrompt: String) {
        val adapter = chatAdapter ?: return
        val rv = rvChat ?: return

        // 사용자 메시지 반영
        val userMsg = com.example.adapter.ChatMessage(role = com.example.adapter.ChatRole.USER, text = userText)
        adapter.append(userMsg)
        rv.post {
            val last = (rv.adapter?.itemCount ?: 1) - 1
            if (last >= 0) rv.smoothScrollToPosition(last)
        }
        etUserPrompt?.setText("")
        chatProgress?.visibility = View.VISIBLE
        btnSendPrompt?.isEnabled = false

        val gemini = GeminiApi()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val resp = gemini.generateWithText(
                    prompt = userText,
                    systemInstruction = systemPrompt
                )
                val botMsg = com.example.adapter.ChatMessage(role = com.example.adapter.ChatRole.BOT, text = resp)
                chatHistory.addAll(listOf(userMsg, botMsg))
                withContext(Dispatchers.Main) {
                    adapter.append(botMsg)
                    rv.post {
                        val last = (rv.adapter?.itemCount ?: 1) - 1
                        if (last >= 0) rv.smoothScrollToPosition(last)
                    }
                    chatProgress?.visibility = View.GONE
                    btnSendPrompt?.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    chatProgress?.visibility = View.GONE
                    btnSendPrompt?.isEnabled = true
                    Toast.makeText(this@MixedModeActivity, "오류: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun buildSystemPromptForMixed(): String {
        // 현재 화면에 표시된 분석 결과와 선택된 클럽을 간결 메타로 제공
        val club = spClub.selectedItem?.toString().orEmpty()
        val sb = StringBuilder()
        sb.appendLine("[SYSTEM]")
        sb.appendLine("역할: 프로 골프 코치")
        sb.appendLine("원칙: 주어진 맥락만 근거로 간결하고 구체적으로 답변. 수치/단위 금지. 마크다운/특수기호/불릿 금지. 문장형 한국어.")
        sb.appendLine("메타: 클럽=${club.ifBlank { "-" }}, 스윙타입=${tvSwingType.text?.toString().orEmpty().ifBlank { "-" }}, 강점=${tvKeyStrength.text?.toString().orEmpty().ifBlank { "-" }}, 개선=${tvImprovement.text?.toString().orEmpty().ifBlank { "-" }}")
        sb.appendLine("출력형식: 3~6문장. 필요한 경우 한 문장에 조언과 이유를 함께 제시.")
        return sb.toString().trim()
    }

    // ===== 유틸 =====

    private fun displayName(uri: Uri): String {
        var name = "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        return name
    }

    private fun guessMimeFromName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".mov") -> "video/quicktime"
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

    private fun extractFps(cr: ContentResolver, uri: Uri): Double {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(this, uri)
            val cap = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
            if (cap != null && cap > 0) return cap
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return 0.0
            if (Build.VERSION.SDK_INT >= 28) {
                val cnt = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
                if (cnt != null && durMs > 0) return cnt.toDouble() / (durMs.toDouble() / 1000.0)
            }
            0.0
        } catch (_: Exception) { 0.0 } finally { try { mmr.release() } catch (_: Exception) {} }
    }

    private fun formatFps(fps: Double): String = if (fps <= 0.1) "알 수 없음" else String.format("%.2f", fps)

    // Gemini 텍스트 파싱
    private data class Parsed(
        val swingType: String = "-",
        val overall: String = "-",
        val takeaway: String = "-",
        val transition: String = "-",
        val impact: String = "-",
        val follow: String = "-",
        val keyStrength: String = "-",
        val improvement: String = "-"
    )

    private fun parseTextBlocks(raw: String): Parsed {
        fun find(key: String): String {
            val rx = Regex("(?im)^" + Regex.escape(key) + ":\\s*(.+)")
            return rx.find(raw)?.groupValues?.getOrNull(1)?.trim().orEmpty().ifBlank { "-" }
        }
        return Parsed(
            swingType   = find("swingType"),
            overall     = find("overallFeedback"),
            takeaway    = find("takeawayAnalysis"),
            transition  = find("transitionAnalysis"),
            impact      = find("impactAnalysis"),
            follow      = find("followThroughAnalysis"),
            keyStrength = find("keyStrength"),
            improvement = find("improvementPoint")
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
    }
}