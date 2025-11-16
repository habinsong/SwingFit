package com.example.swingfit

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_splash)

        // 시스템 바 인셋 반영
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 1초 후 로그인 화면으로 공유 요소 전환
        Handler(Looper.getMainLooper()).postDelayed({
            val logo = findViewById<ImageView>(R.id.imageView) // activity_splash.xml의 로고(ImageView)
            val intent = Intent(this, LoginActivity::class.java)

            // 공유 요소 전환: 두 레이아웃의 ImageView에 동일한 transitionName("logo_shared") 필요
            val options = ActivityOptionsCompat.makeSceneTransitionAnimation(
                this,
                logo,
                "logo_shared"
            )

            startActivity(intent, options.toBundle())
            finish()
        }, 1000)
    }
}