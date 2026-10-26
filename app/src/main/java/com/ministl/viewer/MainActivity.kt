package com.ministl.viewer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.ministl.viewer.ui.MainViewModel
import com.ministl.viewer.ui.StlViewerScreen
import com.ministl.viewer.ui.theme.MiniSTLTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Si la app se abrió desde un gestor de archivos externo
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            MiniSTLTheme {
                val uiState by viewModel.uiState.collectAsState()

                val openDocumentLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri ->
                    uri?.let { viewModel.loadStlFromUri(applicationContext, it) }
                }

                StlViewerScreen(
                    uiState = uiState,
                    onOpenFileClick = {
                        // Acepta tipos MIME de STL, 3MF y binarios sin pedir permisos de almacenamiento
                        openDocumentLauncher.launch(
                            arrayOf(
                                "model/stl",
                                "model/3mf",
                                "application/vnd.ms-package.3dmanufacturing-3dmodel+xml",
                                "application/sla",
                                "application/vnd.ms-pki.stl",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onLoadSampleClick = {
                        viewModel.loadSampleCalibrationMesh()
                    },
                    onTransformOrThresholdChanged = { transform, thresholdDeg ->
                        viewModel.updateAnalysisForTransform(transform, thresholdDeg)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                try {
                    viewModel.loadStlFromUri(applicationContext, uri)
                } catch (_: SecurityException) {
                    // Prevenir caída de la app si el intent proviene de un origen sin permisos
                } catch (_: Exception) {
                    // Los errores de lectura del archivo se canalizan a través del UiState del ViewModel
                }
            }
        }
    }
}
