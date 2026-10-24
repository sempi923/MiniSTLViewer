package com.ministl.viewer.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ministl.viewer.model.MeshAnalysis
import com.ministl.viewer.model.ModelTransform
import com.ministl.viewer.model.StlMesh
import com.ministl.viewer.parser.StlParser
import com.ministl.viewer.util.MeshMath
import com.ministl.viewer.util.calculatePhysicalProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UiState {
    data object Empty : UiState
    data class Loading(val progress: Float = 0f) : UiState
    data class Success(
        val mesh: StlMesh,
        val analysis: MeshAnalysis? = null,
        val isAnalyzing: Boolean = false
    ) : UiState
    data class Error(val message: String) : UiState
}

class MainViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<UiState>(UiState.Empty)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var parseJob: Job? = null
    private var analysisJob: Job? = null

    fun loadStlFromUri(contentResolver: ContentResolver, uri: Uri) {
        parseJob?.cancel()
        analysisJob?.cancel()
        parseJob = viewModelScope.launch {
            _uiState.value = UiState.Loading(0f)
            try {
                val mesh = StlParser.parse(contentResolver, uri) { progress ->
                    _uiState.value = UiState.Loading(progress)
                }
                _uiState.value = UiState.Success(mesh = mesh, analysis = null, isAnalyzing = true)
                recomputeAnalysis(mesh, ModelTransform(), 45f)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.localizedMessage ?: "Error al procesar el archivo STL")
            }
        }
    }

    fun loadSampleCalibrationMesh() {
        parseJob?.cancel()
        analysisJob?.cancel()
        parseJob = viewModelScope.launch {
            _uiState.value = UiState.Loading(0.35f)
            try {
                val mesh = MeshMath.generateCalibrationArchMesh()
                _uiState.value = UiState.Success(mesh = mesh, analysis = null, isAnalyzing = true)
                recomputeAnalysis(mesh, ModelTransform(), 45f)
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Error al generar pieza de calibración")
            }
        }
    }

    fun updateAnalysisForTransform(transform: ModelTransform, overhangAngleDeg: Float = 45f) {
        val current = _uiState.value
        if (current is UiState.Success) {
            recomputeAnalysis(current.mesh, transform, overhangAngleDeg)
        }
    }

    private fun recomputeAnalysis(
        mesh: StlMesh,
        transform: ModelTransform,
        overhangAngleDeg: Float
    ) {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            val current = _uiState.value
            if (current is UiState.Success) {
                _uiState.value = current.copy(isAnalyzing = true)
            }
            val result = mesh.calculatePhysicalProperties(transform, overhangAngleDeg)
            val after = _uiState.value
            if (after is UiState.Success && after.mesh === mesh) {
                _uiState.value = after.copy(analysis = result, isAnalyzing = false)
            }
        }
    }
}
