package com.example.view

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.BallAnalysis
import com.example.model.RecordsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 비거리 기록 화면 전용 ViewModel
 */
class BallRecordsViewModel(
    private val repo: RecordsRepository = RecordsRepository()
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state

    sealed class UiState {
        object Loading : UiState()
        data class Success(val list: List<BallAnalysis>) : UiState()
        data class Error(val msg: String) : UiState()
        object Empty : UiState()
    }

    fun loadRecords(limit: Long = 50) {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                val list = repo.getBallAnalyses(limit)
                _state.value = if (list.isEmpty()) {
                    UiState.Empty
                } else {
                    UiState.Success(list)
                }
            } catch (e: Exception) {
                _state.value = UiState.Error(e.message ?: "알 수 없는 오류")
            }
        }
    }
}