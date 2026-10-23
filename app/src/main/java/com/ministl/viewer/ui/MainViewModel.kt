package com.ministl.viewer.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ministl.viewer.model.StlMesh
import com.ministl.viewer.parser.StlParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Empty : UiState
    data object Loading : UiState
    data class Success(val mesh: StlMesh) : UiState
    data class Error(val message: String) : UiState
}

class MainViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Empty)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var parseJob: Job? = null

    fun loadStlFromUri(contentResolver: ContentResolver, uri: Uri) {
        parseJob?.cancel()
        parseJob = viewModelScope.launch {
            _uiState.value = UiState.Loading
            try {
                val mesh = StlParser.parse(contentResolver, uri)
                _uiState.value = UiState.Success(mesh)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.localizedMessage ?: "Error al procesar el archivo STL")
            }
        }
    }
}
