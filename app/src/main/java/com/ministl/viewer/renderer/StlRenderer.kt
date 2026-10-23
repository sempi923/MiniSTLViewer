package com.ministl.viewer.renderer

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.ministl.viewer.model.StlMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class StlRenderer : GLSurfaceView.Renderer {

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var programId = 0
    private var vboIds = IntArray(3) // 0: Vertices, 1: Normals, 2: Barycentric coords
    private var triangleCount = 0
    private var pendingMesh: StlMesh? = null

    // Estado de cámara e interfaz
    @Volatile var isWireframe: Boolean = false
    @Volatile var isDarkMode: Boolean = true
    @Volatile var cameraDistance: Float = 150f
    @Volatile var panX: Float = 0f
    @Volatile var panY: Float = 0f
    @Volatile var rotX: Float = -30f
    @Volatile var rotY: Float = 35f
    private var defaultDistance: Float = 150f

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uMVMatrix;
        attribute vec4 aPosition;
        attribute vec3 aNormal;
        attribute vec3 aBarycentric;
        varying vec3 vNormal;
        varying vec3 vBarycentric;

        void main() {
            vNormal = normalize(mat3(uMVMatrix) * aNormal);
            vBarycentric = aBarycentric;
            gl_Position = uMVPMatrix * aPosition;
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        #extension GL_OES_standard_derivatives : enable
        precision mediump float;
        varying vec3 vNormal;
        varying vec3 vBarycentric;
        uniform int uWireframe;
        uniform vec3 uBaseColor;

        void main() {
            vec3 lightDir = normalize(vec3(0.35, 0.65, 1.0));
            // Iluminación de dos caras para mallas abiertas
            float diff = abs(dot(normalize(vNormal), lightDir));
            float ambient = 0.28;
            vec3 litColor = uBaseColor * (ambient + (1.0 - ambient) * diff);

            if (uWireframe == 1) {
                vec3 d = fwidth(vBarycentric);
                vec3 a3 = smoothstep(vec3(0.0), d * 1.35, vBarycentric);
                float edgeFactor = min(min(a3.x, a3.y), a3.z);
                if (edgeFactor > 0.85) discard;
                gl_FragColor = vec4(mix(uBaseColor * 1.25, litColor, edgeFactor), 1.0);
            } else {
                gl_FragColor = vec4(litColor, 1.0);
            }
        }
    """.trimIndent()

    fun setMesh(mesh: StlMesh) {
        pendingMesh = mesh
        defaultDistance = mesh.boundingBox.maxDimension * 1.85f
        resetCamera()
    }

    fun resetCamera() {
        cameraDistance = defaultDistance
        panX = 0f
        panY = 0f
        rotX = -25f
        rotY = 35f
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        programId = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vShader)
            GLES20.glAttachShader(prog, fShader)
            GLES20.glLinkProgram(prog)
        }
        GLES20.glGenBuffers(3, vboIds, 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height.coerceAtLeast(1).toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 45f, ratio, 0.1f, 50000f)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Actualizar color de fondo según Dark Mode
        if (isDarkMode) {
            GLES20.glClearColor(0.09f, 0.10f, 0.12f, 1.0f)
        } else {
            GLES20.glClearColor(0.94f, 0.95f, 0.97f, 1.0f)
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Subir nueva malla a los VBOs en el hilo GL si está pendiente
        pendingMesh?.let { mesh ->
            uploadMeshToGpu(mesh)
            pendingMesh = null
        }

        if (triangleCount == 0) return

        // Actualizar plano cercano/lejano dinámicamente según la distancia de la cámara
        val near = (cameraDistance * 0.01f).coerceAtLeast(0.05f)
        val far = (cameraDistance * 20f).coerceAtLeast(1000f)
        projectionMatrix[10] = -(far + near) / (far - near)
        projectionMatrix[14] = -(2f * far * near) / (far - near)

        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, cameraDistance, 0f, 0f, 0f, 0f, 1f, 0f)

        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.translateM(modelMatrix, 0, panX, panY, 0f)
        Matrix.rotateM(modelMatrix, 0, rotX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, rotY, 0f, 1f, 0f)

        Matrix.multiplyMM(mvMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, mvMatrix, 0)

        GLES20.glUseProgram(programId)

        val uMvpHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        val uMvHandle = GLES20.glGetUniformLocation(programId, "uMVMatrix")
        val uWireHandle = GLES20.glGetUniformLocation(programId, "uWireframe")
        val uColorHandle = GLES20.glGetUniformLocation(programId, "uBaseColor")

        GLES20.glUniformMatrix4fv(uMvpHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uMvHandle, 1, false, mvMatrix, 0)
        GLES20.glUniform1i(uWireHandle, if (isWireframe) 1 else 0)

        if (isDarkMode) {
            GLES20.glUniform3f(uColorHandle, 0.38f, 0.72f, 0.98f)
        } else {
            GLES20.glUniform3f(uColorHandle, 0.16f, 0.48f, 0.82f)
        }

        val posHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        val normHandle = GLES20.glGetAttribLocation(programId, "aNormal")
        val baryHandle = GLES20.glGetAttribLocation(programId, "aBarycentric")

        // Enlazar VBO de Vértices
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 3, GLES20.GL_FLOAT, false, 0, 0)

        // Enlazar VBO de Normales
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[1])
        GLES20.glEnableVertexAttribArray(normHandle)
        GLES20.glVertexAttribPointer(normHandle, 3, GLES20.GL_FLOAT, false, 0, 0)

        // Enlazar VBO de Coordenadas Baricéntricas (para Wireframe limpio)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[2])
        GLES20.glEnableVertexAttribArray(baryHandle)
        GLES20.glVertexAttribPointer(baryHandle, 3, GLES20.GL_FLOAT, false, 0, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triangleCount * 3)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(normHandle)
        GLES20.glDisableVertexAttribArray(baryHandle)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun uploadMeshToGpu(mesh: StlMesh) {
        triangleCount = mesh.triangleCount
        val byteCount = triangleCount * 9 * 4

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, byteCount, mesh.vertexBuffer, GLES20.GL_STATIC_DRAW)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[1])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, byteCount, mesh.normalBuffer, GLES20.GL_STATIC_DRAW)

        // Generar coordenadas baricéntricas (1,0,0), (0,1,0), (0,0,1) para el shader de wireframe
        val baryBuffer: FloatBuffer = ByteBuffer.allocateDirect(byteCount)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        for (i in 0 until triangleCount) {
            baryBuffer.put(1f).put(0f).put(0f)
            baryBuffer.put(0f).put(1f).put(0f)
            baryBuffer.put(0f).put(0f).put(1f)
        }
        baryBuffer.position(0)

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboIds[2])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, byteCount, baryBuffer, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun compileShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
        }
    }
}
