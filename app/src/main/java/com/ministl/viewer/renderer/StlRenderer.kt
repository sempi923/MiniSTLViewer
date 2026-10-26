package com.ministl.viewer.renderer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.ministl.viewer.model.BoundingBox
import com.ministl.viewer.model.CameraPreset
import com.ministl.viewer.model.ClippingAxis
import com.ministl.viewer.model.FilamentMaterial
import com.ministl.viewer.model.MeshRenderMode
import com.ministl.viewer.model.ModelTransform
import com.ministl.viewer.model.StlMesh
import com.ministl.viewer.util.MeshMath
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos

class StlRenderer : GLSurfaceView.Renderer {

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val modelRotMatrix = FloatArray(16)
    private val mvMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    // Programas OpenGL ES 2.0
    private var meshProgramId = 0
    private var bedProgramId = 0

    // VBOs del modelo STL: 0 = Vertices, 1 = Normals, 2 = Barycentric coords
    private val meshVboIds = IntArray(3)
    private var hasMeshVbos = false
    private var triangleCount = 0
    private var currentLocalBounds = BoundingBox(-20f, 20f, -20f, 20f, -20f, 20f)

    // VBOs de la Cama de Impresión PEI (256 x 256 mm): 0 = Placa sólida, 1 = Líneas de cuadrícula/ejes
    private val bedVboIds = IntArray(2)
    private var bedGridVertexCount = 0

    @Volatile private var pendingMesh: StlMesh? = null
    @Volatile private var pendingClearMesh: Boolean = false

    // Estado de Cámara (Z-Up estilo Bambu Studio)
    @Volatile var cameraDistance: Float = 220f
    @Volatile var panX: Float = 0f
    @Volatile var panY: Float = 0f
    @Volatile var rotX: Float = CameraPreset.ISO.pitchX // Pitch / Elevación
    @Volatile var rotY: Float = CameraPreset.ISO.yawZ   // Yaw / Azimut alrededor de Z
    private var defaultDistance: Float = 220f

    // Configuración Visual y Materiales
    @Volatile var renderMode: MeshRenderMode = MeshRenderMode.SOLID
    @Volatile var wireframeWidth: Float = 1.35f
    @Volatile var showPrintBed: Boolean = true
    @Volatile var materialPreset: FilamentMaterial = FilamentMaterial.PLA
    @Volatile var baseColorR: Float = 0.0f
    @Volatile var baseColorG: Float = 0.682f
    @Volatile var baseColorB: Float = 0.259f

    // Herramientas de Análisis Técnico (Shader)
    @Volatile var overhangInspectorEnabled: Boolean = false
    @Volatile var overhangAngleThresholdDeg: Float = 45f
    @Volatile var clippingEnabled: Boolean = false
    @Volatile var clippingAxis: ClippingAxis = ClippingAxis.Z
    @Volatile var clippingProgress: Float = 1.0f // 0.0 a 1.0 sobre el eje activo

    // Transformaciones del Modelo
    @Volatile var modelTransform: ModelTransform = ModelTransform()

    // Shader Principal del Modelo 3D (Blinn-Phong Doble Cara + Materiales + Clipping + Voladizos + Wireframe)
    private val meshVertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uMVMatrix;
        uniform mat4 uModelMatrix;
        uniform mat4 uModelRotMatrix;

        attribute vec4 aPosition;
        attribute vec3 aNormal;
        attribute vec3 aBarycentric;

        varying vec3 vViewNormal;
        varying vec3 vViewPos;
        varying vec3 vWorldPos;
        varying vec3 vWorldNormal;
        varying vec3 vLocalPos;
        varying vec3 vBarycentric;

        void main() {
            vec4 worldPos4 = uModelMatrix * aPosition;
            vWorldPos = worldPos4.xyz;
            vLocalPos = aPosition.xyz;
            vWorldNormal = normalize(mat3(uModelRotMatrix) * aNormal);

            vec4 viewPos4 = uMVMatrix * aPosition;
            vViewPos = viewPos4.xyz;
            vViewNormal = normalize(mat3(uMVMatrix) * aNormal);

            vBarycentric = aBarycentric;
            gl_Position = uMVPMatrix * aPosition;
        }
    """.trimIndent()

    private val meshFragmentShaderCode = """
        #extension GL_OES_standard_derivatives : enable
        precision mediump float;

        varying vec3 vViewNormal;
        varying vec3 vViewPos;
        varying vec3 vWorldPos;
        varying vec3 vWorldNormal;
        varying vec3 vLocalPos;
        varying vec3 vBarycentric;

        uniform int uRenderMode;       // 0: Solid, 1: Wireframe, 2: Solid + Wireframe
        uniform float uWireWidth;
        uniform vec3 uBaseColor;

        // Propiedades del Material (PLA, PETG, ABS, Matte, Silk, PA-CF)
        uniform float uAmbient;
        uniform float uDiffuse;
        uniform float uSpecular;
        uniform float uShininess;
        uniform float uRoughness;
        uniform int uIsCarbonFiber;

        // Plano de Corte Transversal (Clipping Plane)
        uniform int uClipEnabled;
        uniform int uClipAxis;         // 0: X, 1: Y, 2: Z
        uniform float uClipLimit;

        // Inspector de Voladizos (Overhang Inspector > 45°)
        uniform int uOverhangMode;
        uniform float uOverhangCosLimit;

        void main() {
            // 1. Corte Transversal Interactivo (Clipping Plane)
            float axisCoord = vWorldPos.z;
            if (uClipAxis == 0) {
                axisCoord = vWorldPos.x;
            } else if (uClipAxis == 1) {
                axisCoord = vWorldPos.y;
            }

            if (uClipEnabled == 1 && axisCoord > uClipLimit) {
                discard;
            }

            // 2. Normal en espacio de vista (iluminación de doble cara para mallas abiertas o cortadas)
            vec3 N = normalize(vViewNormal);
            bool isBackFace = !gl_FrontFacing;
            if (isBackFace) {
                N = -N;
            }

            vec3 V = normalize(-vViewPos);

            // Tres fuentes de luz estilo estudio CAD (Key Light, Fill Light, Rim Light)
            vec3 L1 = normalize(vec3(0.42, 0.55, 0.72));
            vec3 L2 = normalize(vec3(-0.60, -0.35, 0.50));
            vec3 L3 = normalize(vec3(0.0, 0.80, -0.55));

            float diff1 = max(dot(N, L1), 0.0);
            float diff2 = max(dot(N, L2), 0.0) * 0.35;
            float diff3 = max(dot(N, L3), 0.0) * 0.22;
            float diffTotal = diff1 + diff2 + diff3;

            // Especular Blinn-Phong
            vec3 H1 = normalize(L1 + V);
            vec3 H2 = normalize(L2 + V);
            float spec1 = pow(max(dot(N, H1), 0.0), uShininess);
            float spec2 = pow(max(dot(N, H2), 0.0), uShininess * 0.6) * 0.25;
            float fresnel = pow(1.0 - max(dot(N, V), 0.0), 3.0) * (1.0 - uRoughness) * 0.22;

            vec3 surfaceColor = uBaseColor;

            // Micro-textura procedural para Fibra de Carbono (PAHT-CF)
            if (uIsCarbonFiber == 1) {
                float pattern = sin(vLocalPos.x * 3.5 + vLocalPos.y * 3.5) * cos(vLocalPos.z * 4.5);
                surfaceColor = mix(uBaseColor * 0.55, uBaseColor * 0.92, pattern * 0.5 + 0.5);
            }

            // 3. Detector de Voladizos (Overhang Angle Inspector)
            vec3 worldN = normalize(vWorldNormal);
            if (uOverhangMode == 1) {
                // Si la cara mira hacia la cama (-Z) superando el ángulo crítico y no es la base Z=0
                if (worldN.z < -uOverhangCosLimit && vWorldPos.z > 0.25) {
                    float severity = clamp((-worldN.z - uOverhangCosLimit) / (1.0 - uOverhangCosLimit + 0.001), 0.0, 1.0);
                    vec3 alertRed = vec3(1.0, 0.231, 0.188);
                    vec3 deepRed = vec3(0.82, 0.08, 0.08);
                    surfaceColor = mix(alertRed, deepRed, severity * 0.5);
                } else {
                    // Atenuar zonas seguras a gris técnico para resaltar el diagnóstico
                    float luma = dot(surfaceColor, vec3(0.299, 0.587, 0.114));
                    surfaceColor = mix(surfaceColor, vec3(luma * 0.85), 0.55);
                }
            }

            // Sombra ambiental hemisférica suave según orientación Z
            float hemi = 0.82 + 0.18 * worldN.z;
            vec3 ambientTerm = surfaceColor * uAmbient * hemi;
            vec3 diffuseTerm = surfaceColor * uDiffuse * diffTotal;
            vec3 specColor = mix(vec3(1.0), surfaceColor + vec3(0.35), 0.35);
            vec3 specularTerm = specColor * uSpecular * (spec1 + spec2 + fresnel);

            vec3 litColor = ambientTerm + diffuseTerm + specularTerm;

            // Si el plano de corte está activo, sombrear caras internas y resaltar el borde de sección
            if (uClipEnabled == 1) {
                if (isBackFace) {
                    litColor = mix(litColor * 0.65, vec3(0.0, 0.68, 0.26), 0.18);
                }
                float distToCut = abs(uClipLimit - axisCoord);
                if (distToCut < 0.38) {
                    litColor = mix(vec3(0.0, 0.85, 0.36), litColor, distToCut / 0.38);
                }
            }

            // 4. Modos de Malla (Sólido, Alambre, Sólido + Alambre)
            if (uRenderMode == 1 || uRenderMode == 2) {
                vec3 d = fwidth(vBarycentric);
                vec3 a3 = smoothstep(vec3(0.0), d * uWireWidth, vBarycentric);
                float edgeFactor = min(min(a3.x, a3.y), a3.z);

                if (uRenderMode == 1) {
                    if (edgeFactor > 0.85) discard;
                    vec3 wireOnlyColor = mix(vec3(0.0, 0.78, 0.34), surfaceColor * 1.35, 0.5);
                    gl_FragColor = vec4(wireOnlyColor, 1.0);
                } else {
                    vec3 overlayWireColor = vec3(0.07, 0.08, 0.10);
                    gl_FragColor = vec4(mix(overlayWireColor, litColor, edgeFactor), 1.0);
                }
            } else {
                gl_FragColor = vec4(litColor, 1.0);
            }
        }
    """.trimIndent()

    // Shader de la Cama de Impresión 3D Virtual (Textured PEI Sheet 256 x 256 mm)
    private val bedVertexShaderCode = """
        uniform mat4 uVPMatrix;
        attribute vec3 aPosition;
        attribute vec4 aColor;
        varying vec4 vColor;
        varying vec3 vPos;

        void main() {
            vColor = aColor;
            vPos = aPosition;
            gl_Position = uVPMatrix * vec4(aPosition, 1.0);
        }
    """.trimIndent()

    private val bedFragmentShaderCode = """
        precision mediump float;
        varying vec4 vColor;
        varying vec3 vPos;
        uniform int uIsPlateSurface;

        void main() {
            if (uIsPlateSurface == 1) {
                // Textura PEI granulada sutil y viñeteado radial
                float grain = fract(sin(dot(floor(vPos.xy * 3.5), vec2(12.9898, 78.233))) * 43758.5453);
                float radial = clamp(length(vPos.xy) / 180.0, 0.0, 1.0);
                vec3 peiColor = vColor.rgb + vec3((grain - 0.5) * 0.018) - vec3(radial * 0.03);
                gl_FragColor = vec4(peiColor, vColor.a);
            } else {
                gl_FragColor = vColor;
            }
        }
    """.trimIndent()

    fun setMesh(mesh: StlMesh) {
        pendingMesh = mesh
        defaultDistance = (mesh.boundingBox.maxDimension * 2.1f).coerceIn(80f, 8000f)
        resetCamera()
    }

    fun clearMesh() {
        pendingClearMesh = true
    }

    fun resetCamera() {
        cameraDistance = defaultDistance
        panX = 0f
        panY = 0f
        rotX = CameraPreset.ISO.pitchX
        rotY = CameraPreset.ISO.yawZ
    }

    fun applyCameraPreset(preset: CameraPreset) {
        panX = 0f
        panY = 0f
        rotX = preset.pitchX
        rotY = preset.yawZ
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val meshVShader = compileShader(GLES20.GL_VERTEX_SHADER, meshVertexShaderCode)
        val meshFShader = compileShader(GLES20.GL_FRAGMENT_SHADER, meshFragmentShaderCode)
        meshProgramId = createProgram(meshVShader, meshFShader)

        val bedVShader = compileShader(GLES20.GL_VERTEX_SHADER, bedVertexShaderCode)
        val bedFShader = compileShader(GLES20.GL_FRAGMENT_SHADER, bedFragmentShaderCode)
        bedProgramId = createProgram(bedVShader, bedFShader)

        initPrintBedBuffers()
        hasMeshVbos = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.coerceAtLeast(1).toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 42f, ratio, 0.5f, 30000f)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Fondo grafito oscuro técnico (#121316)
        GLES20.glClearColor(0.071f, 0.075f, 0.086f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        if (pendingClearMesh) {
            releaseMeshVbos()
            pendingClearMesh = false
        }

        pendingMesh?.let { mesh ->
            uploadMeshToGpu(mesh)
            pendingMesh = null
        }

        // Ajuste dinámico de planos near/far
        val near = (cameraDistance * 0.01f).coerceAtLeast(0.1f)
        val far = (cameraDistance * 25f).coerceAtLeast(2500f)
        projectionMatrix[10] = -(far + near) / (far - near)
        projectionMatrix[14] = -(2f * far * near) / (far - near)

        // Calcular límites actuales del modelo transformado
        val tBounds = MeshMath.computeTransformedBounds(currentLocalBounds, modelTransform)
        val focusZ = if (triangleCount > 0) (tBounds.heightZ * 0.35f) else 0f

        // Construir ViewMatrix (Z-Up)
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, cameraDistance, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.translateM(viewMatrix, 0, panX, panY, 0f)
        Matrix.rotateM(viewMatrix, 0, rotX, 1f, 0f, 0f)
        Matrix.rotateM(viewMatrix, 0, rotY, 0f, 0f, 1f)
        Matrix.translateM(viewMatrix, 0, 0f, 0f, -focusZ)

        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // 1. Dibujar Cama de Impresión 256 x 256 mm (Textured PEI Sheet)
        if (showPrintBed) {
            drawPrintBed()
        }

        // 2. Dibujar Modelo STL si está cargado
        if (triangleCount > 0 && hasMeshVbos) {
            drawStlMesh(tBounds)
        }
    }

    private fun drawPrintBed() {
        GLES20.glUseProgram(bedProgramId)
        val uVpHandle = GLES20.glGetUniformLocation(bedProgramId, "uVPMatrix")
        val uIsSurfaceHandle = GLES20.glGetUniformLocation(bedProgramId, "uIsPlateSurface")
        val posHandle = GLES20.glGetAttribLocation(bedProgramId, "aPosition")
        val colHandle = GLES20.glGetAttribLocation(bedProgramId, "aColor")

        GLES20.glUniformMatrix4fv(uVpHandle, 1, false, vpMatrix, 0)
        val stride = 7 * 4 // 3 pos + 4 color

        // A) Placa base oscura PEI (en Z = -0.08 mm para evitar Z-fighting con Z = 0)
        GLES20.glUniform1i(uIsSurfaceHandle, 1)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bedVboIds[0])
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glEnableVertexAttribArray(colHandle)
        GLES20.glVertexAttribPointer(colHandle, 4, GLES20.GL_FLOAT, false, stride, 3 * 4)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)

        // B) Cuadrícula milimétrica (10 mm y 50 mm) + Ejes CAD X/Y/Z
        GLES20.glUniform1i(uIsSurfaceHandle, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bedVboIds[1])
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glVertexAttribPointer(colHandle, 4, GLES20.GL_FLOAT, false, stride, 3 * 4)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, bedGridVertexCount)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(colHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawStlMesh(tBounds: BoundingBox) {
        val transform = modelTransform
        val s = transform.scaleFactor

        // Calcular desplazamiento Z para apoyar el punto más bajo en la cama (Z = 0)
        val unshiftedBounds = MeshMath.computeTransformedBounds(
            currentLocalBounds,
            transform.copy(autoLayOnBed = false)
        )
        val zLift = if (transform.autoLayOnBed) -unshiftedBounds.minZ else 0f

        // Matriz de Rotación pura del modelo (para normales en espacio mundo)
        val rotMat = MeshMath.buildRotationMatrix(transform.rotXDeg, transform.rotYDeg, transform.rotZDeg)
        System.arraycopy(rotMat, 0, modelRotMatrix, 0, 16)

        // Matriz de Modelo completa: Traslación Z -> Escala -> Rotación
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, 0f, 0f, zLift)
        Matrix.scaleM(modelMatrix, 0, s, s, s)
        val tempMat = FloatArray(16)
        Matrix.multiplyMM(tempMat, 0, modelMatrix, 0, modelRotMatrix, 0)
        System.arraycopy(tempMat, 0, modelMatrix, 0, 16)

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

        GLES20.glUseProgram(meshProgramId)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(meshProgramId, "uMVPMatrix"), 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(meshProgramId, "uMVMatrix"), 1, false, mvMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(meshProgramId, "uModelMatrix"), 1, false, modelMatrix, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(meshProgramId, "uModelRotMatrix"), 1, false, modelRotMatrix, 0)

        val modeInt = when (renderMode) {
            MeshRenderMode.SOLID -> 0
            MeshRenderMode.WIREFRAME -> 1
            MeshRenderMode.SOLID_WIREFRAME -> 2
        }
        GLES20.glUniform1i(GLES20.glGetUniformLocation(meshProgramId, "uRenderMode"), modeInt)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgramId, "uWireWidth"), wireframeWidth)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(meshProgramId, "uBaseColor"), baseColorR, baseColorG, baseColorB)

        // Uniforms de Preset de Material (PLA, PETG, ABS, Matte, Silk, CF)
        val mat = materialPreset
        GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgramId, "uAmbient"), mat.ambient)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgramId, "uDiffuse"), mat.diffuse)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgramId, "uSpecular"), mat.specular)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgramId, "uShininess"), mat.shininess)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgramId, "uRoughness"), mat.roughness)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(meshProgramId, "uIsCarbonFiber"), if (mat.isCarbonFiber) 1 else 0)

        // Uniforms de Plano de Corte Transversal (Clipping Plane)
        val axisIndex = when (clippingAxis) {
            ClippingAxis.X -> 0
            ClippingAxis.Y -> 1
            ClippingAxis.Z -> 2
        }
        val (minCoord, maxCoord) = when (clippingAxis) {
            ClippingAxis.X -> tBounds.minX to tBounds.maxX
            ClippingAxis.Y -> tBounds.minY to tBounds.maxY
            ClippingAxis.Z -> tBounds.minZ to tBounds.maxZ
        }
        val clipLimitMm = minCoord + (maxCoord - minCoord) * clippingProgress.coerceIn(0f, 1f)

        GLES20.glUniform1i(GLES20.glGetUniformLocation(meshProgramId, "uClipEnabled"), if (clippingEnabled) 1 else 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(meshProgramId, "uClipAxis"), axisIndex)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgramId, "uClipLimit"), clipLimitMm)

        // Uniforms de Detector de Voladizos (> 45°)
        val cosLimit = cos(Math.toRadians(overhangAngleThresholdDeg.toDouble())).toFloat()
        GLES20.glUniform1i(GLES20.glGetUniformLocation(meshProgramId, "uOverhangMode"), if (overhangInspectorEnabled) 1 else 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(meshProgramId, "uOverhangCosLimit"), cosLimit)

        val posHandle = GLES20.glGetAttribLocation(meshProgramId, "aPosition")
        val normHandle = GLES20.glGetAttribLocation(meshProgramId, "aNormal")
        val baryHandle = GLES20.glGetAttribLocation(meshProgramId, "aBarycentric")

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, meshVboIds[0])
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, meshVboIds[1])
        GLES20.glEnableVertexAttribArray(normHandle)
        GLES20.glVertexAttribPointer(normHandle, 3, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, meshVboIds[2])
        GLES20.glEnableVertexAttribArray(baryHandle)
        GLES20.glVertexAttribPointer(baryHandle, 3, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triangleCount * 3)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(normHandle)
        GLES20.glDisableVertexAttribArray(baryHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Genera la cama de impresión estilo Bambu Lab (Textured PEI Plate 256 x 256 mm)
     * con subdivisiones cada 10 mm, líneas principales cada 50 mm y ejes de origen X/Y/Z.
     */
    private fun initPrintBedBuffers() {
        GLES20.glGenBuffers(2, bedVboIds, 0)

        val halfBed = 128f // 256 x 256 mm centrada en (0, 0)
        val zPlate = -0.12f
        val pr = 0.125f; val pg = 0.133f; val pb = 0.153f; val pa = 0.94f

        val plateFloats = floatArrayOf(
            -halfBed, -halfBed, zPlate, pr, pg, pb, pa,
             halfBed, -halfBed, zPlate, pr, pg, pb, pa,
             halfBed,  halfBed, zPlate, pr, pg, pb, pa,
            -halfBed, -halfBed, zPlate, pr, pg, pb, pa,
             halfBed,  halfBed, zPlate, pr, pg, pb, pa,
            -halfBed,  halfBed, zPlate, pr, pg, pb, pa
        )
        val plateBuffer = ByteBuffer.allocateDirect(plateFloats.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(plateFloats); position(0) }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bedVboIds[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, plateFloats.size * 4, plateBuffer, GLES20.GL_STATIC_DRAW)

        // Construir líneas de cuadrícula cada 10 mm y marcas cada 50 mm
        val lineData = ArrayList<Float>()
        fun addLine(
            x1: Float, y1: Float, z1: Float,
            x2: Float, y2: Float, z2: Float,
            r: Float, g: Float, b: Float, a: Float
        ) {
            lineData.add(x1); lineData.add(y1); lineData.add(z1)
            lineData.add(r);  lineData.add(g);  lineData.add(b); lineData.add(a)
            lineData.add(x2); lineData.add(y2); lineData.add(z2)
            lineData.add(r);  lineData.add(g);  lineData.add(b); lineData.add(a)
        }

        val zGrid = -0.02f
        var step = -120
        while (step <= 120) {
            val coord = step.toFloat()
            val isCenter = (step == 0)
            val isMajor50 = (step % 50 == 0)

            val r: Float; val g: Float; val b: Float; val a: Float
            when {
                isCenter -> { r = 0.30f; g = 0.34f; b = 0.40f; a = 0.85f }
                isMajor50 -> { r = 0.25f; g = 0.28f; b = 0.33f; a = 0.72f }
                else -> { r = 0.18f; g = 0.20f; b = 0.24f; a = 0.45f }
            }

            // Línea paralela a Y (variando X)
            addLine(coord, -halfBed, zGrid, coord, halfBed, zGrid, r, g, b, a)
            // Línea paralela a X (variando Y)
            addLine(-halfBed, coord, zGrid, halfBed, coord, zGrid, r, g, b, a)
            step += 10
        }

        // Borde perimetral de 256 x 256 mm en verde técnico Bambu Lab
        val br = 0.0f; val bg = 0.68f; val bb = 0.26f; val ba = 0.70f
        addLine(-halfBed, -halfBed, 0f,  halfBed, -halfBed, 0f, br, bg, bb, ba)
        addLine( halfBed, -halfBed, 0f,  halfBed,  halfBed, 0f, br, bg, bb, ba)
        addLine( halfBed,  halfBed, 0f, -halfBed,  halfBed, 0f, br, bg, bb, ba)
        addLine(-halfBed,  halfBed, 0f, -halfBed, -halfBed, 0f, br, bg, bb, ba)

        // Indicador de Origen de Esquina Frontal-Izquierda (-128, -128, 0) con Ejes X (Rojo), Y (Verde), Z (Azul)
        val axisLen = 35f
        addLine(-halfBed, -halfBed, 0.05f, -halfBed + axisLen, -halfBed, 0.05f, 1.0f, 0.30f, 0.31f, 1.0f) // +X Rojo
        addLine(-halfBed, -halfBed, 0.05f, -halfBed, -halfBed + axisLen, 0.05f, 0.0f, 0.75f, 0.31f, 1.0f) // +Y Verde
        addLine(-halfBed, -halfBed, 0.05f, -halfBed, -halfBed, axisLen,         0.23f, 0.51f, 0.96f, 1.0f) // +Z Azul

        val gridFloats = lineData.toFloatArray()
        bedGridVertexCount = gridFloats.size / 7
        val gridBuffer = ByteBuffer.allocateDirect(gridFloats.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(gridFloats); position(0) }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, bedVboIds[1])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, gridFloats.size * 4, gridBuffer, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /**
     * Libera explícitamente los VBOs previos antes de alojar una nueva malla para prevenir fugas de VRAM/OOM.
     */
    private fun releaseMeshVbos() {
        if (hasMeshVbos) {
            GLES20.glDeleteBuffers(3, meshVboIds, 0)
            meshVboIds[0] = 0
            meshVboIds[1] = 0
            meshVboIds[2] = 0
            hasMeshVbos = false
        }
        triangleCount = 0
    }

    private fun uploadMeshToGpu(mesh: StlMesh) {
        releaseMeshVbos()
        GLES20.glGenBuffers(3, meshVboIds, 0)
        hasMeshVbos = true

        triangleCount = mesh.triangleCount
        currentLocalBounds = mesh.boundingBox
        val byteCount = triangleCount * 9 * 4

        mesh.vertexBuffer.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, meshVboIds[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, byteCount, mesh.vertexBuffer, GLES20.GL_STATIC_DRAW)

        mesh.normalBuffer.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, meshVboIds[1])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, byteCount, mesh.normalBuffer, GLES20.GL_STATIC_DRAW)

        // Coordenadas baricéntricas (1,0,0), (0,1,0), (0,0,1) subidas por bloques para ahorrar hasta 72 MB de RAM
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, meshVboIds[2])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, byteCount, null, GLES20.GL_STATIC_DRAW)

        val chunkTris = 4096.coerceAtMost(triangleCount)
        val chunkBytes = chunkTris * 9 * 4
        val baryChunkBuffer: FloatBuffer = ByteBuffer.allocateDirect(chunkBytes)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (i in 0 until chunkTris) {
            baryChunkBuffer.put(1f).put(0f).put(0f)
            baryChunkBuffer.put(0f).put(1f).put(0f)
            baryChunkBuffer.put(0f).put(0f).put(1f)
        }

        var trisUploaded = 0
        while (trisUploaded < triangleCount) {
            val count = (triangleCount - trisUploaded).coerceAtMost(chunkTris)
            baryChunkBuffer.position(0)
            GLES20.glBufferSubData(
                GLES20.GL_ARRAY_BUFFER,
                trisUploaded * 9 * 4,
                count * 9 * 4,
                baryChunkBuffer
            )
            trisUploaded += count
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun compileShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Error al compilar shader OpenGL ($type): $log")
        }
        return shader
    }

    private fun createProgram(vertexShader: Int, fragmentShader: Int): Int {
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Error al enlazar programa OpenGL: $log")
        }
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
        return program
    }
}
