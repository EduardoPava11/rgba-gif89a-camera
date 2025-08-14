package com.rgbagif.milestones

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuration for JNI fast-path vs UniFFI path selection
 * Allows runtime toggling for benchmarking
 */
object FastPathConfig {
    private const val PREFS_NAME = "m1_fast_path_config"
    private const val KEY_USE_FAST_PATH = "use_jni_fast_path"
    private const val KEY_BENCHMARK_MODE = "benchmark_mode"
    
    private var prefs: SharedPreferences? = null
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Enable/disable JNI fast-path for CBOR writes
     * Default: true (use fast-path if available)
     */
    var useFastPath: Boolean
        get() = prefs?.getBoolean(KEY_USE_FAST_PATH, true) ?: true
        set(value) {
            prefs?.edit()?.putBoolean(KEY_USE_FAST_PATH, value)?.apply()
        }
    
    /**
     * Enable benchmark mode - writes performance metrics for each frame
     * Default: false
     */
    var benchmarkMode: Boolean
        get() = prefs?.getBoolean(KEY_BENCHMARK_MODE, false) ?: false
        set(value) {
            prefs?.edit()?.putBoolean(KEY_BENCHMARK_MODE, value)?.apply()
        }
    
    /**
     * Check if JNI fast-path should be used
     * Returns true only if enabled AND available
     */
    fun shouldUseFastPath(): Boolean {
        return useFastPath && com.rgbagif.native.M1Fast.isAvailable()
    }
}