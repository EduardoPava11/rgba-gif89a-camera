package com.rgbagif.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Development settings for runtime toggles and debugging
 */
object DevSettings {
    private const val PREFS_NAME = "dev_settings"
    private const val KEY_SHOW_ANALYZER_STATS = "show_analyzer_stats"
    private const val KEY_QUANTIZER_TYPE = "quantizer_type"
    private const val KEY_ENABLE_DITHERING = "enable_dithering"
    private const val KEY_ENABLE_PERFETTO = "enable_perfetto"
    
    private var prefs: SharedPreferences? = null
    
    // Observable states
    private val _showAnalyzerStats = MutableStateFlow(false)
    val showAnalyzerStats: StateFlow<Boolean> = _showAnalyzerStats.asStateFlow()
    
    private val _quantizerType = MutableStateFlow(QuantizerType.MEDIAN_CUT)
    val quantizerType: StateFlow<QuantizerType> = _quantizerType.asStateFlow()
    
    private val _enableDithering = MutableStateFlow(false)
    val enableDithering: StateFlow<Boolean> = _enableDithering.asStateFlow()
    
    private val _enablePerfetto = MutableStateFlow(false)
    val enablePerfetto: StateFlow<Boolean> = _enablePerfetto.asStateFlow()
    
    enum class QuantizerType {
        MEDIAN_CUT,
        OCTREE,
        NEUQUANT
    }
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSettings()
    }
    
    private fun loadSettings() {
        prefs?.let { p ->
            _showAnalyzerStats.value = p.getBoolean(KEY_SHOW_ANALYZER_STATS, false)
            _quantizerType.value = QuantizerType.valueOf(
                p.getString(KEY_QUANTIZER_TYPE, QuantizerType.MEDIAN_CUT.name) 
                    ?: QuantizerType.MEDIAN_CUT.name
            )
            _enableDithering.value = p.getBoolean(KEY_ENABLE_DITHERING, false)
            _enablePerfetto.value = p.getBoolean(KEY_ENABLE_PERFETTO, false)
        }
    }
    
    fun setShowAnalyzerStats(show: Boolean) {
        _showAnalyzerStats.value = show
        prefs?.edit()?.putBoolean(KEY_SHOW_ANALYZER_STATS, show)?.apply()
    }
    
    fun setQuantizerType(type: QuantizerType) {
        _quantizerType.value = type
        prefs?.edit()?.putString(KEY_QUANTIZER_TYPE, type.name)?.apply()
    }
    
    fun setEnableDithering(enable: Boolean) {
        _enableDithering.value = enable
        prefs?.edit()?.putBoolean(KEY_ENABLE_DITHERING, enable)?.apply()
    }
    
    fun setEnablePerfetto(enable: Boolean) {
        _enablePerfetto.value = enable
        prefs?.edit()?.putBoolean(KEY_ENABLE_PERFETTO, enable)?.apply()
    }
    
    fun toggleAnalyzerStats() {
        setShowAnalyzerStats(!_showAnalyzerStats.value)
    }
    
    fun toggleDithering() {
        setEnableDithering(!_enableDithering.value)
    }
}