package com.example.swingfit

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import android.util.Patterns
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class RegisterActivity : AppCompatActivity() {

    private lateinit var etName: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText

    private lateinit var ddHandedness: AutoCompleteTextView
    private lateinit var etCarryDriver: TextInputEditText
    private lateinit var etCarry7i: TextInputEditText
    private lateinit var etCarryPW: TextInputEditText

    private lateinit var ddSkillType: AutoCompleteTextView
    private lateinit var etSkillValue: TextInputEditText

    private lateinit var ddPracticeFreq: AutoCompleteTextView
    private lateinit var chipGroupEnv: com.google.android.material.chip.ChipGroup

    private lateinit var etDriverLoft: TextInputEditText
    private lateinit var et7iLoft: TextInputEditText
    private lateinit var etWedgeLofts: TextInputEditText

    private lateinit var tvAutoDistances: TextView
    private lateinit var btnRegister: Button

    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase
    private lateinit var progressDialog: ProgressDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        initializeViews()
        setupDropdowns()
        setupAutoDistancePreview()
        setupListeners()
    }

    private fun initializeViews() {
        etName = findViewById(R.id.etName)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)

        ddHandedness = findViewById(R.id.ddHandedness)
        etCarryDriver = findViewById(R.id.etCarryDriver)
        etCarry7i = findViewById(R.id.etCarry7i)
        etCarryPW = findViewById(R.id.etCarryPW)

        ddSkillType = findViewById(R.id.ddSkillType)
        etSkillValue = findViewById(R.id.etSkillValue)

        ddPracticeFreq = findViewById(R.id.ddPracticeFreq)
        chipGroupEnv = findViewById(R.id.chipGroupEnv)

        etDriverLoft = findViewById(R.id.etDriverLoft)
        et7iLoft = findViewById(R.id.et7iLoft)
        etWedgeLofts = findViewById(R.id.etWedgeLofts)

        tvAutoDistances = findViewById(R.id.tvAutoDistances)
        btnRegister = findViewById(R.id.btnRegister)

        progressDialog = ProgressDialog(this).apply {
            setMessage("회원가입 중..")
            setCancelable(false)
        }
    }

    private fun setupDropdowns() {
        ddHandedness.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                listOf("오른손", "왼손")
            )
        )
        ddSkillType.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                listOf("핸디캡", "18홀 평균 타수", "레인지 전용(모름)")
            )
        )
        ddPracticeFreq.setAdapter(
            ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                listOf("주 0회", "주 1회", "주 2회", "주 3회", "주 4회", "주 5회", "주 6회", "주 7회 이상")
            )
        )

        ddSkillType.setOnItemClickListener { _, _, position, _ ->
            val selected = when (position) {
                0 -> "핸디캡"
                1 -> "18홀 평균 타수"
                else -> "레인지 전용(모름)"
            }
            // 값 입력필드 활성/비활성
            etSkillValue.isEnabled = selected != "레인지 전용(모름)"
            if (!etSkillValue.isEnabled) etSkillValue.setText("")
        }
    }

    private fun setupAutoDistancePreview() {
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateAutoDistances()
            }
        }
        etCarryDriver.addTextChangedListener(watcher)
        etCarry7i.addTextChangedListener(watcher)
        etCarryPW.addTextChangedListener(watcher)
    }

    private fun updateAutoDistances() {
        val d = etCarryDriver.text?.toString()?.toIntOrNull()
        val i7 = etCarry7i.text?.toString()?.toIntOrNull()
        val pw = etCarryPW.text?.toString()?.toIntOrNull()

        if (d == null || i7 == null || pw == null) {
            tvAutoDistances.text = "드라이버/7i/PW 입력 시 자동 생성"
            return
        }

        val woods = mapOf(
            "3W" to (d - 20).coerceAtLeast(0),
            "5W" to (d - 35).coerceAtLeast(0)
        )

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

    private fun computeIronDistances(i7: Int): Map<String, Int> {
        // 1클럽 번호 차이당 10m 룰 적용 (사용자 요청)
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

    private fun setupListeners() {
        btnRegister.setOnClickListener {
            val name = etName.text?.toString()?.trim().orEmpty()
            val email = etEmail.text?.toString()?.trim().orEmpty()
            val password = etPassword.text?.toString()?.trim().orEmpty()

            val handedness = ddHandedness.text?.toString()?.trim().orEmpty()
            val carryDriver = etCarryDriver.text?.toString()?.toIntOrNull()
            val carry7i = etCarry7i.text?.toString()?.toIntOrNull()
            val carryPW = etCarryPW.text?.toString()?.toIntOrNull()

            val skillType = ddSkillType.text?.toString()?.trim().orEmpty()
            val skillValue = etSkillValue.text?.toString()?.trim()

            val practiceFreq = ddPracticeFreq.text?.toString()?.trim().orEmpty()
            val envs = getSelectedEnvironments()

            val driverLoft = etDriverLoft.text?.toString()?.toFloatOrNull() ?: 10.5f
            val sevenILoft = et7iLoft.text?.toString()?.toFloatOrNull() ?: 32f
            val wedgeLofts = etWedgeLofts.text?.toString()?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: listOf(52, 56)

            if (!validateInput(name, email, password, handedness, carryDriver, carry7i, carryPW, skillType, skillValue)) return@setOnClickListener

            btnRegister.isEnabled = false
            progressDialog.show()

            registerUser(
                name = name,
                email = email,
                password = password,
                handedness = handedness,
                carryDriver = carryDriver!!,
                carry7i = carry7i!!,
                carryPW = carryPW!!,
                skillType = skillType,
                skillValue = skillValue?.toFloatOrNull(),
                practiceFreq = practiceFreq,
                environments = envs,
                driverLoft = driverLoft,
                sevenILoft = sevenILoft,
                wedgeLofts = wedgeLofts
            )
        }
    }

    private fun getSelectedEnvironments(): List<String> {
        val list = mutableListOf<String>()
        fun addIfChecked(id: Int, label: String) {
            val chip = findViewById<Chip>(id)
            if (chip.isChecked) list.add(label)
        }
        addIfChecked(R.id.chipIndoorScreen, "실내(스크린)")
        addIfChecked(R.id.chipIndoorRange, "실내 연습장")
        addIfChecked(R.id.chipOutdoorRange, "실외 연습장")
        addIfChecked(R.id.chipField, "필드")
        return list
    }

    private fun validateInput(
        name: String,
        email: String,
        password: String,
        handedness: String,
        carryDriver: Int?,
        carry7i: Int?,
        carryPW: Int?,
        skillType: String,
        skillValue: String?
    ): Boolean {
        if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "이름/이메일/비밀번호를 모두 입력해주세요.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            Toast.makeText(this, "올바른 이메일 형식을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!isValidPassword(password)) {
            Toast.makeText(this, "비밀번호는 최소 8자이며 특수문자 1개 이상 포함해야 합니다.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (handedness.isEmpty()) {
            Toast.makeText(this, "골프 손잡이를 선택해주세요.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (carryDriver == null || carry7i == null || carryPW == null) {
            Toast.makeText(this, "드라이버/7i/PW 캐리를 모두 입력해주세요.", Toast.LENGTH_SHORT).show()
            return false
        }
        if (skillType != "레인지 전용(모름)" && (skillValue.isNullOrBlank())) {
            Toast.makeText(this, "실력 지표의 값을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return false
        }
        // 합리적 범위 검증
        if (carryDriver !in 80..380 || carry7i !in 50..220 || carryPW !in 40..160) {
            Toast.makeText(this, "비정상 범위의 캐리 값이 있습니다. 확인해주세요.", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun isValidPassword(password: String): Boolean {
        val specialChars = "!@#$%^&*()-_=+[]{}|;:',.<>?/"
        return password.length >= 8 && password.any { it in specialChars }
    }

    private fun registerUser(
        name: String,
        email: String,
        password: String,
        handedness: String,
        carryDriver: Int,
        carry7i: Int,
        carryPW: Int,
        skillType: String,
        skillValue: Float?,
        practiceFreq: String,
        environments: List<String>,
        driverLoft: Float,
        sevenILoft: Float,
        wedgeLofts: List<Int>
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val authResult = auth.createUserWithEmailAndPassword(email, password).await()
                val user = authResult.user
                user?.let {
                    val userRef = database.getReference("users").child(it.uid)

                    val woods = mapOf(
                        "3W" to (carryDriver - 20).coerceAtLeast(0),
                        "5W" to (carryDriver - 35).coerceAtLeast(0)
                    )
                    val irons = computeIronDistances(carry7i)
                    val wedges = computeWedgeDistances(carryPW)

                    val profile = hashMapOf(
                        "name" to name,
                        "email" to email,
                        "uid" to it.uid,
                        "handedness" to handedness,
                        "carries" to mapOf(
                            "driver" to carryDriver,
                            "7i" to carry7i,
                            "pw" to carryPW,
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
                            "frequency" to practiceFreq,
                            "environments" to environments
                        ),
                        "equipment" to mapOf(
                            "driver_loft_deg" to driverLoft,
                            "seven_iron_loft_deg" to sevenILoft,
                            "wedge_lofts" to wedgeLofts
                        )
                    )

                    userRef.setValue(profile).await()
                }
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    Toast.makeText(this@RegisterActivity, "회원가입이 완료되었습니다.", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this@RegisterActivity, LoginActivity::class.java))
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    btnRegister.isEnabled = true
                    Toast.makeText(this@RegisterActivity, "회원가입에 실패했습니다: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}