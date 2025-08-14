package com.rgbagif.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.rgbagif.log.CanonicalLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Manages GIF export to user-visible locations (Documents/Downloads)
 * using Storage Access Framework and MediaStore APIs
 */
class GifExportManager(private val activity: ComponentActivity) {
    
    companion object {
        private const val TAG = "GifExportManager"
        private const val PREFS_NAME = "gif_export_prefs"
        private const val KEY_PERSISTED_TREE_URI = "persisted_tree_uri"
    }
    
    // SAF launcher for Documents export
    private val safLauncher: ActivityResultLauncher<Intent>
    private var pendingGifFile: File? = null
    
    // Tree picker launcher for persistent folder
    private val treeLauncher: ActivityResultLauncher<Intent>
    
    init {
        // SAF Document Creator
        safLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == ComponentActivity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    pendingGifFile?.let { gifFile ->
                        exportToUri(gifFile, uri, "SAF")
                    }
                }
            } else {
                Log.i(TAG, "EXPORT_CANCELLED mode=SAF")
                Toast.makeText(activity, "Export canceled", Toast.LENGTH_SHORT).show()
            }
            pendingGifFile = null
        }
        
        // Tree picker for persistent folder
        treeLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == ComponentActivity.RESULT_OK) {
                result.data?.data?.let { treeUri ->
                    persistTreeUri(treeUri)
                    Toast.makeText(activity, "Folder selected for future exports", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Share GIF using Android's share intent
     */
    fun shareGif(gifFile: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                gifFile
            )
            
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/gif"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            activity.startActivity(Intent.createChooser(shareIntent, "Share GIF"))
            Log.i(TAG, "Sharing GIF: ${gifFile.name}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to share GIF", e)
            Toast.makeText(activity, "Failed to share GIF: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Export GIF to Documents using Storage Access Framework
     */
    fun exportToDocuments(gifFile: File) {
        if (!gifFile.exists()) {
            Log.e(TAG, "EXPORT_FAIL reason=\"GIF file not found: ${gifFile.absolutePath}\"")
            Toast.makeText(activity, "GIF file not found", Toast.LENGTH_SHORT).show()
            return
        }
        
        Log.i(TAG, "EXPORT_START mode=SAF source=${gifFile.absolutePath}")
        
        pendingGifFile = gifFile
        
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/gif"
            putExtra(Intent.EXTRA_TITLE, "final.gif")
        }
        
        safLauncher.launch(intent)
    }
    
    /**
     * Export GIF to Downloads using MediaStore (API 29+) or SAF fallback
     */
    suspend fun exportToDownloads(gifFile: File) = withContext(Dispatchers.IO) {
        if (!gifFile.exists()) {
            withContext(Dispatchers.Main) {
                Log.e(TAG, "EXPORT_FAIL reason=\"GIF file not found: ${gifFile.absolutePath}\"")
                Toast.makeText(activity, "GIF file not found", Toast.LENGTH_SHORT).show()
            }
            return@withContext
        }
        
        Log.i(TAG, "EXPORT_START mode=Downloads source=${gifFile.absolutePath}")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Use MediaStore for API 29+
            exportViaMediaStore(gifFile)
        } else {
            // Fall back to SAF for older devices
            withContext(Dispatchers.Main) {
                exportToDocuments(gifFile)
            }
        }
    }
    
    /**
     * Export via MediaStore.Downloads (API 29+)
     */
    private suspend fun exportViaMediaStore(gifFile: File) = withContext(Dispatchers.IO) {
        try {
            val resolver = activity.contentResolver
            
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "final_${System.currentTimeMillis()}.gif")
                put(MediaStore.Downloads.MIME_TYPE, "image/gif")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }
            
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(gifFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                // Mark as complete
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                
                val sizeBytes = gifFile.length()
                Log.i(TAG, "EXPORT_SUCCESS uri=$uri bytes=$sizeBytes")
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "GIF saved to Downloads", Toast.LENGTH_LONG).show()
                }
            } else {
                throw IOException("Failed to create MediaStore entry")
            }
        } catch (e: Exception) {
            Log.e(TAG, "EXPORT_FAIL reason=\"MediaStore error: ${e.message}\"")
            
            // Fall back to SAF
            withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Using document picker instead", Toast.LENGTH_SHORT).show()
                exportToDocuments(gifFile)
            }
        }
    }
    
    /**
     * Copy GIF to the selected URI
     */
    private fun exportToUri(gifFile: File, uri: Uri, mode: String) {
        try {
            activity.contentResolver.openOutputStream(uri)?.use { outputStream ->
                FileInputStream(gifFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            val sizeBytes = gifFile.length()
            Log.i(TAG, "EXPORT_SUCCESS uri=$uri bytes=$sizeBytes")
            
            Toast.makeText(
                activity, 
                "GIF exported successfully", 
                Toast.LENGTH_LONG
            ).show()
            
            // Optionally open the file
            offerToOpenFile(uri)
            
        } catch (e: Exception) {
            Log.e(TAG, "EXPORT_FAIL reason=\"${e.message}\"")
            Toast.makeText(activity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Pick a persistent folder for future exports
     */
    fun pickPersistentFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        
        treeLauncher.launch(intent)
    }
    
    /**
     * Persist the selected tree URI
     */
    private fun persistTreeUri(treeUri: Uri) {
        try {
            // Take persistable permission
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or 
                          Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            activity.contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            
            // Save to preferences
            activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_PERSISTED_TREE_URI, treeUri.toString())
                .apply()
                
            Log.i(TAG, "Persisted tree URI: $treeUri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist tree URI: ${e.message}")
        }
    }
    
    /**
     * Get persisted tree URI if available
     */
    fun getPersistedTreeUri(): Uri? {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(KEY_PERSISTED_TREE_URI, null)
        return uriString?.let { Uri.parse(it) }
    }
    
    /**
     * Offer to open the exported file
     */
    private fun offerToOpenFile(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/gif")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            if (intent.resolveActivity(activity.packageManager) != null) {
                // Could show a snackbar with "Open" action instead
                // For now, just log it
                Log.d(TAG, "File can be opened with: $uri")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open file: ${e.message}")
        }
    }
}