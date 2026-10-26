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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.ministl.viewer.R
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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

private enum class InspectorTab(val titleResId: Int) {
    MODEL(R.string.tab_model),
    MATERIAL(R.string.tab_material),
    ANALYSIS(R.string.tab_analysis),
    TRANSFORM(R.string.tab_transform)
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
    var currentPresetIndex by remember { mutableIntStateOf(0) }
    val cameraPresets = remember {
        listOf(CameraPreset.ISO, CameraPreset.TOP, CameraPreset.FRONT, CameraPreset.RIGHT)
    }

    // Estado del Bottom Sheet modular (colapsado por defecto) y diálogo de privacidad
    var isSheetExpanded by remember { mutableStateOf(false) }
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

    // Control de ciclo de vida para pausar y reanudar el contexto OpenGL ES 2.0 y su hilo de render
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, glSurfaceViewRef) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    glSurfaceViewRef?.onPause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    glSurfaceViewRef?.onResume()
                    glSurfaceViewRef?.requestRender()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glSurfaceViewRef?.onPause()
        }
    }

    // Enviar la malla al hilo OpenGL cuando se cargue un nuevo modelo y mantener el panel colapsado por defecto
    LaunchedEffect(uiState) {
        if (uiState is UiState.Success) {
            isSheetExpanded = false
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
            onRelease = { glView ->
                glView.onPause()
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

        // 2. Barra Superior Transparente y Compacta + Gizmo XYZ Flotante (56dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Izquierda: Título "Rabbit3D" + único botón de icono compacto (carpeta +)
            MinimalTopBar(
                onOpenFileClick = onOpenFileClick
            )

            // Derecha: Círculo del gizmo XYZ flotante (56dp) sin fondo pesado
            FloatingOrientationGizmo(
                pitchX = camPitchX,
                yawZ = camYawZ,
                onTapGizmo = { tappedPreset ->
                    val nextPreset = if (tappedPreset != null) {
                        currentPresetIndex = cameraPresets.indexOf(tappedPreset).coerceAtLeast(0)
                        tappedPreset
                    } else {
                        currentPresetIndex = (currentPresetIndex + 1) % cameraPresets.size
                        cameraPresets[currentPresetIndex]
                    }
                    glSurfaceViewRef?.let { glView ->
                        glView.renderer.resetCamera()
                        glView.renderer.applyCameraPreset(nextPreset)
                        camPitchX = nextPreset.pitchX
                        camYawZ = nextPreset.yawZ
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

        // 4. Contenedor Inferior: Oculto hasta que se cargue un modelo
        if (uiState is UiState.Success) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                // Fila de iconos circulares minimalistas (sin textos largos)
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

                // Bottom Sheet Modular colapsado por defecto (se despliega al tocar una pestaña)
                BambuModularBottomSheet(
                    uiState = uiState,
                    isExpanded = isSheetExpanded,
                    onToggleExpanded = { isSheetExpanded = !isSheetExpanded },
                    activeTab = activeTab,
                    onSelectTab = { tab ->
                        activeTab = tab
                        isSheetExpanded = true
                    },
                    showPrintBed = showPrintBed,
                    onTogglePrintBed = {
                        showPrintBed = it
                        syncRendererAndRedraw()
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
        }

        if (showPrivacyDialog) {
            AboutPrivacyDialog(onDismiss = { showPrivacyDialog = false })
        }
    }
}

/**
 * Barra superior transparente y compacta que muestra únicamente el título "Rabbit3D"
 * y un único botón de icono compacto (carpeta +) para cargar archivo.
 */
@Composable
private fun MinimalTopBar(
    onOpenFileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
                text = "Rabbit3D",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = BambuTextPrimary
            )
        }

        // Único botón de icono compacto (carpeta +) para cargar archivo con touch target de 48dp
        val openFileDescription = stringResource(R.string.cd_open_file)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    onClick = onOpenFileClick,
                    role = Role.Button,
                    onClickLabel = openFileDescription
                )
                .semantics {
                    contentDescription = openFileDescription
                },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = BambuGlassSurface.copy(alpha = 0.72f),
                shape = CircleShape,
                border = BorderStroke(1.dp, BambuBorderSubtle),
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Canvas(modifier = Modifier.size(18.dp)) {
                    val w = size.width
                    val h = size.height
                    val strokeW = 1.7.dp.toPx()

                    // Contorno de carpeta
                    val folderPath = Path().apply {
                        moveTo(w * 0.08f, h * 0.26f)
                        lineTo(w * 0.38f, h * 0.26f)
                        lineTo(w * 0.48f, h * 0.38f)
                        lineTo(w * 0.92f, h * 0.38f)
                        lineTo(w * 0.92f, h * 0.82f)
                        lineTo(w * 0.08f, h * 0.82f)
                        close()
                    }
                    drawPath(
                        path = folderPath,
                        color = BambuGreenBright,
                        style = Stroke(width = strokeW, join = StrokeJoin.Round)
                    )

                    // Símbolo '+' dentro de la carpeta
                    val cx = w * 0.52f
                    val cy = h * 0.60f
                    val arm = w * 0.14f
                    drawLine(
                        color = Color.White,
                        start = Offset(cx - arm, cy),
                        end = Offset(cx + arm, cy),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(cx, cy - arm),
                        end = Offset(cx, cy + arm),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}
}

/**
 * Círculo del gizmo XYZ flotante (56dp) sin tarjeta ni fondo pesado;
 * al tocarlo rota entre las vistas principales y centra la cámara.
 */
@Composable
private fun FloatingOrientationGizmo(
    pitchX: Float,
    yawZ: Float,
    onTapGizmo: (CameraPreset?) -> Unit
) {
    val textMeasurer = rememberTextMeasurer()
    val gizmoDescription = stringResource(R.string.cd_gizmo)

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .semantics {
                contentDescription = gizmoDescription
                role = Role.Button
            }
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
                    if (minDist < 18f) {
                        val target = when (minDist) {
                            dZ -> CameraPreset.TOP
                            dX -> CameraPreset.RIGHT
                            else -> CameraPreset.FRONT
                        }
                        val alreadyThere = kotlin.math.abs(pitchX - target.pitchX) < 1f &&
                            kotlin.math.abs(yawZ - target.yawZ) < 1f
                        if (alreadyThere) {
                            onTapGizmo(null)
                        } else {
                            onTapGizmo(target)
                        }
                    } else {
                        onTapGizmo(null)
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width * 0.5f
            val cy = size.height * 0.5f
            val radius = size.width * 0.34f

            // Círculo flotante ligero sin fondo pesado
            drawCircle(
                color = BambuGlassSurface.copy(alpha = 0.38f),
                radius = size.width * 0.48f,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = BambuBorderSubtle.copy(alpha = 0.55f),
                radius = size.width * 0.48f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.dp.toPx())
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
                AxisNode("-X", negX, depthNX, AxisXRed.copy(alpha = 0.40f), false),
                AxisNode("Y", posY, depthY, AxisYGreen, true),
                AxisNode("-Y", negY, depthNY, AxisYGreen.copy(alpha = 0.40f), false),
                AxisNode("Z", posZ, depthZ, AxisZBlue, true),
                AxisNode("-Z", negZ, depthNZ, AxisZBlue.copy(alpha = 0.40f), false)
            ).sortedBy { it.depth }

            for (node in nodes) {
                if (node.isPositive) {
                    drawLine(
                        color = node.color,
                        start = Offset(cx, cy),
                        end = node.pos,
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = node.color,
                        radius = 5.8.dp.toPx(),
                        center = node.pos
                    )
                    val layoutResult = textMeasurer.measure(
                        text = node.label,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 7.5.sp,
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
                        radius = 2.8.dp.toPx(),
                        center = node.pos
                    )
                }
            }

            // Punto central del gizmo
            drawCircle(
                color = BambuTextPrimary,
                radius = 2.dp.toPx(),
                center = Offset(cx, cy)
            )
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

private enum class QuickActionIcon {
    PRINT_BED,
    WIREFRAME,
    OVERHANG,
    CLIPPING,
    RESET_CAMERA
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
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MinimalCircleIconButton(
            icon = QuickActionIcon.PRINT_BED,
            isActive = showPrintBed,
            contentDescription = stringResource(R.string.cd_toggle_bed),
            onClick = onTogglePrintBed
        )
        MinimalCircleIconButton(
            icon = QuickActionIcon.WIREFRAME,
            isActive = renderMode != MeshRenderMode.SOLID,
            contentDescription = stringResource(R.string.cd_toggle_wireframe),
            onClick = onCycleRenderMode
        )
        MinimalCircleIconButton(
            icon = QuickActionIcon.OVERHANG,
            isActive = overhangInspector,
            activeColor = OverhangAlertRed,
            contentDescription = stringResource(R.string.cd_toggle_overhang),
            onClick = onToggleOverhang
        )
        MinimalCircleIconButton(
            icon = QuickActionIcon.CLIPPING,
            isActive = clippingEnabled,
            contentDescription = stringResource(R.string.cd_toggle_clipping),
            onClick = onToggleClipping
        )
        MinimalCircleIconButton(
            icon = QuickActionIcon.RESET_CAMERA,
            isActive = false,
            contentDescription = stringResource(R.string.cd_reset_camera),
            onClick = onResetCamera
        )
    }
}

@Composable
private fun MinimalCircleIconButton(
    icon: QuickActionIcon,
    isActive: Boolean,
    contentDescription: String,
    activeColor: Color = BambuGreenPrimary,
    onClick: () -> Unit
) {
    val bgColor = if (isActive) activeColor.copy(alpha = 0.26f) else BambuGlassSurface.copy(alpha = 0.72f)
    val borderColor = if (isActive) activeColor else BambuBorderSubtle
    val iconColor = if (isActive) Color.White else BambuTextSecondary

    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                onClick = onClick,
                role = Role.Button,
                onClickLabel = contentDescription
            )
            .semantics {
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = bgColor,
            shape = CircleShape,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Canvas(modifier = Modifier.size(16.dp)) {
                val w = size.width
                val h = size.height
                val s = 1.6.dp.toPx()

                when (icon) {
                    QuickActionIcon.PRINT_BED -> {
                        // Icono de cuadrícula / cama PEI
                        drawRoundRect(
                            color = iconColor,
                            topLeft = Offset(w * 0.1f, h * 0.1f),
                            size = Size(w * 0.8f, h * 0.8f),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                            style = Stroke(width = s)
                        )
                        drawLine(iconColor, Offset(w * 0.5f, h * 0.1f), Offset(w * 0.5f, h * 0.9f), s)
                        drawLine(iconColor, Offset(w * 0.1f, h * 0.5f), Offset(w * 0.9f, h * 0.5f), s)
                    }
                    QuickActionIcon.WIREFRAME -> {
                        // Icono de malla triangular
                        val path = Path().apply {
                            moveTo(w * 0.5f, h * 0.1f)
                            lineTo(w * 0.9f, h * 0.85f)
                            lineTo(w * 0.1f, h * 0.85f)
                            close()
                        }
                        drawPath(path, color = iconColor, style = Stroke(width = s, join = StrokeJoin.Round))
                        drawLine(iconColor, Offset(w * 0.5f, h * 0.1f), Offset(w * 0.5f, h * 0.85f), s * 0.85f)
                    }
                    QuickActionIcon.OVERHANG -> {
                        // Icono de ángulo de voladizo
                        drawLine(iconColor, Offset(w * 0.15f, h * 0.85f), Offset(w * 0.85f, h * 0.85f), s, StrokeCap.Round)
                        drawLine(iconColor, Offset(w * 0.25f, h * 0.85f), Offset(w * 0.80f, h * 0.20f), s, StrokeCap.Round)
                        drawCircle(color = if (isActive) OverhangAlertRed else iconColor, radius = 2.dp.toPx(), center = Offset(w * 0.72f, h * 0.52f))
                    }
                    QuickActionIcon.CLIPPING -> {
                        // Icono de plano de corte Z
                        drawLine(iconColor, Offset(w * 0.1f, h * 0.5f), Offset(w * 0.9f, h * 0.5f), s, StrokeCap.Round)
                        drawRect(
                            color = iconColor,
                            topLeft = Offset(w * 0.22f, h * 0.5f),
                            size = Size(w * 0.56f, h * 0.36f),
                            style = Stroke(width = s)
                        )
                    }
                    QuickActionIcon.RESET_CAMERA -> {
                        // Icono de centrar vista (diana / mira)
                        drawCircle(color = iconColor, radius = w * 0.34f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(width = s))
                        drawCircle(color = iconColor, radius = 1.8.dp.toPx(), center = Offset(w * 0.5f, h * 0.5f))
                        drawLine(iconColor, Offset(w * 0.5f, h * 0.05f), Offset(w * 0.5f, h * 0.22f), s, StrokeCap.Round)
                        drawLine(iconColor, Offset(w * 0.5f, h * 0.78f), Offset(w * 0.5f, h * 0.95f), s, StrokeCap.Round)
                        drawLine(iconColor, Offset(w * 0.05f, h * 0.5f), Offset(w * 0.22f, h * 0.5f), s, StrokeCap.Round)
                        drawLine(iconColor, Offset(w * 0.78f, h * 0.5f), Offset(w * 0.95f, h * 0.5f), s, StrokeCap.Round)
                    }
                }
            }
        }
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
    showPrintBed: Boolean,
    onTogglePrintBed: (Boolean) -> Unit,
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
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        border = BorderStroke(1.dp, BambuBorderSubtle),
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Barra de arrastre mínima
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpanded() }
                    .padding(top = 6.dp, bottom = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(BambuBorderSubtle)
                )
            }

            // Pestañas del Inspector (Modelo, Materiales, Análisis, Transformar)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                InspectorTab.entries.forEach { tab ->
                    val selected = (tab == activeTab) && isExpanded
                    val bg = if (selected) BambuGreenPrimary else BambuSlateElevated
                    val fg = if (selected) Color(0xFF041F0C) else BambuTextSecondary

                    val tabTitle = stringResource(tab.titleResId)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(bg)
                            .clickable(
                                role = Role.Tab,
                                onClickLabel = tabTitle,
                                onClick = {
                                    if (!isExpanded) {
                                        onSelectTab(tab)
                                    } else if (activeTab == tab) {
                                        onToggleExpanded()
                                    } else {
                                        onSelectTab(tab)
                                    }
                                }
                            )
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tabTitle,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = fg,
                            maxLines = 1
                        )
                    }
                }
            }

            // Contenido retraíble del Bottom Sheet (desplegado solo al tocar una pestaña)
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    when (activeTab) {
                        InspectorTab.MODEL -> ModelTabContent(
                            uiState = uiState,
                            modelTransform = modelTransform,
                            showPrintBed = showPrintBed,
                            onTogglePrintBed = onTogglePrintBed,
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
    showPrintBed: Boolean,
    onTogglePrintBed: (Boolean) -> Unit,
    onOpenFileClick: () -> Unit,
    onLoadSampleClick: () -> Unit
) {
    if (uiState is UiState.Success) {
        val mesh = uiState.mesh
        val tBounds = MeshMath.computeTransformedBounds(mesh.boundingBox, modelTransform)
        val fitsPlate = tBounds.widthX <= 256f && tBounds.depthY <= 256f && tBounds.heightZ <= 256f

        // Resumen compacto del archivo activo y conteo de triángulos
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = mesh.fileName,
                style = MaterialTheme.typography.titleSmall,
                color = BambuTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
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
        }

        Spacer(modifier = Modifier.height(8.dp))

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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Mostrar Placa PEI (256×256 mm)",
                style = MaterialTheme.typography.bodySmall,
                color = BambuTextSecondary
            )
            Switch(
                checked = showPrintBed,
                onCheckedChange = onTogglePrintBed,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = BambuGreenPrimary
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Acciones de archivo
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
                Text(stringResource(R.string.action_open_file), fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onLoadSampleClick,
                border = BorderStroke(1.dp, BambuBorderSubtle),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.action_load_sample), color = BambuTextPrimary)
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
        text = "PRESET DE FILAMENTO INDUSTRIAL",
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
            val swatchDescription = stringResource(R.string.cd_color_swatch, swatch.name)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = swatchDescription,
                        onClick = { onSelectSwatch(swatch) }
                    )
                    .semantics {
                        contentDescription = swatchDescription
                    },
                contentAlignment = Alignment.Center
            ) {
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
                )
            }
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
private fun CompactTransformChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(
            role = Role.Button,
            onClickLabel = text,
            onClick = onClick
        ),
        color = if (isSelected) BambuGreenDim else BambuSlateElevated,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (isSelected) BambuGreenBright else BambuBorderSubtle)
    ) {
        Box(modifier = Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
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
        modifier = modifier.clickable(
            role = Role.Button,
            onClickLabel = label,
            onClick = onClick
        ),
        color = BambuSlateElevated,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, axisColor.copy(alpha = 0.65f))
    ) {
        Box(modifier = Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
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
                text = stringResource(R.string.empty_privacy_badge),
                backgroundColor = BambuGreenDim,
                contentColor = BambuGreenBright
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.empty_subtitle),
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
                    Text(stringResource(R.string.action_open_file), fontWeight = FontWeight.Bold)
                }
                OutlinedButton(
                    onClick = onLoadSampleClick,
                    border = BorderStroke(1.dp, BambuBorderSubtle),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.action_load_sample), color = BambuTextPrimary)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.about_title),
                style = MaterialTheme.typography.labelSmall,
                color = BambuTextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(R.string.about_title),
                        onClick = onPrivacyInfoClick
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp)
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
                text = "${stringResource(R.string.loading_title)}... $pct%",
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
                text = stringResource(R.string.error_title),
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
                    Text(stringResource(R.string.action_open_file), color = Color.White)
                }
                OutlinedButton(
                    onClick = onSampleClick,
                    border = BorderStroke(1.dp, BambuBorderSubtle)
                ) {
                    Text(stringResource(R.string.action_load_sample), color = BambuTextPrimary)
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
                    text = stringResource(R.string.about_title),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TechnicalBadge(
                    text = stringResource(R.string.about_version),
                    backgroundColor = BambuGreenDim,
                    contentColor = BambuGreenBright
                )
                Text(
                    text = stringResource(R.string.about_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BambuTextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${stringResource(R.string.about_bullet1)}\n${stringResource(R.string.about_bullet2)}\n${stringResource(R.string.about_bullet3)}",
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
                Text(stringResource(R.string.action_understood), fontWeight = FontWeight.Bold)
            }
        }
    )
}
