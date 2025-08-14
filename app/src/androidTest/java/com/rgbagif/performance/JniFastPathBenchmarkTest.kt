package com.rgbagif.performance

import android.content.Context
import android.os.Trace
import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rgbagif.log.LogEvent
import com.rgbagif.milestones.FastPathConfig
import com.rgbagif.native.M1Fast
import com.rgbagif.pipeline.FastPathFrameProcessor
import com.rgbagif.rust.RustCborWriter
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * JNI Fast-path vs UniFFI benchmark test
 * Validates ≥2× speedup on Galaxy S23
 * 
 * Contract: Capture at 729×729, export at 1440×1440
 */
@RunWith(AndroidJUnit4::class)
class JniFastPathBenchmarkTest {
    
    @get:Rule
    val benchmarkRule = BenchmarkRule()
    
    private lateinit var context: Context
    private lateinit var rustCborWriter: RustCborWriter
    private lateinit var processor: FastPathFrameProcessor
    private lateinit var testDir: File
    private lateinit var rgbaData: ByteArray
    private lateinit var directBuffer: ByteBuffer
    
    // Test parameters matching production
    // CAPTURE RESOLUTION: 729×729 (not 1440×1440)
    private val width = 729  // Capture width
    private val height = 729 // Capture height
    private val stride = width * 4
    private val frameSize = height * stride
    
    // Performance tracking
    private val jniWriteTimes = mutableListOf<Long>()
    private val uniffiWriteTimes = mutableListOf<Long>()
    private val benchmarkResults = mutableListOf<JSONObject>()
    
    companion object {
        private const val WARMUP_ITERATIONS = 3
        private const val TEST_ITERATIONS = 10
        private const val TARGET_SPEEDUP = 2.0
    }
    
    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        
        // Initialize components
        FastPathConfig.init(context)
        rustCborWriter = RustCborWriter(context)
        processor = FastPathFrameProcessor(rustCborWriter)
        
        // Create test directory
        testDir = File(context.cacheDir, "benchmark_${System.currentTimeMillis()}")
        testDir.mkdirs()
        
        // Generate test frame data (random but consistent)
        rgbaData = ByteArray(frameSize).apply {
            Random(42).nextBytes(this)
        }
        
        // Allocate direct buffer for JNI path
        directBuffer = M1Fast.allocateDirectBuffer(frameSize).apply {
            put(rgbaData)
            flip()
        }
        
        // Plant logging tree for metrics
        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (tag == "M1Fast" || tag == "FastPathProcessor") {
                    // Capture performance logs
                    try {
                        if (message.contains("written in")) {
                            val ms = message.substringAfter("in ").substringBefore("ms").toLongOrNull()
                            ms?.let {
                                if (FastPathConfig.shouldUseFastPath()) {
                                    jniWriteTimes.add(it)
                                } else {
                                    uniffiWriteTimes.add(it)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore parse errors
                    }
                }
            }
        })
    }
    
    @Test
    fun benchmarkJniFastPath() {
        // Ensure JNI path is available
        Assert.assertTrue("JNI fast-path must be available", M1Fast.isAvailable())
        
        // Enable JNI path
        FastPathConfig.useFastPath = true
        FastPathConfig.benchmarkMode = true
        
        // Warmup
        repeat(WARMUP_ITERATIONS) {
            val outputPath = File(testDir, "warmup_jni_$it.cbor").absolutePath
            runCatching {
                writeFrameJni(outputPath)
            }
        }
        
        jniWriteTimes.clear()
        
        // Benchmark with Android Benchmark library
        benchmarkRule.measureRepeated {
            val outputPath = File(testDir, "jni_${System.nanoTime()}.cbor").absolutePath
            
            runWithTimingDisabled {
                // Reset buffer position
                directBuffer.clear()
                directBuffer.put(rgbaData)
                directBuffer.flip()
            }
            
            // Measure only the write operation
            writeFrameJni(outputPath)
        }
        
        // Also run manual iterations for detailed metrics
        val manualTimes = mutableListOf<Long>()
        repeat(TEST_ITERATIONS) { i ->
            val outputPath = File(testDir, "jni_test_$i.cbor").absolutePath
            val startTime = System.nanoTime()
            
            writeFrameJni(outputPath)
            
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            manualTimes.add(elapsedMs)
            
            // Log structured event
            logBenchmarkEvent("jni", i, elapsedMs)
        }
        
        // Calculate and store results
        val jniMedian = manualTimes.sorted()[manualTimes.size / 2]
        val jniP90 = manualTimes.sorted()[(manualTimes.size * 0.9).toInt()]
        
        benchmarkResults.add(JSONObject().apply {
            put("implementation", "jni")
            put("median_ms", jniMedian)
            put("p90_ms", jniP90)
            put("samples", manualTimes.size)
            put("all_times", manualTimes)
        })
        
        Timber.i("JNI Fast-path: median=${jniMedian}ms, p90=${jniP90}ms")
    }
    
    @Test
    fun benchmarkUniffiPath() {
        // Ensure UniFFI is available
        Assert.assertTrue("UniFFI must be available", RustCborWriter.isAvailable())
        
        // Disable JNI path to force UniFFI
        FastPathConfig.useFastPath = false
        FastPathConfig.benchmarkMode = true
        
        // Warmup
        repeat(WARMUP_ITERATIONS) {
            val outputPath = File(testDir, "warmup_uniffi_$it.cbor").absolutePath
            runCatching {
                writeFrameUniffi(outputPath)
            }
        }
        
        uniffiWriteTimes.clear()
        
        // Benchmark
        benchmarkRule.measureRepeated {
            val outputPath = File(testDir, "uniffi_${System.nanoTime()}.cbor").absolutePath
            writeFrameUniffi(outputPath)
        }
        
        // Manual iterations
        val manualTimes = mutableListOf<Long>()
        repeat(TEST_ITERATIONS) { i ->
            val outputPath = File(testDir, "uniffi_test_$i.cbor").absolutePath
            val startTime = System.nanoTime()
            
            writeFrameUniffi(outputPath)
            
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
            manualTimes.add(elapsedMs)
            
            // Log structured event
            logBenchmarkEvent("uniffi", i, elapsedMs)
        }
        
        // Calculate results
        val uniffiMedian = manualTimes.sorted()[manualTimes.size / 2]
        val uniffiP90 = manualTimes.sorted()[(manualTimes.size * 0.9).toInt()]
        
        benchmarkResults.add(JSONObject().apply {
            put("implementation", "uniffi")
            put("median_ms", uniffiMedian)
            put("p90_ms", uniffiP90)
            put("samples", manualTimes.size)
            put("all_times", manualTimes)
        })
        
        Timber.i("UniFFI path: median=${uniffiMedian}ms, p90=${uniffiP90}ms")
    }
    
    @Test
    fun verifySpeedupTarget() {
        // Run both benchmarks if not already done
        if (benchmarkResults.size < 2) {
            benchmarkJniFastPath()
            benchmarkUniffiPath()
        }
        
        // Extract medians
        val jniResult = benchmarkResults.find { it.getString("implementation") == "jni" }
        val uniffiResult = benchmarkResults.find { it.getString("implementation") == "uniffi" }
        
        Assert.assertNotNull("JNI benchmark result", jniResult)
        Assert.assertNotNull("UniFFI benchmark result", uniffiResult)
        
        val jniMedian = jniResult!!.getDouble("median_ms")
        val uniffiMedian = uniffiResult!!.getDouble("median_ms")
        
        val speedup = uniffiMedian / jniMedian
        
        // Log final comparison
        val comparison = JSONObject().apply {
            put("jni_median_ms", jniMedian)
            put("uniffi_median_ms", uniffiMedian)
            put("speedup", speedup)
            put("target_speedup", TARGET_SPEEDUP)
            put("pass", speedup >= TARGET_SPEEDUP)
        }
        
        Timber.i("Speedup comparison: $comparison")
        saveBenchmarkResults(comparison)
        
        // Assert speedup target met
        Assert.assertTrue(
            "JNI fast-path should be ≥${TARGET_SPEEDUP}× faster than UniFFI (actual: ${String.format("%.2f", speedup)}×)",
            speedup >= TARGET_SPEEDUP
        )
    }
    
    @Test
    fun benchmarkDirectBufferVsByteArray() {
        // Compare zero-copy DirectByteBuffer vs ByteArray copy
        FastPathConfig.useFastPath = true
        
        val directTimes = mutableListOf<Long>()
        val arrayTimes = mutableListOf<Long>()
        
        // Test DirectByteBuffer path
        repeat(TEST_ITERATIONS) { i ->
            val outputPath = File(testDir, "direct_$i.cbor").absolutePath
            directBuffer.clear()
            directBuffer.put(rgbaData)
            directBuffer.flip()
            
            val startTime = System.nanoTime()
            M1Fast.writeFrameTracked(
                directBuffer, width, height, stride,
                System.currentTimeMillis(), i, outputPath
            )
            directTimes.add((System.nanoTime() - startTime) / 1_000_000)
        }
        
        // Test ByteArray path (requires copy to DirectByteBuffer)
        repeat(TEST_ITERATIONS) { i ->
            val outputPath = File(testDir, "array_$i.cbor").absolutePath
            val startTime = System.nanoTime()
            
            // This includes buffer allocation and copy
            val tempBuffer = ByteBuffer.allocateDirect(frameSize)
            tempBuffer.put(rgbaData)
            tempBuffer.flip()
            M1Fast.writeFrameTracked(
                tempBuffer, width, height, stride,
                System.currentTimeMillis(), i, outputPath
            )
            
            arrayTimes.add((System.nanoTime() - startTime) / 1_000_000)
        }
        
        val directMedian = directTimes.sorted()[directTimes.size / 2]
        val arrayMedian = arrayTimes.sorted()[arrayTimes.size / 2]
        
        Timber.i("DirectByteBuffer median: ${directMedian}ms")
        Timber.i("ByteArray+copy median: ${arrayMedian}ms")
        Timber.i("Copy overhead: ${arrayMedian - directMedian}ms")
        
        // DirectByteBuffer should be faster (no copy)
        Assert.assertTrue(
            "DirectByteBuffer should be faster than ByteArray+copy",
            directMedian <= arrayMedian
        )
    }
    
    // Helper methods
    
    private fun writeFrameJni(outputPath: String): Boolean {
        Trace.beginSection("BENCHMARK_JNI_WRITE")
        return try {
            M1Fast.writeFrameTracked(
                rgba = directBuffer,
                width = width,
                height = height,
                stride = stride,
                tsMs = System.currentTimeMillis(),
                frameIndex = 0,
                outPath = outputPath
            )
        } finally {
            Trace.endSection()
        }
    }
    
    private fun writeFrameUniffi(outputPath: String) {
        Trace.beginSection("BENCHMARK_UNIFFI_WRITE")
        try {
            runBlocking {
                rustCborWriter.writeFrame(
                    rgbaData = rgbaData,
                    width = width,
                    height = height,
                    stride = stride,
                    timestampMs = System.currentTimeMillis(),
                    outputPath = outputPath
                )
            }
        } finally {
            Trace.endSection()
        }
    }
    
    private fun logBenchmarkEvent(impl: String, frameIndex: Int, elapsedMs: Long) {
        LogEvent.Entry(
            event = "m1_write",
            milestone = "M1",
            sessionId = "benchmark",
            extra = mapOf(
                "impl" to impl,
                "ms" to elapsedMs,
                "bytes" to frameSize,
                "frame_index" to frameIndex
            )
        ).log()
    }
    
    private fun saveBenchmarkResults(comparison: JSONObject) {
        try {
            val resultsFile = File(
                context.getExternalFilesDir(null),
                "benchmark_results_${System.currentTimeMillis()}.json"
            )
            resultsFile.writeText(comparison.toString(2))
            Timber.i("Benchmark results saved to: ${resultsFile.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save benchmark results")
        }
    }
    
    @After
    fun tearDown() {
        // Clean up test files
        testDir.deleteRecursively()
        
        // Reset config
        FastPathConfig.benchmarkMode = false
    }
}