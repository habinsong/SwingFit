package com.example.fragment

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.adapter.SwingAnalysisAdapter
import com.example.model.SwingAnalysis
import com.example.swingfit.RecordDetailActivity
import com.example.swingfit.databinding.FragmentRecordsListBinding
import com.example.view.SwingRecordsViewModel
import kotlinx.coroutines.launch

class SwingRecordsFragment : Fragment() {

    private var _b: FragmentRecordsListBinding? = null
    private val b get() = _b!!

    private val vm: SwingRecordsViewModel by viewModels()

    private lateinit var adapter: SwingAnalysisAdapter
    private val items = mutableListOf<SwingAnalysis>()

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
        adapter = SwingAnalysisAdapter(items) { record ->
            val ctx = requireContext()
            val intent = Intent(ctx, RecordDetailActivity::class.java).apply {
                putExtra("type", "swing")
                putExtra("docId", record.id)
                putExtra("createdAt", record.createdAt)
                putExtra("club", record.club)
                putExtra("environment", record.environment)

                // 문자열 분석 결과들
                putExtra("swingType", record.swingType)
                putExtra("overallFeedback", record.overallFeedback)
                putExtra("takeaway", record.takeaway)
                putExtra("transition", record.transition)
                putExtra("impact", record.impact)
                putExtra("followThrough", record.followThrough)
                putExtra("keyStrength", record.keyStrength)
                putExtra("improvement", record.improvement)

                // 🔸 이미지 경로는 널 가드 후 넣기 (둘 다 존재하면 둘 다 넣기)
                record.imageUriLocal?.let { putExtra("imageUriLocal", it) }
                record.thumbnailUri?.let { putExtra("thumbnailUri", it) }

                // 🔸 content:// URI 썸네일을 다른 액티비티에서 바로 읽을 수 있게 권한 전달
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }
        b.recycler.layoutManager = LinearLayoutManager(requireContext())
        b.recycler.adapter = adapter

        // Pull-to-Refresh
        b.swipeRefresh.setOnRefreshListener { vm.loadRecords() }

        // 에러 재시도 버튼
        b.viewError.btnRetry.setOnClickListener { vm.loadRecords() }

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
                        is SwingRecordsViewModel.UiState.Loading -> {
                            b.topProgress.visibility = View.VISIBLE
                            b.viewEmpty.root.visibility = View.GONE
                            b.viewError.root.visibility = View.GONE
                        }
                        is SwingRecordsViewModel.UiState.Success -> {
                            b.topProgress.visibility = View.GONE
                            b.viewEmpty.root.visibility = View.GONE
                            b.viewError.root.visibility = View.GONE
                            b.swipeRefresh.isRefreshing = false

                            items.clear()
                            items.addAll(state.list)
                            adapter.notifyDataSetChanged()

                            if (items.isEmpty()) {
                                b.viewEmpty.root.visibility = View.VISIBLE
                            }
                        }
                        is SwingRecordsViewModel.UiState.Empty -> {
                            b.topProgress.visibility = View.GONE
                            b.viewEmpty.root.visibility = View.VISIBLE
                            b.viewError.root.visibility = View.GONE
                            b.swipeRefresh.isRefreshing = false

                            items.clear()
                            adapter.notifyDataSetChanged()
                        }
                        is SwingRecordsViewModel.UiState.Error -> {
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