package com.rgbagif.log

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.measureNanoTime

/**
 * Structured logging for RGBA→GIF89a pipeline milestones
 * 
 * Tag policy: RGBA.CAPTURE, RGBA.CBOR, RGBA.PNG, RGBA.DOWNSIZE, RGBA.QUANT, RGBA.GIF, RGBA.UI, RGBA.PERF, RGBA.ERROR
 * Level policy: DEBUG (per-frame), INFO (phase boundaries), WARN (anomalies), ERROR (failures)
 */
object LogEvent {
    
    // Tag constants
    const val TAG_CAPTURE = "RGBA.CAPTURE"
    const val TAG_CBOR = "RGBA.CBOR"
    const val TAG_PNG = "RGBA.PNG"
    const val TAG_DOWNSIZE = "RGBA.DOWNSIZE"
    const val TAG_QUANT = "RGBA.QUANT"
    const val TAG_GIF = "RGBA.GIF"
    const val TAG_UI = "RGBA.UI"
    const val TAG_PERF = "RGBA.PERF"
    const val TAG_ERROR = "RGBA.ERROR"
    
    // Error codes
    const val E_CBOR_WRITE = "E_CBOR_WRITE"
    const val E_CBOR_READ = "E_CBOR_READ"
    const val E_PNG_ENCODE = "E_PNG_ENCODE"
    const val E_NN_MISSING = "E_NN_MISSING"
    const val E_NN_INFER = "E_NN_INFER"
    const val E_QUANTIZE = "E_QUANTIZE"
    const val E_GIF_ENCODE = "E_GIF_ENCODE"
    const val E_IO = "E_IO"
    const val E_PERM = "E_PERM"
    const val E_TIMEOUT = "E_TIMEOUT"
    
    // Event types
    const val EVENT_CAPTURE_START = "capture_start"
    const val EVENT_FRAME_SAVED = "frame_saved"
    const val EVENT_CAPTURE_DONE = "capture_done"
    const val EVENT_PNG_GENERATED = "png_generated"
    const val EVENT_DOWNSIZE_START = "downsize_start"
    const val EVENT_DOWNSIZE_DONE = "downsize_done"
    const val EVENT_QUANT_START = "quant_start"
    const val EVENT_QUANT_DONE = "quant_done"
    const val EVENT_GIF_START = "gif_start"
    const val EVENT_GIF_DONE = "gif_done"
    const val EVENT_ERROR = "error"
    
    // Session ID format
    private val sessionFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    
    /**
     * Generate session ID: yyyyMMdd_HHmmss_xxx
     */
    fun generateSessionId(): String {
        val timestamp = sessionFormat.format(Date())
        val random = (100..999).random()
        return "${timestamp}_$random"
    }
    
    /**
     * Base log entry builder
     */
    data class Entry(
        val event: String,
        val milestone: String,
        val sessionId: String,
        val tag: String = TAG_CAPTURE,
        val level: Int = Log.INFO,
        val frameIndex: Int? = null,
        val sizePx: String? = null,
        val bytesIn: Long? = null,
        val bytesOut: Long? = null,
        val dtMs: Double? = null,
        val dtMsCum: Double? = null,
        val ok: Boolean = true,
        val errCode: String? = null,
        val errMsg: String? = null,
        val extra: Map<String, Any>? = null
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("ts_ms", System.currentTimeMillis())
                put("event", event)
                put("milestone", milestone)
                put("session_id", sessionId)
                frameIndex?.let { put("frame_index", it) }
                sizePx?.let { put("size_px", it) }
                bytesIn?.let { put("bytes_in", it) }
                bytesOut?.let { put("bytes_out", it) }
                dtMs?.let { put("dt_ms", it) }
                dtMsCum?.let { put("dt_ms_cum", it) }
                put("ok", ok)
                errCode?.let { put("err_code", it) }
                errMsg?.let { put("err_msg", it) }
                extra?.let { put("extra", JSONObject(it)) }
            }
        }
        
        fun log() {
            val json = toJson().toString()
            Log.println(level, tag, json)
        }
    }
    
    /**
     * Measure block execution time in milliseconds
     */
    inline fun <T> measure(
        event: String,
        milestone: String,
        sessionId: String,
        tag: String = TAG_PERF,
        frameIndex: Int? = null,
        extra: Map<String, Any>? = null,
        block: () -> T
    ): T {
        val startNanos = SystemClock.elapsedRealtimeNanos()
        val result = try {
            block()
        } catch (e: Exception) {
            val dtMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
            Entry(
                event = EVENT_ERROR,
                milestone = milestone,
                sessionId = sessionId,
                tag = TAG_ERROR,
                level = Log.ERROR,
                frameIndex = frameIndex,
                dtMs = dtMs,
                ok = false,
                errCode = e.javaClass.simpleName,
                errMsg = e.message,
                extra = extra
            ).log()
            throw e
        }
        val dtMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
        
        Entry(
            event = event,
            milestone = milestone,
            sessionId = sessionId,
            tag = tag,
            level = if (frameIndex != null) Log.DEBUG else Log.INFO,
            frameIndex = frameIndex,
            dtMs = dtMs,
            ok = true,
            extra = extra
        ).log()
        
        return result
    }
    
    /**
     * Phase timer for cumulative measurements
     */
    class PhaseTimer(
        private val milestone: String,
        private val sessionId: String,
        private val tag: String = TAG_PERF
    ) {
        private val startNanos = SystemClock.elapsedRealtimeNanos()
        private var lastCheckpointNanos = startNanos
        
        fun checkpoint(
            event: String,
            frameIndex: Int? = null,
            bytesIn: Long? = null,
            bytesOut: Long? = null,
            extra: Map<String, Any>? = null
        ) {
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val dtMs = (nowNanos - lastCheckpointNanos) / 1_000_000.0
            val dtMsCum = (nowNanos - startNanos) / 1_000_000.0
            
            Entry(
                event = event,
                milestone = milestone,
                sessionId = sessionId,
                tag = tag,
                level = if (frameIndex != null) Log.DEBUG else Log.INFO,
                frameIndex = frameIndex,
                bytesIn = bytesIn,
                bytesOut = bytesOut,
                dtMs = dtMs,
                dtMsCum = dtMsCum,
                extra = extra
            ).log()
            
            lastCheckpointNanos = nowNanos
        }
        
        fun done(
            event: String,
            summary: Map<String, Any>? = null
        ) {
            val dtMsCum = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0
            
            Entry(
                event = event,
                milestone = milestone,
                sessionId = sessionId,
                tag = tag,
                level = Log.INFO,
                dtMsCum = dtMsCum,
                extra = summary
            ).log()
        }
    }
    
    /**
     * M1: Capture logging helpers
     */
    fun logCaptureStart(sessionId: String, targetFrames: Int = 81) {
        Entry(
            event = EVENT_CAPTURE_START,
            milestone = "M1",
            sessionId = sessionId,
            tag = TAG_CAPTURE,
            extra = mapOf("target_frames" to targetFrames, "size_px" to "729x729")
        ).log()
    }
    
    fun logFrameSaved(sessionId: String, frameIndex: Int, bytesOut: Long, dtMs: Double) {
        Entry(
            event = EVENT_FRAME_SAVED,
            milestone = "M1",
            sessionId = sessionId,
            tag = TAG_CBOR,
            level = Log.DEBUG,
            frameIndex = frameIndex,
            sizePx = "729x729",
            bytesOut = bytesOut,
            dtMs = dtMs
        ).log()
    }
    
    fun logCaptureDone(sessionId: String, frameCount: Int, dtMsCum: Double) {
        Entry(
            event = EVENT_CAPTURE_DONE,
            milestone = "M1",
            sessionId = sessionId,
            tag = TAG_CAPTURE,
            dtMsCum = dtMsCum,
            extra = mapOf("frame_count" to frameCount)
        ).log()
    }
    
    /**
     * M2: Downsize logging helpers
     */
    fun logDownsizeStart(sessionId: String, frameCount: Int) {
        Entry(
            event = EVENT_DOWNSIZE_START,
            milestone = "M2",
            sessionId = sessionId,
            tag = TAG_DOWNSIZE,
            extra = mapOf("frame_count" to frameCount, "target_size" to "81x81")
        ).log()
    }
    
    fun logDownsizeFrame(sessionId: String, frameIndex: Int, bytesIn: Long, bytesOut: Long, dtMs: Double) {
        Entry(
            event = "frame_downsized",
            milestone = "M2",
            sessionId = sessionId,
            tag = TAG_DOWNSIZE,
            level = Log.DEBUG,
            frameIndex = frameIndex,
            sizePx = "81x81",
            bytesIn = bytesIn,
            bytesOut = bytesOut,
            dtMs = dtMs
        ).log()
    }
    
    fun logDownsizeDone(sessionId: String, okFrames: Int, errFrames: Int, avgMs: Double, p95Ms: Double, dtMsCum: Double) {
        Entry(
            event = EVENT_DOWNSIZE_DONE,
            milestone = "M2",
            sessionId = sessionId,
            tag = TAG_DOWNSIZE,
            dtMsCum = dtMsCum,
            extra = mapOf(
                "ok_frames" to okFrames,
                "err_frames" to errFrames,
                "avg_ms" to avgMs,
                "p95_ms" to p95Ms
            )
        ).log()
    }
    
    /**
     * M3: Quantization & GIF logging helpers
     */
    fun logQuantStart(sessionId: String, frameCount: Int) {
        Entry(
            event = EVENT_QUANT_START,
            milestone = "M3",
            sessionId = sessionId,
            tag = TAG_QUANT,
            extra = mapOf("frame_count" to frameCount, "target_palette" to 256)
        ).log()
    }
    
    fun logQuantFrame(sessionId: String, frameIndex: Int, paletteSize: Int, dtMs: Double) {
        Entry(
            event = "frame_quantized",
            milestone = "M3",
            sessionId = sessionId,
            tag = TAG_QUANT,
            level = Log.DEBUG,
            frameIndex = frameIndex,
            dtMs = dtMs,
            extra = mapOf("palette" to paletteSize)
        ).log()
    }
    
    fun logGifExport(sessionId: String, frameCount: Int, delayCentiseconds: Int, totalBytes: Long, outputPath: String, dtMs: Double) {
        Entry(
            event = EVENT_GIF_DONE,
            milestone = "M3",
            sessionId = sessionId,
            tag = TAG_GIF,
            bytesOut = totalBytes,
            dtMs = dtMs,
            extra = mapOf(
                "frame_count" to frameCount,
                "delay_cs" to delayCentiseconds,
                "output_path" to outputPath
            )
        ).log()
    }
}