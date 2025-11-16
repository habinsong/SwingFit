package com.example.swingfit

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnPreDraw
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.safetynet.SafetyNetAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvRegister: TextView
    private lateinit var cbAutoLogin: CheckBox
    private lateinit var progressBar: ProgressBar
    private lateinit var tvLoadingMessage: TextView
    private lateinit var ivLogo: ImageView

    private lateinit var auth: FirebaseAuth
    private lateinit var database: FirebaseDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        // 공유 요소(로고) 준비될 때까지 전환 지연
        supportPostponeEnterTransition()

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Firebase init
        FirebaseApp.initializeApp(this)
        val appCheck = FirebaseAppCheck.getInstance()
        appCheck.installAppCheckProviderFactory(SafetyNetAppCheckProviderFactory.getInstance())

        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance()

        bindViews()

        // 로고 뷰가 실제로 그려진 뒤에 Enter Transition 시작 (Shared Element 끊김 방지)
        ivLogo.doOnPreDraw {
            supportStartPostponedEnterTransition()
        }

        wireAutoLoginAndUi()
    }

    private fun bindViews() {
        ivLogo = findViewById(R.id.imageView3)          // 로그인 레이아웃의 로고(ImageView) — transitionName="logo_shared" 필수
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnLogin = findViewById(R.id.btnLogin)
        tvRegister = findViewById(R.id.tvRegister)
        cbAutoLogin = findViewById(R.id.cbAutoLogin)
        progressBar = findViewById(R.id.progressBar)
        tvLoadingMessage = findViewById(R.id.tvLoadingMessage)
    }

    private fun wireAutoLoginAndUi() {
        val prefs = getSharedPreferences(Prefs.PREFS, MODE_PRIVATE)
        val savedEmail = prefs.getString(Prefs.KEY_EMAIL, null)
        val savedPassword = prefs.getString(Prefs.KEY_PASSWORD, null)
        val isAutoLogin = prefs.getBoolean(Prefs.KEY_AUTO_LOGIN, false)

        cbAutoLogin.isChecked = isAutoLogin

        // 자동로그인 조건 충족 시 즉시 시도
        if (isAutoLogin && !savedEmail.isNullOrBlank() && !savedPassword.isNullOrBlank()) {
            showLoading()
            loginUser(savedEmail, savedPassword)
        }

        btnLogin.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "이메일과 비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 체크박스 상태에 따라 저장/삭제
            prefs.edit().apply {
                putBoolean(Prefs.KEY_AUTO_LOGIN, cbAutoLogin.isChecked)
                if (cbAutoLogin.isChecked) {
                    putString(Prefs.KEY_EMAIL, email)
                    putString(Prefs.KEY_PASSWORD, password)
                } else {
                    remove(Prefs.KEY_EMAIL)
                    remove(Prefs.KEY_PASSWORD)
                }
            }.apply()

            loginUser(email, password)
        }

        cbAutoLogin.setOnCheckedChangeListener { _, checked ->
            // 체크 상태만 즉시 반영 (자격 증명은 로그인 시점에서 저장/삭제)
            prefs.edit().putBoolean(Prefs.KEY_AUTO_LOGIN, checked).apply()
            if (!checked) {
                // 체크 해제 시 저장되어 있던 자격 증명 제거
                prefs.edit().remove(Prefs.KEY_EMAIL).remove(Prefs.KEY_PASSWORD).apply()
            }
        }

        tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun loginUser(email: String, password: String) {
        showLoading()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val authResult = auth.signInWithEmailAndPassword(email, password).await()
                val user = authResult.user
                if (user != null) {
                    withContext(Dispatchers.Main) {
                        hideLoading()
                        startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                        finish()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        hideLoading()
                        Toast.makeText(this@LoginActivity, "로그인 실패: 사용자 정보를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideLoading()
                    Toast.makeText(this@LoginActivity, "로그인 실패: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
        tvLoadingMessage.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        progressBar.visibility = View.GONE
        tvLoadingMessage.visibility = View.GONE
    }
}