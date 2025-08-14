package com.rgbagif.camera

import android.graphics.Matrix
import android.view.TextureView
import com.rgbagif.logging.JsonLog

/**
 * Transform debugger to verify we're transforming the correct TextureView
 */
object TransformDebugger {
    
    /**
     * Apply a test transform to verify the TextureView is live
     * This should visibly change the preview
     */
    fun applyTestTransform(textureView: TextureView) {
        val tvId = System.identityHashCode(textureView)
        
        // JsonLog.i("camera_event")
        
        // Apply obvious test transform - half scale
        val matrix = Matrix()
        matrix.setScale(0.5f, 0.5f)
        matrix.postTranslate(270f, 270f) // Center in 1080x1080 view
        
        textureView.setTransform(matrix)
    }
    
    /**
     * Force the correct WYSIWYG transform with extra verification
     */
    fun forceCorrectTransform(textureView: TextureView) {
        val tvId = System.identityHashCode(textureView)
        
        // Try different approaches to ensure transform is applied
        
        // Approach 1: Direct matrix application
        val matrix = Matrix()
        matrix.setScale(0.75f, 0.75f)
        matrix.postTranslate(-180f, 0f)
        
        textureView.setTransform(matrix)
        
        // JsonLog.i("camera_event")
        
        // Approach 2: Post to UI thread
        textureView.post {
            val matrix2 = Matrix()
            matrix2.setScale(0.75f, 0.75f)
            matrix2.postTranslate(-180f, 0f)
            textureView.setTransform(matrix2)
            
        // JsonLog.i("camera_event")
        }
        
        // Approach 3: Delayed application
        textureView.postDelayed({
            val matrix3 = Matrix()
            matrix3.setScale(0.75f, 0.75f)
            matrix3.postTranslate(-180f, 0f)
            textureView.setTransform(matrix3)
            
        // JsonLog.i("camera_event")
        }, 500)
    }
}