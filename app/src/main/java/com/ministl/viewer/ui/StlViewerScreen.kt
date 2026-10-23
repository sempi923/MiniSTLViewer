package com.ministl.viewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ministl.viewer.renderer.StlGLSurfaceView
import java.util.Locale

@Composable
fun StlViewerScreen(
    uiState: UiState,
    onOpenFileClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    var isWireframe by remember { mutableStateOf(false) }
    var showInfoPanel by remember { mutableStateOf(true) }
    var glSurfaceViewRef by remember { mutableStateOf<StlGLSurfaceView?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Visor 3D OpenGL ES 2.0
        AndroidView(
            factory = { context ->
                StlGLSurfaceView(context).also { glView ->
                    glSurfaceViewRef = glView
                }
            },
            update = { glView ->
                glView.renderer.isDarkMode = isDark
                glView.renderer.isWireframe = isWireframe
                glView.requestRender()
            },
            modifier = Modifier.fillMaxSize()
        )

        // Enviar la malla al hilo de OpenGL cuando cambie el estado
        LaunchedEffect(uiState) {
            if (uiState is UiState.Success) {
                glSurfaceViewRef?.let { glView ->
                    glView.queueEvent {
                        glView.renderer.setMesh(uiState.mesh)
                    }
                    glView.requestRender()
                }
            }
        }

        // Estados de Carga / Vacío / Error
        when (uiState) {
            is UiState.Empty -> {
                ElevatedCard(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("MiniSTL Viewer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Selecciona un modelo 3D en formato .STL para comenzar.")
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onOpenFileClick) {
                            Text("Cargar archivo STL")
                        }
                    }
                }
            }
            is UiState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            is UiState.Error -> {
                ElevatedCard(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(uiState.message, color = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onOpenFileClick) { Text("Reintentar") }
                    }
                }
            }
            is UiState.Success -> {
                // Panel Informativo Retraíble (Arriba a la izquierda)
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(16.dp)
                ) {
                    FilledTonalButton(onClick = { showInfoPanel = !showInfoPanel }) {
                        Text(if (showInfoPanel) "Ocultar Info ▲" else "Info del Modelo ▼")
                    }

                    AnimatedVisibility(visible = showInfoPanel) {
                        val mesh = uiState.mesh
                        val bbox = mesh.boundingBox
                        Surface(
                            modifier = Modifier.padding(top = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 6.dp
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "Formato: ${if (mesh.isBinary) "Binario" else "ASCII"}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text = "Triángulos: ${String.format(Locale.US, "%,d", mesh.triangleCount)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = String.format(
                                        Locale.US,
                                        "Dimensiones (mm):\nX (Ancho): %.2f\nY (Alto): %.2f\nZ (Prof.): %.2f",
                                        bbox.widthX, bbox.heightY, bbox.depthZ
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }

        // Barra de herramientas flotante inferior
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 8.dp,
            shadowElevation = 6.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onOpenFileClick) {
                    Text("Cargar STL")
                }

                OutlinedButton(
                    onClick = { isWireframe = !isWireframe }
                ) {
                    Text(if (isWireframe) "Modo: Alambre" else "Modo: Sólido")
                }

                FilledTonalButton(
                    onClick = {
                        glSurfaceViewRef?.let { glView ->
                            glView.renderer.resetCamera()
                            glView.requestRender()
                        }
                    }
                ) {
                    Text("Centrar")
                }
            }
        }
    }
}
