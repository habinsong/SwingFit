package com.example.swingfit

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.swingfit.databinding.ActivityRecordsBinding
import com.example.fragment.BallRecordsFragment
import com.example.fragment.SwingRecordsFragment
import com.google.android.material.tabs.TabLayoutMediator
import com.example.adapter.RecordsPagerAdapter

class RecordsActivity : AppCompatActivity() {

    private lateinit var b: ActivityRecordsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRecordsBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 툴바 뒤로가기 버튼 동작
        setSupportActionBar(b.toolbarRecords)
        b.toolbarRecords.setNavigationOnClickListener { finish() }

        // ViewPager2 어댑터 연결
        val fragments = listOf(
            BallRecordsFragment(),
            SwingRecordsFragment()
        )
        val titles = listOf("비거리", "스윙")

        val adapter = RecordsPagerAdapter(this, fragments)
        b.viewPagerRecords.adapter = adapter

        // 탭과 ViewPager2 연결
        TabLayoutMediator(b.tabLayoutRecords, b.viewPagerRecords) { tab, position ->
            tab.text = titles[position]
        }.attach()
    }
}