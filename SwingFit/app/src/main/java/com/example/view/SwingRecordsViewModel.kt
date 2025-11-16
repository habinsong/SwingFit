package com.example.view

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.RecordsRepository
import com.example.model.SwingAnalysis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 스윙 기록 화면 전용 ViewModel
 */
class SwingRecordsViewModel(
    private val repo: RecordsRepository = RecordsRepository()
) : ViewModel() {

    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state

    sealed class UiState {
        object Loading : UiState()
        data class Success(val list: List<SwingAnalysis>) : UiState()
        data class Error(val msg: String) : UiState()
        object Empty : UiState()
    }

    fun loadRecords(limit: Long = 50) {
        _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                val list = repo.getSwingAnalyses(limit)
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