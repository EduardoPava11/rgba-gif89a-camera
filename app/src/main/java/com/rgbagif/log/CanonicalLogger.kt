package com.rgbagif.log

import android.util.Log
import timber.log.Timber

/**
 * Canonical logging for M1→M2→M3 pipeline verification
 * Single-line, grep-friendly structured events for testing
 */
object CanonicalLogger {
    
    /**
     * App startup logging
     */
    fun logAppStart(version: String, versionCode: Int) {
        Timber.i("APP_START version=$version code=$versionCode")
    }
    
    /**
     * Camera initialization logging
     */
    fun logCameraInit() {
        Timber.i("CAMERA_INIT outputFormat=RGBA_8888")
    }
    
    /**
     * M2 initialization logging
     */
    fun logM2InitStart() {
        Timber.i("M2_INIT start")
    }
    
    fun logM2InitSuccess(version: String) {
        Timber.i("M2_INIT ok version=$version")
    }
    
    fun logM2InitFail(error: String) {
        Timber.e("M2_INIT fail msg=$error")
    }
    
    /**
     * JNI/UniFFI library loading
     */
    fun logJniOk(library: String, version: String) {
        Timber.i("JNI_OK lib=$library version=$version")
    }
    
    fun logJniFail(library: String, error: String) {
        Timber.e("JNI_FAIL lib=$library error=$error")
    }
    
    /**
     * M2 per-frame processing
     */
    fun logM2FrameBegin(idx: Int) {
        Timber.i("M2_FRAME_BEGIN idx=$idx in=729x729 out=81x81")
    }
    
    fun logM2FrameEnd(idx: Int, pngSuccess: Boolean, path: String, bytes: Long) {
        Timber.i("M2_FRAME_END idx=$idx png=$pngSuccess path=$path bytes=$bytes")
    }
    
    fun logM2FrameFail(idx: Int, error: String) {
        Timber.e("M2_FRAME_FAIL idx=$idx error=$error")
    }
    
    /**
     * M2 mosaic generation
     */
    fun logM2MosaicDone(path: String, bytes: Long) {
        Timber.i("M2_MOSAIC_DONE path=$path bytes=$bytes")
    }
    
    /**
     * M2 completion
     */
    fun logM2Done(frames: Int, elapsedMs: Long) {
        Timber.i("M2_DONE frames=$frames elapsedMs=$elapsedMs")
    }
    
    /**
     * M1 logging (for completeness)
     */
    fun logM1Start(sessionId: String) {
        Timber.i("M1_START sessionId=$sessionId")
    }
    
    fun logM1FrameSaved(idx: Int, width: Int, height: Int, bytes: Int, path: String) {
        Timber.i("M1_FRAME_SAVED idx=$idx width=$width height=$height bytes=$bytes path=$path")
    }
    
    fun logM1Done(totalFrames: Int, elapsedMs: Long) {
        Timber.i("M1_DONE totalFrames=$totalFrames elapsedMs=$elapsedMs")
    }
    
    /**
     * M3 logging (for completeness)
     */
    fun logM3Start(sessionId: String) {
        Timber.i("M3_START sessionId=$sessionId")
    }
    
    fun logM3GifDone(frames: Int, fps: Int, sizeBytes: Long, loop: Boolean, path: String) {
        Timber.i("M3_GIF_DONE frames=$frames fps=$fps sizeBytes=$sizeBytes loop=$loop path=$path")
    }
    
    /**
     * M3 initialization logging
     */
    fun logM3InitStart() {
        Timber.i("M3_INIT start")
    }
    
    fun logM3InitSuccess(version: String) {
        Timber.i("M3_INIT ok version=$version")
    }
    
    fun logM3InitFail(error: String) {
        Timber.e("M3_INIT fail msg=$error")
    }
    
    /**
     * M3 session and export logging
     */
    fun logM3SessionStart(sessionId: String) {
        Timber.i("M3_SESSION_START sessionId=$sessionId")
    }
    
    fun logM3ExportBegin(frameCount: Int, fileName: String) {
        Timber.i("M3_EXPORT_BEGIN frames=$frameCount file=$fileName")
    }
    
    fun logM3ExportComplete(fileName: String, sizeBytes: Long, elapsedMs: Long) {
        Timber.i("M3_EXPORT_COMPLETE file=$fileName sizeBytes=$sizeBytes elapsedMs=$elapsedMs")
    }
    
    fun logM3ExportFail(error: String) {
        Timber.e("M3_EXPORT_FAIL error=$error")
    }
}
