package com.example.swingfit

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.swingfit.databinding.ActivityHomeBinding
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableResult
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

class HomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseDatabase
    private lateinit var functions: FirebaseFunctions
    private lateinit var fs: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseDatabase.getInstance()
        functions = FirebaseFunctions.getInstance()
        fs = FirebaseFirestore.getInstance()

        // 헤더 텍스트
        b.tvHello.text = "안녕하세요!"
        b.bottomNav.selectedItemId = R.id.nav_home // 초기 진입 시에도 홈 강조

        // 하단 탭
        b.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> true
                R.id.nav_records -> {
                    startActivity(Intent(this, RecordsActivity::class.java))
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        b.bottomNav.setOnItemReselectedListener { /* no-op */ }

        loadUserName()
        loadPracticePlan()
        loadLatestFeedbacks() // 최신 피드백 로드
    }

    override fun onResume() {
        super.onResume()
        // 다른 화면에서 돌아왔을 때 하단 탭 선택 상태를 홈으로 복원
        b.bottomNav.selectedItemId = R.id.nav_home
    }

    private fun loadUserName() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            b.tvName.text = "게스트"
            return
        }
        val ref = db.getReference("users").child(uid)
        ref.get().addOnSuccessListener { snap ->
            val name = snap.child("name").getValue(String::class.java)
            val email = snap.child("email").getValue(String::class.java)
            val display = when {
                !name.isNullOrBlank() -> name
                !email.isNullOrBlank() -> email.substringBefore("@")
                else -> "사용자"
            }
            b.tvName.text = "$display" + " 프로님"
        }.addOnFailureListener {
            b.tvName.text = "사용자"
        }
    }

    // Firestore에서 최신 피드백 2종 읽어와서 카드에 꽂기
    private fun loadLatestFeedbacks() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            b.tvFeedbackText1.text = "로그인 후 확인할 수 있어요."
            b.tvFeedbackText2.text = "로그인 후 확인할 수 있어요."
            return
        }

        // 1) ball-analyses → feedback (최신 1개)
        fs.collection("users").document(uid)
            .collection("ball-analyses")
            .orderBy("timestampMs", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .addOnSuccessListener { qs ->
                val doc = qs.documents.firstOrNull()
                val feedback = (doc?.get("feedback") as? String)?.takeIf { it.isNotBlank() }
                    ?: "최근 비거리 피드백이 없습니다."
                b.tvFeedbackText1.text = feedback
            }
            .addOnFailureListener {
                b.tvFeedbackText1.text = "비거리 피드백을 불러오지 못했어요."
            }

        // 2) swing-analyses → overallFeedback (최신 1개)
        fs.collection("users").document(uid)
            .collection("swing-analyses")
            .orderBy("timestampMs", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .addOnSuccessListener { qs ->
                val doc = qs.documents.firstOrNull()
                val overall = (doc?.get("overallFeedback") as? String)?.takeIf { it.isNotBlank() }
                    ?: "최근 스윙 피드백이 없습니다."
                b.tvFeedbackText2.text = overall
            }
            .addOnFailureListener {
                b.tvFeedbackText2.text = "스윙 피드백을 불러오지 못했어요."
            }
    }

    private fun loadPracticePlan() {
        // Cloud Function: getPracticePlan
        functions
            .getHttpsCallable("getPracticePlan")
            .call(hashMapOf("locale" to "ko_KR"))
            .addOnSuccessListener { res: HttpsCallableResult ->
                @Suppress("UNCHECKED_CAST")
                val data = (res.getData() as? Map<String, Any>) ?: emptyMap()

                val progress = anyToInt(data["progress"])
                val count = anyToInt(data["count"])
                val items = anyToListOfMaps(data["items"])

                var distTitle = "비거리 모드"
                var distSub = "비거리 값을 분석합니다"
                var swingTitle = "스윙 모드"
                var swingSub = "스윙 자세를 분석합니다"
                var mixedTitle = "혼합 모드"
                var mixedSub = "빠르게 스윙 타입과 피드백을 분석합니다."

                items.forEach { m ->
                    when (m["type"]) {
                        "distance" -> {
                            distTitle = (m["title"] as? String) ?: distTitle
                            distSub = (m["subtitle"] as? String) ?: distSub
                        }
                        "swing" -> {
                            swingTitle = (m["title"] as? String) ?: swingTitle
                            swingSub = (m["subtitle"] as? String) ?: swingSub
                        }
                        "mixed" -> {
                            mixedTitle = (m["title"] as? String) ?: mixedTitle
                            mixedSub = (m["subtitle"] as? String) ?: mixedSub
                        }
                    }
                }

                setCard(b.cardDistance, distTitle, distSub) {
                    startActivity(Intent(this, MainActivity::class.java))
                }
                setCard(b.cardSwing, swingTitle, swingSub) {
                    startActivity(Intent(this, SwingActivity::class.java))
                }
                setCard(b.cardMixed, mixedTitle, mixedSub) {
                    startActivity(Intent(this, MixedModeActivity::class.java))
                }
            }
            .addOnFailureListener {
                setCard(b.cardDistance, "비거리 모드", "비거리 값을 분석합니다.") {
                    startActivity(Intent(this, MainActivity::class.java))
                }
                setCard(b.cardSwing, "스윙 모드", "스윙 자세를 분석합니다.") {
                    startActivity(Intent(this, SwingActivity::class.java))
                }
                setCard(b.cardMixed, "혼합 모드", "빠르게 스윙 타입과 피드백을 분석합니다.") {
                    startActivity(Intent(this, MixedModeActivity::class.java))
                }
            }
    }

    private fun anyToInt(v: Any?, default: Int = 0): Int {
        return when (v) {
            is Number -> v.toInt()
            is String -> v.toDoubleOrNull()?.toInt() ?: default
            is Boolean -> if (v) 1 else 0
            else -> default
        }
    }

    private fun anyToListOfMaps(v: Any?): List<Map<String, Any?>> {
        return (v as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()
    }

    private fun setCard(
        card: com.google.android.material.card.MaterialCardView,
        title: String,
        sub: String,
        onClick: () -> Unit
    ) {
        val child = card.getChildAt(0) ?: return // include된 item_task 루트(RelativeLayout)
        val tvTitle = child.findViewById<android.widget.TextView>(R.id.title) ?: return
        val tvSub = child.findViewById<android.widget.TextView>(R.id.subtitle) ?: return

        tvTitle.text = title
        tvSub.text = sub

        // 공통 클릭 핸들러
        val handler = android.view.View.OnClickListener { onClick() }

        // 자식이 클릭을 가로채는 상황을 대비해 둘 다 연결
        child.setOnClickListener(handler)
        card.setOnClickListener(handler)

        // 시각적 피드백(리플)도 카드에 주는 게 자연스러움
        card.isClickable = true
        card.isFocusable = true
    }
}