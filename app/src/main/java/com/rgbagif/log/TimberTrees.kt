package com.rgbagif.log

import android.util.Log
import timber.log.Timber
import org.json.JSONObject

/**
 * Timber trees for structured logging
 */

/**
 * JSON-formatted log tree that outputs single-line JSON to Logcat
 */
class JsonTree : Timber.Tree() {
    
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // If message is already JSON, pass through
        if (message.startsWith("{") && message.endsWith("}")) {
            Log.println(priority, tag ?: "RGBA", message)
            return
        }
        
        // Otherwise, wrap in JSON structure
        val json = JSONObject().apply {
            put("ts_ms", System.currentTimeMillis())
            put("level", priorityToString(priority))
            tag?.let { put("tag", it) }
            put("msg", message)
            t?.let { 
                put("err_type", it.javaClass.simpleName)
                put("err_msg", it.message)
            }
        }
        
        Log.println(priority, tag ?: "RGBA", json.toString())
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

/**
 * Release tree that only logs INFO and above
 */
class ReleaseTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.INFO) return
        
        // In release, still use JSON format but suppress DEBUG
        if (message.startsWith("{") && message.endsWith("}")) {
            Log.println(priority, tag ?: "RGBA", message)
        }
    }
}