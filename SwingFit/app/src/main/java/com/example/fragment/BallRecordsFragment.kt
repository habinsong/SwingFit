package com.example.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.adapter.BallAnalysisAdapter
import com.example.model.BallAnalysis
import com.example.swingfit.RecordDetailActivity
import com.example.swingfit.databinding.FragmentRecordsListBinding
import com.example.view.BallRecordsViewModel
import kotlinx.coroutines.launch

import android.util.Log
import com.google.firebase.auth.FirebaseAuth


class BallRecordsFragment : Fragment() {

    private var _b: FragmentRecordsListBinding? = null
    private val b get() = _b!!

    private val vm: BallRecordsViewModel by viewModels()

    private lateinit var adapter: BallAnalysisAdapter
    private val items = mutableListOf<BallAnalysis>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = FragmentRecordsListBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // RecyclerView
        adapter = BallAnalysisAdapter(items) { record ->
            // 상세 화면 이동 (공용 상세)
            val ctx = requireContext()
            val intent = Intent(ctx, RecordDetailActivity::class.java).apply {
                putExtra("type", "distance")              // 공용 상세에서 구분용
                putExtra("docId", record.id)              // 필요 시 상세 조회에 사용
                putExtra("createdAt", record.createdAt)
                putExtra("thumbnailUri", record.thumbnailUri)
                // 필요하면 주요 값도 넘겨서 즉시 렌더 후, 상세에서 추가 로딩
                putExtra("totalDistance", record.totalDistance)
                putExtra("carryDistance", record.carryDistance)
                putExtra("launchAngle", record.launchAngle)
                putExtra("ballSpeed", record.ballSpeed)
                putExtra("backspin", record.backspin)
                putExtra("club", record.club)
                putExtra("environment", record.environment)
                putExtra("smashFactor", record.smashFactor)
                putExtra("apexHeight", record.apexHeight)
            }
            startActivity(intent)
        }
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        // Pull-to-Refresh
        b.swipeRefresh.setOnRefreshListener { vm.loadRecords() }

        // 에러 재시도 버튼
        b.viewError.btnRetry.setOnClickListener { vm.loadRecords() }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        Log.d("Records", "BallRecordsFragment uid=$uid path=users/$uid/ball-analyses")

        // State 수집
        collectState()

        // 최초 로드
        vm.loadRecords()
    }

    private fun collectState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    when (state) {
                        is BallRecordsViewModel.UiState.Loading -> {
                            b.topProgress.visibility = View.VISIBLE
                            b.viewEmpty.root.visibility = View.GONE
                            b.viewError.root.visibility = View.GONE
                        }
                        is BallRecordsViewModel.UiState.Success -> {
                            b.topProgress.visibility = View.GONE
                            b.viewEmpty.root.visibility = View.GONE
                            b.viewError.root.visibility = View.GONE
                            b.swipeRefresh.isRefreshing = false

                            items.clear()
                            items.addAll(state.list)
                            adapter.notifyDataSetChanged()
                        }
                        is BallRecordsViewModel.UiState.Empty -> {
                            b.topProgress.visibility = View.GONE
                            b.viewEmpty.root.visibility = View.VISIBLE
                            b.viewError.root.visibility = View.GONE
                            b.swipeRefresh.isRefreshing = false

                            items.clear()
                            adapter.notifyDataSetChanged()
                        }
                        is BallRecordsViewModel.UiState.Error -> {
                            b.topProgress.visibility = View.GONE
                            b.viewEmpty.root.visibility = View.GONE
                            b.viewError.root.visibility = View.VISIBLE
                            b.swipeRefresh.isRefreshing = false
                        }
                    }
                }
            }
        }
    }



    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}