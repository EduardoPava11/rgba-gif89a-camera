package com.rgbagif.logging

import android.util.Log
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Simple JSON structured logging utility for debugging.
 * Outputs JSON-formatted log messages to logcat.
 */
object JsonLog {
    private const val TAG = "RGBAGif89a"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    
    /**
     * Log a debug message with optional key-value pairs.
     */
    fun d(message: String, vararg pairs: Pair<String, Any?>) {
        log(Log.DEBUG, message, *pairs)
    }
    
    /**
     * Log an info message with optional key-value pairs.
     */
    fun i(message: String, vararg pairs: Pair<String, Any?>) {
        log(Log.INFO, message, *pairs)
    }
    
    /**
     * Log a warning message with optional key-value pairs.
     */
    fun w(message: String, vararg pairs: Pair<String, Any?>) {
        log(Log.WARN, message, *pairs)
    }
    
    /**
     * Log an error message with optional key-value pairs.
     */
    fun e(message: String, vararg pairs: Pair<String, Any?>) {
        log(Log.ERROR, message, *pairs)
    }
    
    private fun log(priority: Int, message: String, vararg pairs: Pair<String, Any?>) {
        try {
            val json = JSONObject().apply {
                put("timestamp", dateFormat.format(Date()))
                put("level", priorityToString(priority))
                put("message", message)
                
                if (pairs.isNotEmpty()) {
                    val data = JSONObject()
                    pairs.forEach { (key, value) ->
                        when (value) {
                            is Number -> data.put(key, value)
                            is Boolean -> data.put(key, value)
                            is String -> data.put(key, value)
                            is FloatArray -> data.put(key, value.joinToString(","))
                            is IntArray -> data.put(key, value.joinToString(","))
                            null -> data.put(key, JSONObject.NULL)
                            else -> data.put(key, value.toString())
                        }
                    }
                    put("data", data)
                }
            }
            
            Log.println(priority, TAG, json.toString())
        } catch (e: Exception) {
            // Fallback to simple logging if JSON fails
            Log.println(priority, TAG, "$message ${pairs.joinToString { "${it.first}=${it.second}" }}")
        }
    }
    
    private fun priorityToString(priority: Int): String = when (priority) {
        Log.VERBOSE -> "VERBOSE"
        Log.DEBUG -> "DEBUG"
        Log.INFO -> "INFO"
        Log.WARN -> "WARN"
        Log.ERROR -> "ERROR"
        Log.ASSERT -> "ASSERT"
        else -> "UNKNOWN"
    }
}