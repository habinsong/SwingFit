package com.example.swingfit

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlin.math.max

class SettingsActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseDatabase
    private lateinit var progress: ProgressDialog

    // 공통
    private lateinit var tabLayout: TabLayout
    private lateinit var containerViewInfo: View
    private lateinit var containerViewEdit: View
    private lateinit var btnLogout: Button
    private lateinit var btnSave: Button

    // 보기 탭
    private lateinit var tvNameV: TextView
    private lateinit var tvEmailV: TextView
    private lateinit var tvHandedV: TextView
    private lateinit var tvCarriesV: TextView
    private lateinit var tvSkillV: TextView
    private lateinit var tvPracticeV: TextView
    private lateinit var tvEnvV: TextView
    private lateinit var tvEquipV: TextView

    // 수정 탭 - 기본
    private lateinit var etName: TextInputEditText
    private lateinit var tvEmailR: TextView
    private lateinit var ddHanded: AutoCompleteTextView
    private lateinit var etCarryDriver: TextInputEditText
    private lateinit var etCarry7i: TextInputEditText
    private lateinit var etCarryPW: TextInputEditText
    private lateinit var ddSkillType: AutoCompleteTextView
    private lateinit var etSkillValue: TextInputEditText
    private lateinit var ddPracticeFreq: AutoCompleteTextView

    // 수정 탭 - 연습 환경
    private lateinit var chipGroupEnv: com.google.android.material.chip.ChipGroup

    // 수정 탭 - 장비
    private lateinit var etDriverLoft: TextInputEditText
    private lateinit var et7iLoft: TextInputEditText
    private lateinit var etWedgeLofts: TextInputEditText

    // 자동 캐리 미리보기
    private lateinit var tvAutoDistances: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "SettingsActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        auth = FirebaseAuth.getInstance()
        db = FirebaseDatabase.getInstance()

        progress = ProgressDialog(this).apply {
            setMessage("불러오는 중…")
            setCancelable(false)
        }

        bindViews()
        setupTabs()
        setupDropdowns()
        setupAutoDistancePreview()
        setupButtons()

        loadProfile()
    }

    private fun bindViews() {
        tabLayout = findViewById(R.id.tabLayout)
        containerViewInfo = findViewById(R.id.containerInfo)
        containerViewEdit = findViewById(R.id.containerEdit)

        btnLogout = findViewById(R.id.btnLogout)
        btnSave = findViewById(R.id.btnSave)

        // 보기
        tvNameV = findViewById(R.id.tvNameV)
        tvEmailV = findViewById(R.id.tvEmailV)
        tvHandedV = findViewById(R.id.tvHandedV)
        tvCarriesV = findViewById(R.id.tvCarriesV)
        tvSkillV = findViewById(R.id.tvSkillV)
        tvPracticeV = findViewById(R.id.tvPracticeV)
        tvEnvV = findViewById(R.id.tvEnvV)
        tvEquipV = findViewById(R.id.tvEquipV)

        // 수정
        etName = findViewById(R.id.etNameS)
        tvEmailR = findViewById(R.id.tvEmailS)
        ddHanded = findViewById(R.id.ddHandednessS)
        etCarryDriver = findViewById(R.id.etCarryDriverS)
        etCarry7i = findViewById(R.id.etCarry7iS)
        etCarryPW = findViewById(R.id.etCarryPWS)
        ddSkillType = findViewById(R.id.ddSkillTypeS)
        etSkillValue = findViewById(R.id.etSkillValueS)
        ddPracticeFreq = findViewById(R.id.ddPracticeFreqS)

        chipGroupEnv = findViewById(R.id.chipGroupEnvS)

        etDriverLoft = findViewById(R.id.etDriverLoftS)
        et7iLoft = findViewById(R.id.et7iLoftS)
        etWedgeLofts = findViewById(R.id.etWedgeLoftsS)

        tvAutoDistances = findViewById(R.id.tvAutoDistancesS)
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("내 정보"))
        tabLayout.addTab(tabLayout.newTab().setText("수정"))
        showInfoTab(true)

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showInfoTab(tab.position == 0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun showInfoTab(isInfo: Boolean) {
        containerViewInfo.visibility = if (isInfo) View.VISIBLE else View.GONE
        containerViewEdit.visibility = if (isInfo) View.GONE else View.VISIBLE
        btnSave.visibility = if (isInfo) View.GONE else View.VISIBLE
    }

    private fun setupDropdowns() {
        ddHanded.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("오른손", "왼손"))
        )
        ddSkillType.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("핸디캡", "18홀 평균 타수", "레인지 전용(모름)"))
        )
        ddPracticeFreq.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1,
                listOf("주 0회", "주 1회", "주 2회", "주 3회", "주 4회", "주 5회", "주 6회", "주 7회 이상"))
        )

        ddSkillType.setOnItemClickListener { _, _, position, _ ->
            val selected = when (position) {
                0 -> "핸디캡"
                1 -> "18홀 평균 타수"
                else -> "레인지 전용(모름)"
            }
            etSkillValue.isEnabled = selected != "레인지 전용(모름)"
            if (!etSkillValue.isEnabled) etSkillValue.setText("")
        }
    }

    private fun setupAutoDistancePreview() {
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateAutoDistancesPreview()
            }
        }
        etCarryDriver.addTextChangedListener(watcher)
        etCarry7i.addTextChangedListener(watcher)
        etCarryPW.addTextChangedListener(watcher)
    }

    private fun updateAutoDistancesPreview() {
        val d = etCarryDriver.text?.toString()?.toIntOrNull()
        val i7 = etCarry7i.text?.toString()?.toIntOrNull()
        val pw = etCarryPW.text?.toString()?.toIntOrNull()

        if (d == null || i7 == null || pw == null) {
            tvAutoDistances.text = "드라이버/7i/PW 입력 시 자동 생성"
            return
        }

        val woods = mapOf("3W" to (d - 20).coerceAtLeast(0), "5W" to (d - 35).coerceAtLeast(0))
        val irons = computeIronDistances(i7)
        val wedges = computeWedgeDistances(pw)

        val sb = StringBuilder()
        sb.append("우드\n")
        woods.forEach { (k, v) -> sb.append(" • $k: ${v}m\n") }
        sb.append("\n아이언(단계당 10m)\n")
        irons.forEach { (k, v) -> sb.append(" • $k: ${v}m\n") }
        sb.append("\n웨지\n")
        wedges.forEach { (k, v) -> sb.append(" • $k: ${v}m\n") }
        tvAutoDistances.text = sb.toString()
    }

    private fun setupButtons() {
        btnLogout.setOnClickListener {
            // 1) Firebase 로그아웃
            auth.signOut()

            // 2) 자동 로그인 해제 (Prefs.kt 사용)
            Prefs.clearAutoLogin(this)

            // 3) 로그인 화면으로 이동 (백스택 제거)
            Toast.makeText(this, "로그아웃 되었습니다.", Toast.LENGTH_SHORT).show()
            val i = Intent(this, LoginActivity::class.java)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(i)
            finish()
        }

        btnSave.setOnClickListener {
            saveProfile()
        }
    }

    private fun loadProfile() {
        val uid = auth.currentUser?.uid ?: run {
            Toast.makeText(this, "로그인이 필요합니다.", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        progress.show()
        scope.launch {
            try {
                val snap = db.getReference("users").child(uid).get().await()
                val p = snap.value as? Map<*, *> ?: emptyMap<String, Any>()

                withContext(Dispatchers.Main) {
                    // 보기 탭 채우기
                    val name = (p["name"] as? String).orEmpty()
                    val email = (p["email"] as? String).orEmpty()
                    val handed = (p["handedness"] as? String).orEmpty()
                    val carries = (p["carries"] as? Map<*, *>) ?: emptyMap<String, Any>()
                    val skill = (p["skill"] as? Map<*, *>) ?: emptyMap<String, Any>()
                    val practice = (p["practice"] as? Map<*, *>) ?: emptyMap<String, Any>()
                    val equip = (p["equipment"] as? Map<*, *>) ?: emptyMap<String, Any>()

                    val driver = (carries["driver"] as? Number)?.toInt() ?: -1
                    val i7 = (carries["7i"] as? Number)?.toInt() ?: -1
                    val pw = (carries["pw"] as? Number)?.toInt() ?: -1

                    val woods = ((carries["auto"] as? Map<*, *>)?.get("woods") as? Map<*, *>) ?: emptyMap<String, Any>()
                    val irons = ((carries["auto"] as? Map<*, *>)?.get("irons") as? Map<*, *>) ?: emptyMap<String, Any>()
                    val wedges = ((carries["auto"] as? Map<*, *>)?.get("wedges") as? Map<*, *>) ?: emptyMap<String, Any>()

                    tvNameV.text = name
                    tvEmailV.text = email
                    tvHandedV.text = handed

                    tvCarriesV.text = buildString {
                        append("Driver: ${if (driver >= 0) "${driver}m" else "-"} / 7i: ${if (i7 >= 0) "${i7}m" else "-"} / PW: ${if (pw >= 0) "${pw}m" else "-"}\n")
                        append("[자동생성]\n- 우드: ${woods.entries.joinToString { "${it.key}:${it.value}" }}\n")
                        append("- 아이언: ${irons.entries.joinToString { "${it.key}:${it.value}" }}\n")
                        append("- 웨지: ${wedges.entries.joinToString { "${it.key}:${it.value}" }}")
                    }

                    val skillType = (skill["type"] as? String).orEmpty()
                    val skillValue = (skill["value"] as? Number)?.toFloat() ?: -1f
                    tvSkillV.text = if (skillType == "레인지 전용(모름)") skillType else "$skillType: $skillValue"

                    val freq = (practice["frequency"] as? String).orEmpty()
                    tvPracticeV.text = freq

                    val envs = (practice["environments"] as? List<*>)?.joinToString() ?: "-"
                    tvEnvV.text = envs

                    val dLoft = (equip["driver_loft_deg"] as? Number)?.toFloat() ?: 10.5f
                    val i7Loft = (equip["seven_iron_loft_deg"] as? Number)?.toFloat() ?: 32f
                    val wLofts = (equip["wedge_lofts"] as? List<*>)?.joinToString() ?: "52, 56"
                    tvEquipV.text = "Driver ${dLoft}°, 7i ${i7Loft}°, Wedges [$wLofts]"

                    // 수정 탭 채우기
                    etName.setText(name)
                    tvEmailR.text = email
                    ddHanded.setText(handed, false)
                    if (driver >= 0) etCarryDriver.setText(driver.toString())
                    if (i7 >= 0) etCarry7i.setText(i7.toString())
                    if (pw >= 0) etCarryPW.setText(pw.toString())

                    ddSkillType.setText(skillType.ifBlank { "레인지 전용(모름)" }, false)
                    if (skillType != "레인지 전용(모름)" && skillValue >= 0) {
                        etSkillValue.isEnabled = true
                        etSkillValue.setText(skillValue.toString())
                    } else {
                        etSkillValue.isEnabled = false
                        etSkillValue.setText("")
                    }

                    ddPracticeFreq.setText(freq.ifBlank { "주 0회" }, false)

                    // 환경칩
                    setChipChecked(R.id.chipIndoorScreenS, envs.contains("스크린"))
                    setChipChecked(R.id.chipIndoorRangeS, envs.contains("실내 연습장"))
                    setChipChecked(R.id.chipOutdoorRangeS, envs.contains("실외 연습장"))
                    setChipChecked(R.id.chipFieldS, envs.contains("필드"))

                    etDriverLoft.setText(dLoft.toString())
                    et7iLoft.setText(i7Loft.toString())
                    etWedgeLofts.setText(wLofts)

                    updateAutoDistancesPreview()
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadProfile failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "불러오기 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) { progress.dismiss() }
            }
        }
    }

    private fun setChipChecked(id: Int, checked: Boolean) {
        findViewById<Chip>(id)?.isChecked = checked
    }

    private fun saveProfile() {
        val uid = auth.currentUser?.uid ?: return

        // 입력값 수집/검증
        val name = etName.text?.toString()?.trim().orEmpty()
        val email = tvEmailR.text?.toString()?.trim().orEmpty()
        val handed = ddHanded.text?.toString()?.trim().orEmpty()
        val d = etCarryDriver.text?.toString()?.toIntOrNull()
        val i7 = etCarry7i.text?.toString()?.toIntOrNull()
        val pw = etCarryPW.text?.toString()?.toIntOrNull()
        val skillType = ddSkillType.text?.toString()?.trim().orEmpty()
        val skillValue = etSkillValue.text?.toString()?.toFloatOrNull()
        val freq = ddPracticeFreq.text?.toString()?.trim().orEmpty()
        val envs = getSelectedEnvironments()

        val dLoft = etDriverLoft.text?.toString()?.toFloatOrNull() ?: 10.5f
        val i7Loft = et7iLoft.text?.toString()?.toFloatOrNull() ?: 32f
        val wLofts = etWedgeLofts.text?.toString()
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.isNotEmpty() } ?: listOf(52, 56)

        if (name.isBlank()) {
            toast("이름을 입력하세요."); return
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            toast("이메일 형식이 올바르지 않습니다."); return
        }
        if (handed.isBlank()) {
            toast("손잡이를 선택하세요."); return
        }
        if (d == null || i7 == null || pw == null) {
            toast("드라이버/7i/PW 캐리를 모두 입력하세요."); return
        }
        if (d !in 80..380 || i7 !in 50..220 || pw !in 40..160) {
            toast("비정상 범위의 캐리 값이 있습니다."); return
        }
        if (skillType != "레인지 전용(모름)" && etSkillValue.isEnabled && skillValue == null) {
            toast("실력 지표의 값을 입력하세요."); return
        }

        val woods = mapOf("3W" to max(0, d - 20), "5W" to max(0, d - 35))
        val irons = computeIronDistances(i7)
        val wedges = computeWedgeDistances(pw)

        val profile = hashMapOf(
            "name" to name,
            "email" to email,
            "uid" to uid,
            "handedness" to handed,
            "carries" to mapOf(
                "driver" to d,
                "7i" to i7,
                "pw" to pw,
                "auto" to mapOf(
                    "woods" to woods,
                    "irons" to irons,
                    "wedges" to wedges
                )
            ),
            "skill" to mapOf(
                "type" to skillType,
                "value" to (skillValue ?: -1f)
            ),
            "practice" to mapOf(
                "frequency" to freq,
                "environments" to envs
            ),
            "equipment" to mapOf(
                "driver_loft_deg" to dLoft,
                "seven_iron_loft_deg" to i7Loft,
                "wedge_lofts" to wLofts
            )
        )

        progress.setMessage("저장 중…")
        progress.show()

        scope.launch {
            try {
                db.getReference("users").child(uid).setValue(profile).await()
                withContext(Dispatchers.Main) {
                    toast("저장되었습니다.")
                    // 저장 후 보기 탭 갱신 위해 다시 로드
                    loadProfile()
                    tabLayout.getTabAt(0)?.select()
                }
            } catch (e: Exception) {
                Log.e(TAG, "saveProfile failed", e)
                withContext(Dispatchers.Main) { toast("저장 실패: ${e.message}") }
            } finally {
                withContext(Dispatchers.Main) { progress.dismiss() }
            }
        }
    }

    private fun getSelectedEnvironments(): List<String> {
        val list = mutableListOf<String>()
        fun addIfChecked(id: Int, label: String) {
            val chip = findViewById<Chip>(id)
            if (chip.isChecked) list.add(label)
        }
        addIfChecked(R.id.chipIndoorScreenS, "실내(스크린)")
        addIfChecked(R.id.chipIndoorRangeS, "실내 연습장")
        addIfChecked(R.id.chipOutdoorRangeS, "실외 연습장")
        addIfChecked(R.id.chipFieldS, "필드")
        return list
    }

    private fun computeIronDistances(i7: Int): Map<String, Int> {
        val map = linkedMapOf<String, Int>()
        map["3i"] = i7 + 40
        map["4i"] = i7 + 30
        map["5i"] = i7 + 20
        map["6i"] = i7 + 10
        map["7i"] = i7
        map["8i"] = i7 - 10
        map["9i"] = i7 - 20
        return map
    }

    private fun computeWedgeDistances(pw: Int): Map<String, Int> {
        val map = linkedMapOf<String, Int>()
        map["PW"] = pw
        map["50°"] = (pw - 10).coerceAtLeast(0)
        map["52°"] = (pw - 15).coerceAtLeast(0)
        map["56°"] = (pw - 25).coerceAtLeast(0)
        map["60°"] = (pw - 35).coerceAtLeast(0)
        return map
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}