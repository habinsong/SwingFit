package com.example.swingfit

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.adapter.ChatMessage
import com.example.adapter.ChatMessageAdapter
import com.example.adapter.ChatRole
import com.example.swingfit.databinding.ActivityRecordDetailBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecordDetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityRecordDetailBinding
    private lateinit var chatAdapter: ChatMessageAdapter
    private val chatHistory = mutableListOf<ChatMessage>()

    // 기존 GeminiApi 유지 (영상용 API 포함)
    private val gemini = GeminiApi()

    // 현재 레코드 메타(삭제/프롬프트 공용)
    private lateinit var recordType: String
    private var recordClub: String = ""
    private var recordEnv: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRecordDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        // ===== 툴바 =====
        setSupportActionBar(b.toolbarDetail)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbarDetail.setNavigationOnClickListener { finish() }

        // ===== 기본 정보 =====
        val type          = intent.str("type").lowercase()
        val createdAt     = intent.str("createdAt")
        val thumbnailUri  = intent.str("thumbnailUri")
        val imageUriLocal = intent.str("imageUriLocal")
        val environment   = intent.str("environment")
        val club          = intent.str("club").ifBlank { intent.str("clubType") }

        recordType = type
        recordClub = club
        recordEnv  = environment

        // 상단 헤더
        b.tvType.text = if (type == "distance") "비거리" else "스윙"
        b.tvDate.text = createdAt.orDash()
        b.tvMeta.text = listOf(club, environment).filter { it.isNotBlank() }.joinToString(" • ")

        // 썸네일
        val thumb = when {
            thumbnailUri.isNotBlank()  -> thumbnailUri
            imageUriLocal.isNotBlank() -> imageUriLocal
            else -> ""
        }
        if (thumb.isNotBlank()) {
            Glide.with(this)
                .load(Uri.parse(thumb))
                .placeholder(R.drawable.bg_big_thumb)
                .into(b.imgThumbnail)
        } else {
            b.imgThumbnail.setImageResource(R.drawable.bg_big_thumb)
        }

        // 섹션 표시
        when (type) {
            "distance" -> bindDistanceData()
            "swing"    -> bindSwingData()
            else -> {
                b.sectionDistance.visibility = View.GONE
                b.sectionSwing.visibility = View.GONE
            }
        }

        // 원본/문서ID
        b.tvSourceUri.text = intent.str("docId")

        // 삭제 버튼
        b.btnDeleteRecord.setOnClickListener { confirmDelete() }

        // ===== 대화형 섹션 초기화 =====
        initChatUI(type, club, environment)
    }

    /** -------- 비거리 섹션 -------- */
    private fun bindDistanceData() {
        b.sectionDistance.visibility = View.VISIBLE
        b.sectionSwing.visibility = View.GONE
        b.tvTotalDistance.text = "총 비거리: ${intent.str("totalDistance").orDash()}"
        b.tvCarryDistance.text = "캐리: ${intent.str("carryDistance").orDash()}"
        b.tvLaunchAngle.text   = "발사각: ${intent.str("launchAngle").orDash()}"
        b.tvBallSpeed.text     = "볼스피드: ${intent.str("ballSpeed").orDash()}"
        b.tvBackspin.text      = "백스핀: ${intent.str("backspin").orDash()}"
        b.tvSmashFactor.text   = "스매시: ${intent.str("smashFactor").orDash()}"
        b.tvApexHeight.text    = "최고점: ${intent.str("apexHeight").orDash()}"
    }

    /** -------- 스윙 섹션 -------- */
    private fun bindSwingData() {
        b.sectionDistance.visibility = View.GONE
        b.sectionSwing.visibility = View.VISIBLE
        b.tvSwingType.text         = intent.str("swingType").orDash()
        b.tvOverallFeedback.text   = intent.str("overallFeedback").orDash()
        b.tvTakeawayBody.text      = intent.str("takeaway").orDash()
        b.tvTransitionBody.text    = intent.str("transition").orDash()
        b.tvImpactBody.text        = intent.str("impact").orDash()
        b.tvFollowThroughBody.text = intent.str("followThrough").orDash()
        b.tvKeyStrength.text       = intent.str("keyStrength").orDash()
        b.tvImprovement.text       = intent.str("improvement").orDash()
    }

    /** -------- 대화형 섹션 초기화 -------- */
    private fun initChatUI(type: String, club: String, environment: String) {
        // RecyclerView
        chatAdapter = ChatMessageAdapter()
        b.rvChat.apply {
            layoutManager = LinearLayoutManager(this@RecordDetailActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }

        // 추천 질문 chip 클릭 → 입력창 채우기
        for (i in 0 until b.chipSuggestions.childCount) {
            val child = b.chipSuggestions.getChildAt(i)
            child.setOnClickListener {
                if (child is com.google.android.material.chip.Chip) {
                    b.etUserPrompt.setText(child.text)
                    b.etUserPrompt.setSelection(b.etUserPrompt.text?.length ?: 0)
                }
            }
        }

        // Enter/IME send 처리
        b.etUserPrompt.setOnEditorActionListener { _, actionId, event ->
            val sendByIme = actionId == EditorInfo.IME_ACTION_SEND
            val sendByEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (sendByIme || sendByEnter) {
                b.btnSendPrompt.performClick()
                true
            } else false
        }

        // 전송 버튼
        b.btnSendPrompt.setOnClickListener {
            val text = b.etUserPrompt.text?.toString()?.trim().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            val sysPrompt = buildSystemPromptConcise(type, club, environment)
            sendChatMessage(text, sysPrompt)
        }

        // 지우기 버튼
        b.btnClearChat.setOnClickListener {
            chatHistory.clear()
            chatAdapter.clearAll()
        }
    }

    /** -------- Gemini 2.5 Pro 호출 (텍스트) -------- */
    private fun sendChatMessage(userText: String, systemPrompt: String) {
        // 사용자 메시지 추가
        val userMsg = ChatMessage(role = ChatRole.USER, text = userText)
        chatAdapter.append(userMsg)
        scrollChatToBottom()
        b.etUserPrompt.setText("")
        b.chatProgress.visibility = View.VISIBLE
        b.btnSendPrompt.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = gemini.generateWithText(
                    prompt = userText,
                    systemInstruction = systemPrompt
                )
                val botMsg = ChatMessage(role = ChatRole.BOT, text = response)
                chatHistory.addAll(listOf(userMsg, botMsg))
                withContext(Dispatchers.Main) {
                    chatAdapter.append(botMsg)
                    scrollChatToBottom()
                    b.chatProgress.visibility = View.GONE
                    b.btnSendPrompt.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    b.chatProgress.visibility = View.GONE
                    b.btnSendPrompt.isEnabled = true
                    Toast.makeText(
                        this@RecordDetailActivity,
                        "오류: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun scrollChatToBottom() {
        b.rvChat.post {
            val last = (b.rvChat.adapter?.itemCount ?: 1) - 1
            if (last >= 0) b.rvChat.smoothScrollToPosition(last)
        }
    }

    /** -------- 시스템 프롬프트(간결 버전) -------- */
    private fun buildSystemPromptConcise(type: String, club: String, environment: String): String {
        val sb = StringBuilder()
        sb.appendLine("[SYSTEM]")
        sb.appendLine("당신은 프로 골프 코치입니다.")
        sb.appendLine("주어진 데이터만을 근거로 간결하고 구체적인 분석과 조언을 제공합니다.")
        sb.appendLine("불필요한 서론, 감정 표현, 형식적인 문장은 제외합니다.")
        sb.appendLine("출력은 자연스러운 문장 형태로만 작성하며, 줄바꿈 외의 형식 기호는 사용하지 않습니다.")
        sb.appendLine("다음 금지 규칙을 반드시 지킵니다:")
        sb.appendLine("1. '**', '##', '-', '*', ':', 숫자목록 등 어떠한 서식 기호도 사용하지 않습니다.")
        sb.appendLine("2. 항목 제목이나 라벨(예: '핵심 원인 요약', '개선 포인트')을 붙이지 않습니다.")
        sb.appendLine("3. 오직 완전한 문장으로만 답변합니다.")
        sb.appendLine("4. 가능한 한 짧고 명료하게 답변합니다.")
        sb.appendLine("5. 친절한 골프 코치처럼 대화하되, 요점만 전달합니다.\n")
        sb.appendLine("원칙: 주어진 데이터만 근거로 간결하고 구체적으로 답변. 불필요한 서론·감정·형식 금지. Markdown/특수기호(**, ##, 목록) 사용 금지. 핵심 문장 위주.")
        sb.appendLine("메타: 유형=${if (type == "distance") "비거리" else "스윙"}, 클럽=${club.orDash()}, 환경=${environment.orDash()}")

        if (type == "distance") {
            sb.appendLine("총비거리=${intent.str("totalDistance").orDash()}, 캐리=${intent.str("carryDistance").orDash()}, 발사각=${intent.str("launchAngle").orDash()}, 볼스피드=${intent.str("ballSpeed").orDash()}, 백스핀=${intent.str("backspin").orDash()}, 스매시=${intent.str("smashFactor").orDash()}, 최고점=${intent.str("apexHeight").orDash()}")
        } else {
            sb.appendLine("스윙타입=${intent.str("swingType").orDash()}, 총평=${intent.str("overallFeedback").orDash()}, 강점=${intent.str("keyStrength").orDash()}, 개선=${intent.str("improvement").orDash()}")
        }
        sb.appendLine("출력형식: 3~6문장. 문장형 한국어. 불릿/헤더/강조기호 미사용.")
        return sb.toString().trim()
    }

    /** -------- 삭제 처리 -------- */
    private fun confirmDelete() {
        val docId = intent.str("docId")
        if (docId.isBlank()) {
            Toast.makeText(this, "문서 ID를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val title = if (recordType == "distance") "비거리 분석 삭제" else "스윙 분석 삭제"
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage("현재 분석 기록을 삭제합니다. 복구할 수 없습니다. 진행할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ -> deleteNow(docId) }
            .show()
    }

    private fun deleteNow(docId: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            Toast.makeText(this, "로그인이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val collection = if (recordType == "distance") "ball-analyses" else "swing-analyses"

        b.topProgress.visibility = View.VISIBLE
        b.btnDeleteRecord.isEnabled = false

        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection(collection).document(docId)
            .delete()
            .addOnSuccessListener {
                // 가능하면 연동된 Firebase Storage 파일도 제거 (fire-and-forget)
                tryDeleteStorage(intent.str("thumbnailUri"))
                tryDeleteStorage(intent.str("imageUriLocal"))

                // 호출자에 삭제 결과 전달 (리스트에서 즉시 제거용)
                setResult(RESULT_OK, Intent().apply {
                    putExtra("deletedId", docId)
                    putExtra("deletedType", recordType)
                })

                b.topProgress.visibility = View.GONE
                Toast.makeText(this, "삭제되었습니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
            .addOnFailureListener { e ->
                b.topProgress.visibility = View.GONE
                b.btnDeleteRecord.isEnabled = true
                Toast.makeText(this, "삭제 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // Firebase Storage URL일 때만 안전하게 삭제 시도
    private fun tryDeleteStorage(url: String?) {
        if (url.isNullOrBlank()) return
        val isStorageUrl = url.startsWith("gs://") ||
                url.startsWith("https://firebasestorage.googleapis.com")
        if (!isStorageUrl) return
        runCatching {
            FirebaseStorage.getInstance().getReferenceFromUrl(url).delete()
        }
    }

    /** -------- 유틸 -------- */
    private fun String?.orDash(): String = if (this.isNullOrBlank()) "-" else this
    private fun android.content.Intent.str(key: String): String = getStringExtra(key) ?: ""
}