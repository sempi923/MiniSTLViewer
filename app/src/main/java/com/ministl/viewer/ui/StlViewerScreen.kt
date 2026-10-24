package com.ministl.viewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ministl.viewer.model.*
import com.ministl.viewer.renderer.StlGLSurfaceView
import com.ministl.viewer.ui.theme.*
import com.ministl.viewer.util.MeshMath
import com.ministl.viewer.util.estimateFilamentCost
import com.ministl.viewer.util.estimateWeightGrams
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private enum class InspectorTab(val title: String) {
    MODEL("Modelo"),
    MATERIAL("Materiales"),
    ANALYSIS("Análisis"),
    TRANSFORM("Transformar")
}

@Composable
fun StlViewerScreen(
    uiState: UiState,
    onOpenFileClick: () -> Unit,
    onLoadSampleClick: () -> Unit = {},
    onTransformOrThresholdChanged: (ModelTransform, Float) -> Unit = { _, _ -> }
) {
    var glSurfaceViewRef by remember { mutableStateOf<StlGLSurfaceView?>(null) }

    // Estado de visualización y material
    var renderMode by remember { mutableStateOf(MeshRenderMode.SOLID) }
    var wireframeWidth by remember { mutableFloatStateOf(1.35f) }
    var showPrintBed by remember { mutableStateOf(true) }
    var selectedMaterial by remember { mutableStateOf(FilamentMaterial.PLA) }
    var selectedSwatch by remember { mutableStateOf(BambuFilamentPalette.first()) }
    var filamentPricePerKg by remember { mutableFloatStateOf(FilamentMaterial.PLA.defaultPricePerKg) }

    // Estado de herramientas de análisis
    var overhangInspector by remember { mutableStateOf(false) }
    var overhangThresholdDeg by remember { mutableFloatStateOf(45f) }
    var clippingEnabled by remember { mutableStateOf(false) }
    var clippingAxis by remember { mutableStateOf(ClippingAxis.Z) }
    var clippingProgress by remember { mutableFloatStateOf(1.0f) }

    // Estado de transformaciones del modelo
    var modelTransform by remember { mutableStateOf(ModelTransform()) }

    // Estado del Gizmo de Orientación 3D (sincronizado con StlRenderer)
    var camPitchX by remember { mutableFloatStateOf(CameraPreset.ISO.pitchX) }
    var camYawZ by remember { mutableFloatStateOf(CameraPreset.ISO.yawZ) }

    // Estado del Bottom Sheet modular y diálogo de privacidad
    var isSheetExpanded by remember { mutableStateOf(true) }
    var activeTab by remember { mutableStateOf(InspectorTab.MODEL) }
    var showPrivacyDialog by remember { mutableStateOf(false) }

    fun syncRendererAndRedraw() {
        glSurfaceViewRef?.let { glView ->
            val r = glView.renderer
            r.renderMode = renderMode
            r.wireframeWidth = wireframeWidth
            r.showPrintBed = showPrintBed
            r.materialPreset = selectedMaterial
            r.baseColorR = selectedSwatch.color.red
            r.baseColorG = selectedSwatch.color.green
            r.baseColorB = selectedSwatch.color.blue
            r.overhangInspectorEnabled = overhangInspector
            r.overhangAngleThresholdDeg = overhangThresholdDeg
            r.clippingEnabled = clippingEnabled
            r.clippingAxis = clippingAxis
            r.clippingProgress = clippingProgress
            r.modelTransform = modelTransform
            glView.requestRender()
        }
    }

    // Enviar la malla al hilo OpenGL cuando se cargue un nuevo modelo
    LaunchedEffect(uiState) {
        if (uiState is UiState.Success) {
            glSurfaceViewRef?.let { glView ->
                glView.queueEvent {
                    glView.renderer.setMesh(uiState.mesh)
                    camPitchX = glView.renderer.rotX
                    camYawZ = glView.renderer.rotY
                }
                glView.requestRender()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BambuGraphiteBg)
    ) {
        // 1. Lienzo Nativo OpenGL ES 2.0
        AndroidView(
            factory = { context ->
                StlGLSurfaceView(context).also { glView ->
                    glView.onCameraRotationChanged = { pitch, yaw ->
                        camPitchX = pitch
                        camYawZ = yaw
                    }
                    glSurfaceViewRef = glView
                }
            },
            update = { glView ->
                val r = glView.renderer
                r.renderMode = renderMode
                r.wireframeWidth = wireframeWidth
                r.showPrintBed = showPrintBed
                r.materialPreset = selectedMaterial
                r.baseColorR = selectedSwatch.color.red
                r.baseColorG = selectedSwatch.color.green
                r.baseColorB = selectedSwatch.color.blue
                r.overhangInspectorEnabled = overhangInspector
                r.overhangAngleThresholdDeg = overhangThresholdDeg
                r.clippingEnabled = clippingEnabled
                r.clippingAxis = clippingAxis
                r.clippingProgress = clippingProgress
                r.modelTransform = modelTransform
                glView.requestRender()
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Barra Superior Técnica + Gizmo 3D de Orientación XYZ (Esquina Superior Derecha)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Cabecera de Estado del Modelo (Izquierda)
            TopTechnicalHeader(
                uiState = uiState,
                onOpenFileClick = onOpenFileClick,
                onLoadSampleClick = onLoadSampleClick,
                onPrivacyInfoClick = { showPrivacyDialog = true },
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Widget Gizmo 3D Interactivo + Vistas Ortogonales (Derecha)
            OrientationGizmoCard(
                pitchX = camPitchX,
                yawZ = camYawZ,
                onSelectPreset = { preset ->
                    glSurfaceViewRef?.let { glView ->
                        glView.renderer.applyCameraPreset(preset)
                        camPitchX = preset.pitchX
                        camYawZ = preset.yawZ
                        glView.requestRender()
                    }
                }
            )
        }

        // 3. Estados centrales: Empty / Loading con progreso real / Error
        when (uiState) {
            is UiState.Empty -> {
                EmptyWorkspacePrompt(
                    onOpenFileClick = onOpenFileClick,
                    onLoadSampleClick = onLoadSampleClick,
                    onPrivacyInfoClick = { showPrivacyDialog = true },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp)
                )
            }
            is UiState.Loading -> {
                LoadingProgressCard(
                    progress = uiState.progress,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp)
                )
            }
            is UiState.Error -> {
                ErrorDiagnosticCard(
                    message = uiState.message,
                    onRetryClick = onOpenFileClick,
                    onSampleClick = onLoadSampleClick,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 24.dp)
                )
            }
            is UiState.Success -> Unit
        }

        // 4. Contenedor Inferior: Floating Action Pills + Bottom Sheet Modular Retraíble
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Floating Action Pills (Acceso rápido con estética glassmorphism)
            FloatingActionPillsRow(
                showPrintBed = showPrintBed,
                onTogglePrintBed = {
                    showPrintBed = !showPrintBed
                    syncRendererAndRedraw()
                },
                renderMode = renderMode,
                onCycleRenderMode = {
                    renderMode = when (renderMode) {
                        MeshRenderMode.SOLID -> MeshRenderMode.SOLID_WIREFRAME
                        MeshRenderMode.SOLID_WIREFRAME -> MeshRenderMode.WIREFRAME
                        MeshRenderMode.WIREFRAME -> MeshRenderMode.SOLID
                    }
                    syncRendererAndRedraw()
                },
                overhangInspector = overhangInspector,
                onToggleOverhang = {
                    overhangInspector = !overhangInspector
                    syncRendererAndRedraw()
                },
                clippingEnabled = clippingEnabled,
                onToggleClipping = {
                    clippingEnabled = !clippingEnabled
                    if (clippingEnabled && clippingProgress >= 0.99f) {
                        clippingProgress = 0.55f
                    }
                    syncRendererAndRedraw()
                },
                onResetCamera = {
                    glSurfaceViewRef?.let { glView ->
                        glView.renderer.resetCamera()
                        camPitchX = glView.renderer.rotX
                        camYawZ = glView.renderer.rotY
                        glView.requestRender()
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom Sheet Modular (Modelo, Materiales, Análisis, Transformar)
            BambuModularBottomSheet(
                uiState = uiState,
                isExpanded = isSheetExpanded,
                onToggleExpanded = { isSheetExpanded = !isSheetExpanded },
                activeTab = activeTab,
                onSelectTab = { tab ->
                    activeTab = tab
                    isSheetExpanded = true
                },
                onOpenFileClick = onOpenFileClick,
                onLoadSampleClick = onLoadSampleClick,
                selectedMaterial = selectedMaterial,
                onSelectMaterial = { mat ->
                    selectedMaterial = mat
                    filamentPricePerKg = mat.defaultPricePerKg
                    syncRendererAndRedraw()
                },
                selectedSwatch = selectedSwatch,
                onSelectSwatch = { swatch ->
                    selectedSwatch = swatch
                    syncRendererAndRedraw()
                },
                renderMode = renderMode,
                onSelectRenderMode = { mode ->
                    renderMode = mode
                    syncRendererAndRedraw()
                },
                wireframeWidth = wireframeWidth,
                onWireframeWidthChange = { width ->
                    wireframeWidth = width
                    syncRendererAndRedraw()
                },
                filamentPricePerKg = filamentPricePerKg,
                onPricePerKgChange = { filamentPricePerKg = it },
                clippingEnabled = clippingEnabled,
                onClippingEnabledChange = { enabled ->
                    clippingEnabled = enabled
                    if (enabled && clippingProgress >= 0.99f) clippingProgress = 0.6f
                    syncRendererAndRedraw()
                },
                clippingAxis = clippingAxis,
                onClippingAxisChange = { axis ->
                    clippingAxis = axis
                    syncRendererAndRedraw()
                },
                clippingProgress = clippingProgress,
                onClippingProgressChange = { prog ->
                    clippingProgress = prog
                    syncRendererAndRedraw()
                },
                overhangInspector = overhangInspector,
                onOverhangInspectorChange = { enabled ->
                    overhangInspector = enabled
                    syncRendererAndRedraw()
                },
                overhangThresholdDeg = overhangThresholdDeg,
                onOverhangThresholdChange = { deg ->
                    overhangThresholdDeg = deg
                    syncRendererAndRedraw()
                    onTransformOrThresholdChanged(modelTransform, deg)
                },
                modelTransform = modelTransform,
                onModelTransformChange = { newTransform ->
                    val rotationChanged = newTransform.rotXDeg != modelTransform.rotXDeg ||
                        newTransform.rotYDeg != modelTransform.rotYDeg ||
                        newTransform.rotZDeg != modelTransform.rotZDeg
                    modelTransform = newTransform
                    syncRendererAndRedraw()
                    if (rotationChanged) {
                        onTransformOrThresholdChanged(newTransform, overhangThresholdDeg)
                    }
                }
            )
        }

        if (showPrivacyDialog) {
            AboutPrivacyDialog(onDismiss = { showPrivacyDialog = false })
        }
    }
}

@Composable
private fun TopTechnicalHeader(
    uiState: UiState,
    onOpenFileClick: () -> Unit,
    onLoadSampleClick: () -> Unit,
    onPrivacyInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = BambuGlassSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BambuBorderSubtle)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(BambuGreenBright)
                )
                Text(
                    text = "Rabbit3D Viewer",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = BambuGreenBright
                )
                Text(
                    text = "• STL / 3MF",
                    style = MaterialTheme.typography.labelSmall,
                    color = BambuTextSecondary
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (uiState is UiState.Success) {
                val mesh = uiState.mesh
                Text(
                    text = mesh.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TechnicalBadge(
                        text = mesh.formatBadge,
                        backgroundColor = BambuGreenDim,
                        contentColor = BambuGreenBright
                    )
                    Text(
                        text = String.format(Locale.US, "%,d tris", mesh.triangleCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = BambuTextSecondary
                    )
                }
            } else {
                Text(
                    text = "Placa PEI 256×256 mm",
                    style = MaterialTheme.typography.bodySmall,
                    color = BambuTextSecondary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactActionButton(
                    text = "Cargar STL / 3MF",
                    isPrimary = true,
                    onClick = onOpenFileClick
                )
                CompactActionButton(
                    text = "Pieza Test",
                    isPrimary = false,
                    onClick = onLoadSampleClick
                )
                CompactActionButton(
                    text = "ⓘ Privacidad",
                    isPrimary = false,
                    onClick = onPrivacyInfoClick
                )
            }
        }
    }
}

/**
 * Widget tipo Gizmo 3D interactivo en la esquina superior derecha.
 * Proyecta los ejes X (Rojo), Y (Verde) y Z (Azul) según la rotación actual de la cámara
 * y permite alinear instantáneamente a vistas ortogonales (TOP, FRONT, RIGHT, ISO).
 */
@Composable
private fun OrientationGizmoCard(
    pitchX: Float,
    yawZ: Float,
    onSelectPreset: (CameraPreset) -> Unit
) {
    val textMeasurer = rememberTextMeasurer()

    Surface(
        color = BambuGlassSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BambuBorderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .pointerInput(pitchX, yawZ) {
                        detectTapGestures { offset ->
                            val cx = size.width * 0.5f
                            val cy = size.height * 0.5f
                            val axisLen = size.width * 0.34f

                            val xTip = projectAxisVector(1f, 0f, 0f, pitchX, yawZ, cx, cy, axisLen)
                            val yTip = projectAxisVector(0f, 1f, 0f, pitchX, yawZ, cx, cy, axisLen)
                            val zTip = projectAxisVector(0f, 0f, 1f, pitchX, yawZ, cx, cy, axisLen)

                            val dX = hypot(offset.x - xTip.first.x, offset.y - xTip.first.y)
                            val dY = hypot(offset.x - yTip.first.x, offset.y - yTip.first.y)
                            val dZ = hypot(offset.x - zTip.first.x, offset.y - zTip.first.y)

                            val minDist = minOf(dX, minOf(dY, dZ))
                            if (minDist < 34f) {
                                when (minDist) {
                                    dZ -> onSelectPreset(CameraPreset.TOP)
                                    dX -> onSelectPreset(CameraPreset.RIGHT)
                                    else -> onSelectPreset(CameraPreset.FRONT)
                                }
                            } else {
                                onSelectPreset(CameraPreset.ISO)
                            }
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width * 0.5f
                    val cy = size.height * 0.5f
                    val radius = size.width * 0.34f

                    // Círculo de órbita técnica
                    drawCircle(
                        color = BambuBorderSubtle.copy(alpha = 0.55f),
                        radius = radius + 6.dp.toPx(),
                        center = Offset(cx, cy)
                    )

                    data class AxisNode(
                        val label: String,
                        val pos: Offset,
                        val depth: Float,
                        val color: Color,
                        val isPositive: Boolean
                    )

                    val (posX, depthX) = projectAxisVector(1f, 0f, 0f, pitchX, yawZ, cx, cy, radius)
                    val (negX, depthNX) = projectAxisVector(-1f, 0f, 0f, pitchX, yawZ, cx, cy, radius)
                    val (posY, depthY) = projectAxisVector(0f, 1f, 0f, pitchX, yawZ, cx, cy, radius)
                    val (negY, depthNY) = projectAxisVector(0f, -1f, 0f, pitchX, yawZ, cx, cy, radius)
                    val (posZ, depthZ) = projectAxisVector(0f, 0f, 1f, pitchX, yawZ, cx, cy, radius)
                    val (negZ, depthNZ) = projectAxisVector(0f, 0f, -1f, pitchX, yawZ, cx, cy, radius)

                    val nodes = listOf(
                        AxisNode("X", posX, depthX, AxisXRed, true),
                        AxisNode("-X", negX, depthNX, AxisXRed.copy(alpha = 0.45f), false),
                        AxisNode("Y", posY, depthY, AxisYGreen, true),
                        AxisNode("-Y", negY, depthNY, AxisYGreen.copy(alpha = 0.45f), false),
                        AxisNode("Z", posZ, depthZ, AxisZBlue, true),
                        AxisNode("-Z", negZ, depthNZ, AxisZBlue.copy(alpha = 0.45f), false)
                    ).sortedBy { it.depth }

                    for (node in nodes) {
                        if (node.isPositive) {
                            drawLine(
                                color = node.color,
                                start = Offset(cx, cy),
                                end = node.pos,
                                strokeWidth = 2.4.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            drawCircle(
                                color = node.color,
                                radius = 7.5.dp.toPx(),
                                center = node.pos
                            )
                            val layoutResult = textMeasurer.measure(
                                text = node.label,
                                style = TextStyle(
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                            drawText(
                                textLayoutResult = layoutResult,
                                topLeft = Offset(
                                    node.pos.x - layoutResult.size.width * 0.5f,
                                    node.pos.y - layoutResult.size.height * 0.5f
                                )
                            )
                        } else {
                            drawCircle(
                                color = node.color,
                                radius = 4.dp.toPx(),
                                center = node.pos
                            )
                        }
                    }

                    // Punto central del gizmo
                    drawCircle(
                        color = BambuTextPrimary,
                        radius = 2.5.dp.toPx(),
                        center = Offset(cx, cy)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Botones rápidos de alineación ortogonal
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                listOf(
                    CameraPreset.TOP,
                    CameraPreset.FRONT,
                    CameraPreset.RIGHT,
                    CameraPreset.ISO
                ).forEach { preset ->
                    Text(
                        text = preset.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = BambuTextPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(BambuSlateElevated)
                            .clickable { onSelectPreset(preset) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/**
 * Proyecta un vector unitario (vx, vy, vz) usando exactamente las rotaciones de cámara
 * de StlRenderer: RotateX(pitchX) * RotateZ(yawZ).
 */
private fun projectAxisVector(
    vx: Float,
    vy: Float,
    vz: Float,
    pitchXDeg: Float,
    yawZDeg: Float,
    cx: Float,
    cy: Float,
    scale: Float
): Pair<Offset, Float> {
    val radZ = Math.toRadians(yawZDeg.toDouble())
    val cosZ = cos(radZ).toFloat()
    val sinZ = sin(radZ).toFloat()

    // Rotación alrededor de Z
    val x1 = vx * cosZ - vy * sinZ
    val y1 = vx * sinZ + vy * cosZ
    val z1 = vz

    // Rotación alrededor de X
    val radX = Math.toRadians(pitchXDeg.toDouble())
    val cosX = cos(radX).toFloat()
    val sinX = sin(radX).toFloat()

    val x2 = x1
    val y2 = y1 * cosX - z1 * sinX
    val z2 = y1 * sinX + z1 * cosX

    // En pantalla: +X es derecha, +Y (OpenGL) es arriba -> -y2 en Canvas
    val screenX = cx + x2 * scale
    val screenY = cy - y2 * scale
    return Offset(screenX, screenY) to z2
}

@Composable
private fun FloatingActionPillsRow(
    showPrintBed: Boolean,
    onTogglePrintBed: () -> Unit,
    renderMode: MeshRenderMode,
    onCycleRenderMode: () -> Unit,
    overhangInspector: Boolean,
    onToggleOverhang: () -> Unit,
    clippingEnabled: Boolean,
    onToggleClipping: () -> Unit,
    onResetCamera: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FloatingPillButton(
            label = if (showPrintBed) "Placa PEI: ON" else "Placa PEI: OFF",
            isActive = showPrintBed,
            onClick = onTogglePrintBed
        )
        FloatingPillButton(
            label = "Malla: ${renderMode.label}",
            isActive = renderMode != MeshRenderMode.SOLID,
            onClick = onCycleRenderMode
        )
        FloatingPillButton(
            label = "Voladizos >45°",
            isActive = overhangInspector,
            activeColor = OverhangAlertRed,
            onClick = onToggleOverhang
        )
        FloatingPillButton(
            label = "Corte Z",
            isActive = clippingEnabled,
            onClick = onToggleClipping
        )
        FloatingPillButton(
            label = "Centrar Vista",
            isActive = false,
            onClick = onResetCamera
        )
    }
}

@Composable
private fun FloatingPillButton(
    label: String,
    isActive: Boolean,
    activeColor: Color = BambuGreenPrimary,
    onClick: () -> Unit
) {
    val bgColor = if (isActive) activeColor.copy(alpha = 0.24f) else BambuGlassSurface
    val borderColor = if (isActive) activeColor else BambuBorderSubtle
    val textColor = if (isActive) Color.White else BambuTextSecondary

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (isActive) activeColor else BambuTextMuted)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = textColor
            )
        }
    }
}

@Composable
private fun BambuModularBottomSheet(
    uiState: UiState,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    activeTab: InspectorTab,
    onSelectTab: (InspectorTab) -> Unit,
    onOpenFileClick: () -> Unit,
    onLoadSampleClick: () -> Unit,
    selectedMaterial: FilamentMaterial,
    onSelectMaterial: (FilamentMaterial) -> Unit,
    selectedSwatch: FilamentSwatches,
    onSelectSwatch: (FilamentSwatches) -> Unit,
    renderMode: MeshRenderMode,
    onSelectRenderMode: (MeshRenderMode) -> Unit,
    wireframeWidth: Float,
    onWireframeWidthChange: (Float) -> Unit,
    filamentPricePerKg: Float,
    onPricePerKgChange: (Float) -> Unit,
    clippingEnabled: Boolean,
    onClippingEnabledChange: (Boolean) -> Unit,
    clippingAxis: ClippingAxis,
    onClippingAxisChange: (ClippingAxis) -> Unit,
    clippingProgress: Float,
    onClippingProgressChange: (Float) -> Unit,
    overhangInspector: Boolean,
    onOverhangInspectorChange: (Boolean) -> Unit,
    overhangThresholdDeg: Float,
    onOverhangThresholdChange: (Float) -> Unit,
    modelTransform: ModelTransform,
    onModelTransformChange: (ModelTransform) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = BambuSlateSurface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        border = BorderStroke(1.dp, BambuBorderSubtle),
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Barra de arrastre / colapso y selector de pestañas modulares
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() }
                    .padding(top = 8.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(BambuBorderSubtle)
                )
            }

            // Pestañas del Inspector (Modelo, Materiales, Análisis, Transformar)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                InspectorTab.entries.forEach { tab ->
                    val selected = (tab == activeTab) && isExpanded
                    val bg = if (selected) BambuGreenPrimary else BambuSlateElevated
                    val fg = if (selected) Color(0xFF041F0C) else BambuTextSecondary

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(bg)
                            .clickable {
                                if (activeTab == tab) {
                                    onToggleExpanded()
                                } else {
                                    onSelectTab(tab)
                                }
                            }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = fg,
                            maxLines = 1
                        )
                    }
                }
            }

            // Contenido retraíble del Bottom Sheet
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 270.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    when (activeTab) {
                        InspectorTab.MODEL -> ModelTabContent(
                            uiState = uiState,
                            modelTransform = modelTransform,
                            onOpenFileClick = onOpenFileClick,
                            onLoadSampleClick = onLoadSampleClick
                        )
                        InspectorTab.MATERIAL -> MaterialTabContent(
                            selectedMaterial = selectedMaterial,
                            onSelectMaterial = onSelectMaterial,
                            selectedSwatch = selectedSwatch,
                            onSelectSwatch = onSelectSwatch,
                            renderMode = renderMode,
                            onSelectRenderMode = onSelectRenderMode,
                            wireframeWidth = wireframeWidth,
                            onWireframeWidthChange = onWireframeWidthChange
                        )
                        InspectorTab.ANALYSIS -> AnalysisTabContent(
                            uiState = uiState,
                            selectedMaterial = selectedMaterial,
                            modelTransform = modelTransform,
                            filamentPricePerKg = filamentPricePerKg,
                            onPricePerKgChange = onPricePerKgChange,
                            clippingEnabled = clippingEnabled,
                            onClippingEnabledChange = onClippingEnabledChange,
                            clippingAxis = clippingAxis,
                            onClippingAxisChange = onClippingAxisChange,
                            clippingProgress = clippingProgress,
                            onClippingProgressChange = onClippingProgressChange,
                            overhangInspector = overhangInspector,
                            onOverhangInspectorChange = onOverhangInspectorChange,
                            overhangThresholdDeg = overhangThresholdDeg,
                            onOverhangThresholdChange = onOverhangThresholdChange
                        )
                        InspectorTab.TRANSFORM -> TransformTabContent(
                            uiState = uiState,
                            modelTransform = modelTransform,
                            onModelTransformChange = onModelTransformChange
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelTabContent(
    uiState: UiState,
    modelTransform: ModelTransform,
    onOpenFileClick: () -> Unit,
    onLoadSampleClick: () -> Unit
) {
    if (uiState is UiState.Success) {
        val mesh = uiState.mesh
        val tBounds = MeshMath.computeTransformedBounds(mesh.boundingBox, modelTransform)
        val fitsPlate = tBounds.widthX <= 256f && tBounds.depthY <= 256f && tBounds.heightZ <= 256f

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DIMENSIONES DEL MODELO (MM)",
                style = MaterialTheme.typography.labelMedium,
                color = BambuTextSecondary
            )
            TechnicalBadge(
                text = if (fitsPlate) "CABE EN PLACA 256³" else "EXCEDE PLACA 256³",
                backgroundColor = if (fitsPlate) BambuGreenDim else Color(0xFF422808),
                contentColor = if (fitsPlate) BambuGreenBright else WarningAmber
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Tarjetas de dimensiones X / Y / Z con indicadores de color CAD
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DimensionMetricCard(
                axisLabel = "X (Ancho)",
                valueMm = tBounds.widthX,
                axisColor = AxisXRed,
                modifier = Modifier.weight(1f)
            )
            DimensionMetricCard(
                axisLabel = "Y (Prof.)",
                valueMm = tBounds.depthY,
                axisColor = AxisYGreen,
                modifier = Modifier.weight(1f)
            )
            DimensionMetricCard(
                axisLabel = "Z (Altura)",
                valueMm = tBounds.heightZ,
                axisColor = AxisZBlue,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Resumen de topología y acciones de archivo
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onOpenFileClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BambuGreenPrimary,
                    contentColor = Color(0xFF041F0C)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Cargar STL / 3MF", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onLoadSampleClick,
                border = BorderStroke(1.dp, BambuBorderSubtle),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Pieza Calibración", color = BambuTextPrimary)
            }
        }
    } else {
        Text(
            text = "Ningún modelo cargado actualmente. Abre un archivo .STL o .3MF desde tu almacenamiento o carga la pieza de calibración integrada.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onOpenFileClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BambuGreenPrimary,
                    contentColor = Color(0xFF041F0C)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Cargar STL / 3MF", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onLoadSampleClick,
                border = BorderStroke(1.dp, BambuBorderSubtle),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Probar Calibración", color = BambuTextPrimary)
            }
        }
    }
}

@Composable
private fun MaterialTabContent(
    selectedMaterial: FilamentMaterial,
    onSelectMaterial: (FilamentMaterial) -> Unit,
    selectedSwatch: FilamentSwatches,
    onSelectSwatch: (FilamentSwatches) -> Unit,
    renderMode: MeshRenderMode,
    onSelectRenderMode: (MeshRenderMode) -> Unit,
    wireframeWidth: Float,
    onWireframeWidthChange: (Float) -> Unit
) {
    Text(
        text = "PRESET DE FILAMENTO BAMBU LAB",
        style = MaterialTheme.typography.labelMedium,
        color = BambuTextSecondary
    )
    Spacer(modifier = Modifier.height(6.dp))

    // Selector de material (PLA, PETG, ABS, Matte, Silk, PA-CF)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilamentMaterial.entries.forEach { mat ->
            val selected = mat == selectedMaterial
            Surface(
                color = if (selected) BambuGreenDim else BambuSlateElevated,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (selected) BambuGreenBright else BambuBorderSubtle),
                modifier = Modifier.clickable { onSelectMaterial(mat) }
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = mat.shortTag,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (selected) BambuGreenBright else BambuTextPrimary
                    )
                    Text(
                        text = "${mat.densityGPerCm3} g/cm³",
                        style = MaterialTheme.typography.labelSmall,
                        color = BambuTextSecondary
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Paleta de colores con código HEX
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "COLOR: ${selectedSwatch.name.uppercase()}",
            style = MaterialTheme.typography.labelMedium,
            color = BambuTextSecondary
        )
        TechnicalBadge(
            text = selectedSwatch.hex,
            backgroundColor = BambuSlateElevated,
            contentColor = BambuTextPrimary
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BambuFilamentPalette.forEach { swatch ->
            val isSelected = swatch.hex == selectedSwatch.hex
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(swatch.color)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) BambuGreenBright else BambuBorderSubtle,
                        shape = CircleShape
                    )
                    .clickable { onSelectSwatch(swatch) }
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Selector de modo de malla + grosor de wireframe
    Text(
        text = "MODO DE VISUALIZACIÓN DE MALLA",
        style = MaterialTheme.typography.labelMedium,
        color = BambuTextSecondary
    )
    Spacer(modifier = Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MeshRenderMode.entries.forEach { mode ->
            val selected = mode == renderMode
            Surface(
                color = if (selected) BambuGreenDim else BambuSlateElevated,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (selected) BambuGreenBright else BambuBorderSubtle),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelectRenderMode(mode) }
            ) {
                Box(
                    modifier = Modifier.padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) BambuGreenBright else BambuTextPrimary
                    )
                }
            }
        }
    }

    if (renderMode != MeshRenderMode.SOLID) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = String.format(Locale.US, "Grosor de línea: %.2f px", wireframeWidth),
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = wireframeWidth,
                onValueChange = onWireframeWidthChange,
                valueRange = 0.7f..2.8f,
                modifier = Modifier.width(180.dp),
                colors = SliderDefaults.colors(
                    thumbColor = BambuGreenBright,
                    activeTrackColor = BambuGreenPrimary
                )
            )
        }
    }
}

@Composable
private fun AnalysisTabContent(
    uiState: UiState,
    selectedMaterial: FilamentMaterial,
    modelTransform: ModelTransform,
    filamentPricePerKg: Float,
    onPricePerKgChange: (Float) -> Unit,
    clippingEnabled: Boolean,
    onClippingEnabledChange: (Boolean) -> Unit,
    clippingAxis: ClippingAxis,
    onClippingAxisChange: (ClippingAxis) -> Unit,
    clippingProgress: Float,
    onClippingProgressChange: (Float) -> Unit,
    overhangInspector: Boolean,
    onOverhangInspectorChange: (Boolean) -> Unit,
    overhangThresholdDeg: Float,
    onOverhangThresholdChange: (Float) -> Unit
) {
    val successState = uiState as? UiState.Success
    val analysis = successState?.analysis
    val mesh = successState?.mesh

    // 1. Tarjetas de Propiedades Físicas (Volumen, Área, Peso y Costo)
    Text(
        text = "PROPIEDADES FÍSICAS (${selectedMaterial.displayName.uppercase()})",
        style = MaterialTheme.typography.labelMedium,
        color = BambuTextSecondary
    )
    Spacer(modifier = Modifier.height(6.dp))

    if (analysis != null && mesh != null) {
        val scaledVolCm3 = analysis.estimateVolumeScaledCm3(modelTransform.scaleFactor)
        val scaledAreaCm2 = analysis.estimateSurfaceScaledCm2(modelTransform.scaleFactor)
        val weightGrams = mesh.estimateWeightGrams(analysis.volumeCm3, selectedMaterial, modelTransform.scaleFactor)
        val estimatedCost = mesh.estimateFilamentCost(weightGrams, filamentPricePerKg)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricSummaryBox(
                label = "Volumen",
                value = String.format(Locale.US, "%.2f cm³", scaledVolCm3),
                modifier = Modifier.weight(1f)
            )
            MetricSummaryBox(
                label = "Superficie",
                value = String.format(Locale.US, "%.1f cm²", scaledAreaCm2),
                modifier = Modifier.weight(1f)
            )
            MetricSummaryBox(
                label = "Peso Est.",
                value = String.format(Locale.US, "%.1f g", weightGrams),
                highlightColor = BambuGreenBright,
                modifier = Modifier.weight(1f)
            )
            MetricSummaryBox(
                label = "Costo Fil.",
                value = String.format(Locale.US, "$%.2f", estimatedCost),
                highlightColor = BambuGreenBright,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = String.format(Locale.US, "Precio bobina: $%.0f / kg", filamentPricePerKg),
                style = MaterialTheme.typography.bodySmall
            )
            Slider(
                value = filamentPricePerKg,
                onValueChange = onPricePerKgChange,
                valueRange = 10f..75f,
                modifier = Modifier.width(180.dp),
                colors = SliderDefaults.colors(
                    thumbColor = BambuGreenBright,
                    activeTrackColor = BambuGreenPrimary
                )
            )
        }
    } else {
        Text(
            text = if (successState?.isAnalyzing == true) "Calculando integración de tetraedros..."
            else "Carga un modelo STL / 3MF para calcular volumen, área, peso y costo.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = BambuBorderSubtle)

    // 2. Plano de Sección / Corte Transversal Interactivo (Clipping Plane Z/X/Y)
    val tBounds = mesh?.let { MeshMath.computeTransformedBounds(it.boundingBox, modelTransform) }
    val currentClipMm = if (tBounds != null) {
        val (minC, maxC) = when (clippingAxis) {
            ClippingAxis.X -> tBounds.minX to tBounds.maxX
            ClippingAxis.Y -> tBounds.minY to tBounds.maxY
            ClippingAxis.Z -> tBounds.minZ to tBounds.maxZ
        }
        minC + (maxC - minC) * clippingProgress
    } else 0f

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "PLANO DE CORTE TRANSVERSAL",
                style = MaterialTheme.typography.labelMedium,
                color = BambuTextPrimary
            )
            Text(
                text = if (clippingEnabled) {
                    String.format(Locale.US, "Cota activa: %.2f mm (%d%%)", currentClipMm, (clippingProgress * 100).toInt())
                } else {
                    "Inspeccionar geometría interna (discard shader)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (clippingEnabled) BambuGreenBright else BambuTextSecondary
            )
        }
        Switch(
            checked = clippingEnabled,
            onCheckedChange = onClippingEnabledChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BambuGreenPrimary
            )
        )
    }

    if (clippingEnabled) {
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ClippingAxis.entries.forEach { axis ->
                val selected = axis == clippingAxis
                Surface(
                    color = if (selected) BambuGreenDim else BambuSlateElevated,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, if (selected) BambuGreenBright else BambuBorderSubtle),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onClippingAxisChange(axis) }
                ) {
                    Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = axis.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) BambuGreenBright else BambuTextSecondary
                        )
                    }
                }
            }
        }
        Slider(
            value = clippingProgress,
            onValueChange = onClippingProgressChange,
            valueRange = 0.01f..1.0f,
            colors = SliderDefaults.colors(
                thumbColor = BambuGreenBright,
                activeTrackColor = BambuGreenPrimary
            )
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = BambuBorderSubtle)

    // 3. Detector de Voladizos (Overhang Angle Inspector > 45°)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "INSPECTOR DE VOLADIZOS (> ${overhangThresholdDeg.toInt()}°)",
                    style = MaterialTheme.typography.labelMedium,
                    color = BambuTextPrimary
                )
                if (analysis != null) {
                    TechnicalBadge(
                        text = String.format(Locale.US, "%.1f%% en riesgo", analysis.overhangPercentage),
                        backgroundColor = if (analysis.overhangPercentage > 5f) Color(0xFF421210) else BambuGreenDim,
                        contentColor = if (analysis.overhangPercentage > 5f) OverhangAlertRed else BambuGreenBright
                    )
                }
            }
            Text(
                text = "Colorea en rojo las caras que requieren soportes",
                style = MaterialTheme.typography.labelSmall,
                color = BambuTextSecondary
            )
        }
        Switch(
            checked = overhangInspector,
            onCheckedChange = onOverhangInspectorChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = OverhangAlertRed
            )
        )
    }

    if (overhangInspector) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Ángulo crítico: ${overhangThresholdDeg.toInt()}°",
                style = MaterialTheme.typography.bodySmall,
                color = OverhangAlertRed
            )
            Slider(
                value = overhangThresholdDeg,
                onValueChange = onOverhangThresholdChange,
                valueRange = 25f..70f,
                modifier = Modifier.width(180.dp),
                colors = SliderDefaults.colors(
                    thumbColor = OverhangAlertRed,
                    activeTrackColor = OverhangAlertRed
                )
            )
        }
    }
}

@Composable
private fun TransformTabContent(
    uiState: UiState,
    modelTransform: ModelTransform,
    onModelTransformChange: (ModelTransform) -> Unit
) {
    val mesh = (uiState as? UiState.Success)?.mesh
    val tBounds = mesh?.let { MeshMath.computeTransformedBounds(it.boundingBox, modelTransform) }

    // 1. Auto-centrar y colocar sobre la cama (Z = 0)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "COLOCAR SOBRE LA CAMA (Z = 0)",
                style = MaterialTheme.typography.labelMedium,
                color = BambuTextPrimary
            )
            Text(
                text = "Alinea automáticamente la base del modelo con la placa PEI",
                style = MaterialTheme.typography.labelSmall,
                color = BambuTextSecondary
            )
        }
        Switch(
            checked = modelTransform.autoLayOnBed,
            onCheckedChange = { lay ->
                onModelTransformChange(modelTransform.copy(autoLayOnBed = lay))
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = BambuGreenPrimary
            )
        )
    }

    Spacer(modifier = Modifier.height(10.dp))

    // 2. Escala uniforme por porcentaje y ajuste exacto de altura Z
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = String.format(Locale.US, "ESCALA UNIFORME: %d%%", (modelTransform.scaleFactor * 100f).toInt()),
            style = MaterialTheme.typography.labelMedium,
            color = BambuTextSecondary
        )
        if (tBounds != null) {
            Text(
                text = String.format(Locale.US, "Altura Z: %.2f mm", tBounds.heightZ),
                style = MaterialTheme.typography.titleSmall,
                color = BambuGreenBright
            )
        }
    }

    Slider(
        value = modelTransform.scaleFactor,
        onValueChange = { s ->
            onModelTransformChange(modelTransform.copy(scaleFactor = s.coerceIn(0.1f, 5.0f)))
        },
        valueRange = 0.1f..3.0f,
        colors = SliderDefaults.colors(
            thumbColor = BambuGreenBright,
            activeTrackColor = BambuGreenPrimary
        )
    )

    // Botones rápidos de escala y ajuste milimétrico de altura Z
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        listOf(0.5f to "50%", 1.0f to "100%", 1.5f to "150%", 2.0f to "200%").forEach { (factor, label) ->
            CompactTransformChip(
                text = label,
                isSelected = kotlin.math.abs(modelTransform.scaleFactor - factor) < 0.01f,
                onClick = { onModelTransformChange(modelTransform.copy(scaleFactor = factor)) },
                modifier = Modifier.weight(1f)
            )
        }
        if (tBounds != null && tBounds.heightZ > 0.1f) {
            CompactTransformChip(
                text = "Z = 50mm",
                isSelected = false,
                onClick = {
                    val unscaledHeight = tBounds.heightZ / modelTransform.scaleFactor
                    if (unscaledHeight > 0.01f) {
                        val targetScale = (50f / unscaledHeight).coerceIn(0.1f, 10f)
                        onModelTransformChange(modelTransform.copy(scaleFactor = targetScale))
                    }
                },
                modifier = Modifier.weight(1.2f)
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // 3. Rotación rápida en pasos de 90° sobre los ejes X, Y y Z
    Text(
        text = String.format(
            Locale.US,
            "ROTACIÓN EN PASOS DE 90° (X: %d° | Y: %d° | Z: %d°)",
            modelTransform.rotXDeg.toInt(),
            modelTransform.rotYDeg.toInt(),
            modelTransform.rotZDeg.toInt()
        ),
        style = MaterialTheme.typography.labelMedium,
        color = BambuTextSecondary
    )
    Spacer(modifier = Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AxisRotationButton(
            label = "Rotar X +90°",
            axisColor = AxisXRed,
            onClick = {
                onModelTransformChange(
                    modelTransform.copy(rotXDeg = (modelTransform.rotXDeg + 90f) % 360f)
                )
            },
            modifier = Modifier.weight(1f)
        )
        AxisRotationButton(
            label = "Rotar Y +90°",
            axisColor = AxisYGreen,
            onClick = {
                onModelTransformChange(
                    modelTransform.copy(rotYDeg = (modelTransform.rotYDeg + 90f) % 360f)
                )
            },
            modifier = Modifier.weight(1f)
        )
        AxisRotationButton(
            label = "Rotar Z +90°",
            axisColor = AxisZBlue,
            onClick = {
                onModelTransformChange(
                    modelTransform.copy(rotZDeg = (modelTransform.rotZDeg + 90f) % 360f)
                )
            },
            modifier = Modifier.weight(1f)
        )
        CompactTransformChip(
            text = "Reset",
            isSelected = false,
            onClick = { onModelTransformChange(ModelTransform()) },
            modifier = Modifier.weight(0.75f)
        )
    }
}

@Composable
private fun DimensionMetricCard(
    axisLabel: String,
    valueMm: Float,
    axisColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = BambuSlateElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, BambuBorderSubtle)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(axisColor)
                )
                Text(
                    text = axisLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = BambuTextSecondary
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = String.format(Locale.US, "%.2f", valueMm),
                style = MaterialTheme.typography.titleSmall,
                color = BambuTextPrimary
            )
        }
    }
}

@Composable
private fun MetricSummaryBox(
    label: String,
    value: String,
    highlightColor: Color = BambuTextPrimary,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = BambuSlateElevated,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, BambuBorderSubtle)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = BambuTextSecondary,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = highlightColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TechnicalBadge(
    text: String,
    backgroundColor: Color,
    contentColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(backgroundColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CompactActionButton(
    text: String,
    isPrimary: Boolean,
    onClick: () -> Unit
) {
    val bg = if (isPrimary) BambuGreenPrimary else BambuSlateElevated
    val fg = if (isPrimary) Color(0xFF041F0C) else BambuTextPrimary

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, if (isPrimary) BambuGreenBright else BambuBorderSubtle, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = fg
        )
    }
}

@Composable
private fun CompactTransformChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (isSelected) BambuGreenDim else BambuSlateElevated,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (isSelected) BambuGreenBright else BambuBorderSubtle)
    ) {
        Box(modifier = Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) BambuGreenBright else BambuTextPrimary
            )
        }
    }
}

@Composable
private fun AxisRotationButton(
    label: String,
    axisColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = BambuSlateElevated,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, axisColor.copy(alpha = 0.65f))
    ) {
        Box(modifier = Modifier.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = BambuTextPrimary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun EmptyWorkspacePrompt(
    onOpenFileClick: () -> Unit,
    onLoadSampleClick: () -> Unit,
    onPrivacyInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = BambuGlassSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, BambuBorderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TechnicalBadge(
                text = "MANUFACTURA ADITIVA // STL & 3MF // OPENGL ES 2.0",
                backgroundColor = BambuGreenDim,
                contentColor = BambuGreenBright
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Rabbit3D Viewer",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Importa un archivo .STL (Binario o ASCII) o .3MF para analizar volumen, peso de filamento, cortes transversales Z y ángulos de voladizo.",
                style = MaterialTheme.typography.bodyMedium,
                color = BambuTextSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onOpenFileClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BambuGreenPrimary,
                        contentColor = Color(0xFF041F0C)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cargar STL / 3MF", fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onLoadSampleClick,
                    border = BorderStroke(1.dp, BambuBorderSubtle),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Pieza de Calibración", color = BambuTextPrimary)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Acerca de / Privacidad: Procesamiento 100% local sin recopilación de datos",
                style = MaterialTheme.typography.labelSmall,
                color = BambuTextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onPrivacyInfoClick)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun LoadingProgressCard(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val pct = (progress * 100f).toInt().coerceIn(0, 100)
    Surface(
        modifier = modifier,
        color = BambuGlassSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, BambuBorderSubtle)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "PROCESANDO GEOMETRÍA 3D (STL / 3MF)... $pct%",
                style = MaterialTheme.typography.titleSmall,
                color = BambuGreenBright
            )
            Spacer(modifier = Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0.02f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = BambuGreenPrimary,
                trackColor = BambuSlateElevated
            )
        }
    }
}

@Composable
private fun ErrorDiagnosticCard(
    message: String,
    onRetryClick: () -> Unit,
    onSampleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xE62A1212),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, OverhangAlertRed)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "ERROR DE LECTURA STL / 3MF",
                style = MaterialTheme.typography.titleSmall,
                color = OverhangAlertRed
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = BambuTextPrimary
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRetryClick,
                    colors = ButtonDefaults.buttonColors(containerColor = OverhangAlertRed)
                ) {
                    Text("Cargar STL / 3MF", color = Color.White)
                }
                OutlinedButton(
                    onClick = onSampleClick,
                    border = BorderStroke(1.dp, BambuBorderSubtle)
                ) {
                    Text("Cargar Calibración", color = BambuTextPrimary)
                }
            }
        }
    }
}

@Composable
private fun AboutPrivacyDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BambuSlateSurface,
        titleContentColor = BambuTextPrimary,
        textContentColor = BambuTextSecondary,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(BambuGreenBright)
                )
                Text(
                    text = "Acerca de Rabbit3D Viewer",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TechnicalBadge(
                    text = "VERSIÓN 1.0.0 • STL & 3MF CAD ENGINE",
                    backgroundColor = BambuGreenDim,
                    contentColor = BambuGreenBright
                )
                Text(
                    text = "Rabbit3D Viewer procesa los modelos 3D 100% de forma local en el dispositivo y no recopila ni comparte datos personales.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = BambuTextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "• Sin servidores externos ni telemetría.\n• Acceso a archivos exclusivamente bajo selección del usuario (Storage Access Framework).\n• Soporte nativo para mallas .STL (Binario/ASCII) y contenedores .3MF.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BambuTextSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BambuGreenPrimary,
                    contentColor = Color(0xFF041F0C)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Entendido", fontWeight = FontWeight.Bold)
            }
        }
    )
}
