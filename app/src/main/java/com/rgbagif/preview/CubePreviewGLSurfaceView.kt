package com.rgbagif.preview

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.util.Log
import uniffi.m3gif.QuantizedCubeData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView for WYSIWYG preview of quantized cube data
 * 
 * Renders using:
 * - 256×1 RGB palette texture
 * - 81×81 R8 index textures (one per frame)
 * - Fragment shader performs palette lookup
 * 
 * This provides exact preview of what will be in the final GIF
 * without needing to encode to GIF format.
 */
class CubePreviewGLSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {
    
    private val renderer: CubeRenderer
    
    init {
        // Use OpenGL ES 2.0
        setEGLContextClientVersion(2)
        
        // Create renderer
        renderer = CubeRenderer()
        setRenderer(renderer)
        
        // Render when requested (not continuously)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
    
    /**
     * Set cube data for preview
     */
    fun setCubeData(cube: QuantizedCubeData) {
        queueEvent {
            renderer.setCubeData(cube)
            requestRender()
        }
    }
    
    /**
     * Set current frame to display
     */
    fun setFrame(frameIndex: Int) {
        queueEvent {
            renderer.setCurrentFrame(frameIndex)
            requestRender()
        }
    }
    
    /**
     * Play animation
     */
    fun playAnimation() {
        renderer.startAnimation()
        renderMode = RENDERMODE_CONTINUOUSLY
    }
    
    /**
     * Stop animation
     */
    fun stopAnimation() {
        renderer.stopAnimation()
        renderMode = RENDERMODE_WHEN_DIRTY
    }
    
    /**
     * Renderer implementation
     */
    private class CubeRenderer : GLSurfaceView.Renderer {
        companion object {
            private const val TAG = "CubeRenderer"
            
            // Vertex shader - simple quad
            private const val VERTEX_SHADER = """
                attribute vec4 a_Position;
                attribute vec2 a_TexCoord;
                varying vec2 v_TexCoord;
                
                void main() {
                    gl_Position = a_Position;
                    v_TexCoord = a_TexCoord;
                }
            """
            
            // Fragment shader - palette lookup
            private const val FRAGMENT_SHADER = """
                precision mediump float;
                
                uniform sampler2D u_IndexTexture;  // 81×81 R8 indices
                uniform sampler2D u_PaletteTexture; // 256×1 RGB palette
                varying vec2 v_TexCoord;
                
                void main() {
                    // Get palette index (0-255) from red channel
                    float index = texture2D(u_IndexTexture, v_TexCoord).r;
                    
                    // Convert to palette texture coordinate
                    float paletteCoord = index * 255.0 / 256.0 + 0.5 / 256.0;
                    
                    // Look up color from palette
                    vec3 color = texture2D(u_PaletteTexture, vec2(paletteCoord, 0.5)).rgb;
                    
                    gl_FragColor = vec4(color, 1.0);
                }
            """
        }
        
        private var program: Int = 0
        private var paletteTexture: Int = 0
        private var indexTextures = mutableListOf<Int>()
        private var vertexBuffer: FloatBuffer? = null
        private var texCoordBuffer: FloatBuffer? = null
        
        private var cubeData: QuantizedCubeData? = null
        private var currentFrame = 0
        private var isAnimating = false
        private var animationStartTime = 0L
        
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var indexTextureHandle = 0
        private var paletteTextureHandle = 0
        
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            // Set clear color
            GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
            
            // Create shader program
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            
            // Get handles
            positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
            texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
            indexTextureHandle = GLES20.glGetUniformLocation(program, "u_IndexTexture")
            paletteTextureHandle = GLES20.glGetUniformLocation(program, "u_PaletteTexture")
            
            // Create vertex data (full screen quad)
            val vertices = floatArrayOf(
                -1f, -1f, 0f,  // Bottom left
                 1f, -1f, 0f,  // Bottom right
                -1f,  1f, 0f,  // Top left
                 1f,  1f, 0f   // Top right
            )
            
            val texCoords = floatArrayOf(
                0f, 1f,  // Bottom left
                1f, 1f,  // Bottom right
                0f, 0f,  // Top left
                1f, 0f   // Top right
            )
            
            vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vertices)
                .apply { position(0) }
            
            texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(texCoords)
                .apply { position(0) }
        }
        
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }
        
        override fun onDrawFrame(gl: GL10?) {
            // Clear
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            
            // Check if we have data
            val cube = cubeData ?: return
            if (indexTextures.isEmpty()) return
            
            // Update frame if animating
            if (isAnimating) {
                val elapsed = System.currentTimeMillis() - animationStartTime
                val frameTime = 40L // 40ms per frame = 25fps
                currentFrame = ((elapsed / frameTime) % cube.indexedFrames.size).toInt()
            }
            
            // Use shader program
            GLES20.glUseProgram(program)
            
            // Bind vertex data
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
            
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            
            // Bind textures
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, indexTextures[currentFrame])
            GLES20.glUniform1i(indexTextureHandle, 0)
            
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, paletteTexture)
            GLES20.glUniform1i(paletteTextureHandle, 1)
            
            // Draw
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            
            // Cleanup
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
        }
        
        fun setCubeData(cube: QuantizedCubeData) {
            cubeData = cube
            
            // Delete old textures
            if (paletteTexture != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(paletteTexture), 0)
            }
            if (indexTextures.isNotEmpty()) {
                GLES20.glDeleteTextures(indexTextures.size, indexTextures.toIntArray(), 0)
                indexTextures.clear()
            }
            
            // Create palette texture (256×1 RGB)
            paletteTexture = createPaletteTexture(cube.globalPaletteRgb)
            
            // Create index textures (one per frame)
            cube.indexedFrames.forEach { frame ->
                indexTextures.add(createIndexTexture(frame, cube.width.toInt(), cube.height.toInt()))
            }
            
            Log.d(TAG, "Loaded cube: ${cube.indexedFrames.size} frames, palette size ${cube.globalPaletteRgb.size / 3}")
        }
        
        fun setCurrentFrame(frame: Int) {
            cubeData?.let {
                currentFrame = frame.coerceIn(0, it.indexedFrames.size - 1)
            }
        }
        
        fun startAnimation() {
            isAnimating = true
            animationStartTime = System.currentTimeMillis()
        }
        
        fun stopAnimation() {
            isAnimating = false
        }
        
        private fun createPaletteTexture(paletteRgb: List<UByte>): Int {
            val textureHandle = IntArray(1)
            GLES20.glGenTextures(1, textureHandle, 0)
            
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])
            
            // Convert UByte list to ByteBuffer
            val buffer = ByteBuffer.allocateDirect(paletteRgb.size)
                .order(ByteOrder.nativeOrder())
            paletteRgb.forEach { buffer.put(it.toByte()) }
            buffer.position(0)
            
            // Upload as 256×1 RGB texture
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGB,
                256, 1, 0,
                GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE,
                buffer
            )
            
            // Set texture parameters
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            
            return textureHandle[0]
        }
        
        private fun createIndexTexture(indices: List<UByte>, width: Int, height: Int): Int {
            val textureHandle = IntArray(1)
            GLES20.glGenTextures(1, textureHandle, 0)
            
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])
            
            // Convert UByte list to ByteBuffer
            val buffer = ByteBuffer.allocateDirect(indices.size)
                .order(ByteOrder.nativeOrder())
            indices.forEach { buffer.put(it.toByte()) }
            buffer.position(0)
            
            // Upload as width×height LUMINANCE (R8) texture
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
                width, height, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE,
                buffer
            )
            
            // Set texture parameters
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            
            return textureHandle[0]
        }
        
        private fun createProgram(vertexShaderCode: String, fragmentShaderCode: String): Int {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
            
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            
            // Check link status
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                val error = GLES20.glGetProgramInfoLog(program)
                GLES20.glDeleteProgram(program)
                throw RuntimeException("Failed to link program: $error")
            }
            
            return program
        }
        
        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            
            // Check compile status
            val compileStatus = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
            if (compileStatus[0] == 0) {
                val error = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw RuntimeException("Failed to compile shader: $error")
            }
            
            return shader
        }
    }
}