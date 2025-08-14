package com.rgbagif.camera

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.TextureView
import com.rgbagif.logging.JsonLog

/**
 * Aggressive TextureView transform fixer
 * 
 * The issue: Transform is being applied but not visible, suggesting we're 
 * transforming the wrong instance or at the wrong time.
 */
object TextureViewFixer {
    
    private const val TAG = "TextureViewFixer"
    
    /**
     * Force fix the transform by finding and transforming ALL TextureViews
     */
    fun forceFixAllTextureViews(rootView: android.view.View) {
        val textureViews = mutableListOf<TextureView>()
        findAllTextureViews(rootView, textureViews)
        
        // JsonLog.i("camera_event")
        
        textureViews.forEach { tv ->
            applyCorrectTransform(tv)
        }
    }
    
    /**
     * Recursively find all TextureViews in view hierarchy
     */
    private fun findAllTextureViews(view: android.view.View, list: MutableList<TextureView>) {
        if (view is TextureView) {
            list.add(view)
            val tvId = System.identityHashCode(view)
        // JsonLog.i("camera_event")
        }
        
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findAllTextureViews(view.getChildAt(i), list)
            }
        }
    }
    
    /**
     * Apply the correct WYSIWYG transform
     */
    private fun applyCorrectTransform(textureView: TextureView) {
        val tvId = System.identityHashCode(textureView)
        
        if (!textureView.isAttachedToWindow) {
        // JsonLog.i("camera_event")
            return
        }
        
        if (textureView.width <= 0 || textureView.height <= 0) {
        // JsonLog.i("camera_event")
            return
        }
        
        // Apply the correct transform
        val matrix = Matrix()
        
        // Calculate proper scale and translation
        val viewSize = Math.min(textureView.width, textureView.height).toFloat()
        val scale = viewSize / 1440f  // 1080 / 1440 = 0.75
        
        // Apply scale first
        matrix.setScale(scale, scale)
        
        // Then translate to center the crop
        val tx = -240f * scale  // -240 * 0.75 = -180
        val ty = 0f
        matrix.postTranslate(tx, ty)
        
        // Apply it multiple ways to ensure it sticks
        textureView.setTransform(matrix)
        
        // Also try setting it on the SurfaceTextureListener
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                textureView.setTransform(matrix)
        // JsonLog.i("camera_event")
            }
            
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                textureView.setTransform(matrix)
        // JsonLog.i("camera_event")
            }
            
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
            
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                // Apply on first frame update
                textureView.setTransform(matrix)
                // Remove listener after first update to avoid spam
                textureView.surfaceTextureListener = null
        // JsonLog.i("camera_event")
            }
        }
        
        // JsonLog.i("camera_event")
    }
    
    /**
     * Install a watcher that continuously fixes the transform
     */
    fun installContinuousFixer(textureView: TextureView) {
        val tvId = System.identityHashCode(textureView)
        
        // JsonLog.i("camera_event")
        
        // Run every frame
        val runnable = object : Runnable {
            private var frameCount = 0
            
            override fun run() {
                if (!textureView.isAttachedToWindow) {
        // JsonLog.i("camera_event")
                    return
                }
                
                frameCount++
                
                // Apply transform every 10 frames
                if (frameCount % 10 == 0) {
                    val matrix = Matrix()
                    val scale = 0.75f
                    matrix.setScale(scale, scale)
                    matrix.postTranslate(-180f, 0f)
                    textureView.setTransform(matrix)
                    
                    if (frameCount == 10) {
        // JsonLog.i("camera_event")
                    }
                }
                
                // Continue running
                textureView.postOnAnimation(this)
            }
        }
        
        textureView.postOnAnimation(runnable)
    }
}