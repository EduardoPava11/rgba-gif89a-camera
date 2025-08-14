package com.rgbagif

import android.app.Application
import timber.log.Timber

/**
 * Application class for RGBA→GIF89a Camera
 * Initializes logging and other app-wide components
 */
class RgbaGifApp : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("🚀 RGBA→GIF89a Camera initialized in DEBUG mode")
        } else {
            // In release, only log important events (INFO and above)
            Timber.plant(ReleaseTree())
        }
        
        // Log app start
        Timber.i("APP_START { version: \"${BuildConfig.VERSION_NAME}\", versionCode: ${BuildConfig.VERSION_CODE} }")
    }
    
    /**
     * Release tree that only logs INFO and above
     */
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < android.util.Log.INFO) {
                return
            }
            super.log(priority, tag, message, t)
        }
    }
}