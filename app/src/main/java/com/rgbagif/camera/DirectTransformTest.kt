package com.rgbagif.camera

import android.graphics.Matrix
import android.view.TextureView
import com.rgbagif.logging.JsonLog

/**
 * Direct transform test to verify if transforms work at all
 */
object DirectTransformTest {
    
    /**
     * Apply an obvious test transform that should be immediately visible
     */
    fun applyObviousTestTransform(textureView: TextureView) {
        val tvId = System.identityHashCode(textureView)
        
        // Try a very obvious transform - rotate 45 degrees and scale 0.5
        val matrix = Matrix()
        matrix.postRotate(45f, 540f, 540f)  // Rotate around center
        matrix.postScale(0.5f, 0.5f, 540f, 540f)  // Scale down by half
        
        textureView.setTransform(matrix)
        
        // JsonLog.i("camera_event")
        
        // Also try clearing transform to see if that works
        textureView.postDelayed({
            textureView.setTransform(Matrix())  // Identity matrix
        // JsonLog.i("camera_event")
        }, 3000)
        
        // Then try the correct transform
        textureView.postDelayed({
            val correctMatrix = Matrix()
            correctMatrix.setScale(0.75f, 0.75f)
            correctMatrix.postTranslate(-180f, 0f)
            textureView.setTransform(correctMatrix)
        // JsonLog.i("camera_event")
        }, 6000)
    }
}