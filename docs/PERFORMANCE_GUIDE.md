# Performance Guide

## Overview

This guide covers performance optimization, measurement, and debugging for the RGBA→GIF89a Camera App. The app uses a multi-stage pipeline with aggressive optimization at each stage.

## Performance Architecture

### M1: Capture (JNI Fast-Path)

The capture pipeline uses a dual-path architecture:

1. **JNI Fast-Path** (Default): ~2.18× faster than UniFFI
   - DirectByteBuffer for zero-copy access
   - Native CBOR encoding with ciborium
   - Buffer pooling to avoid allocations
   - Direct file I/O with large buffers

2. **UniFFI Path** (Fallback): More portable but slower
   - Safe Rust bindings
   - Memory copy required
   - Good for debugging/validation

#### Switching Paths

```kotlin
// Runtime toggle via FastPathConfig
FastPathConfig.setUseFastPath(true)  // Use JNI
FastPathConfig.setUseFastPath(false) // Use UniFFI

// Enable benchmarking mode
FastPathConfig.benchmarkMode = true
```

### M3: GIF Export (Quantization + Compression)

The GIF export pipeline offers multiple quantizers:

1. **MedianCut**: Fast baseline, good quality
2. **Octree**: Better quality, slightly slower
3. **NeuQuant** (planned): Best quality, neural network-based

Optional Floyd-Steinberg dithering improves visual quality at a performance cost.

## Perfetto Tracing

### Key Trace Sections

The app includes detailed Perfetto trace sections:

```
M1 Capture:
- M1_CAPTURE: Full capture session
- M1_JNI_WRITE: Per-frame JNI write
- DirectByteBuffer_Access: Buffer operations
- CBOR_ENCODE: CBOR serialization
- FILE_WRITE: Disk I/O
- BUFFER_POOL_ACQUIRE/RELEASE: Buffer management

M3 Export:
- GIF89a_EXPORT: Full export pipeline
- QUANTIZATION: Color quantization
- MedianCutQuantizer/OctreeQuantizer: Specific algorithms
- COLOR_HISTOGRAM: Color analysis
- PALETTE_GENERATION: Palette creation
- FRAME_INDEXING: Mapping pixels to palette
- DITHERING: Error diffusion
- FLOYD_STEINBERG: Dithering algorithm
- LZW_COMPRESSION: GIF compression
- GIF_WRITE: File output
```

### Collecting Traces

1. Enable Perfetto in dev settings:
```kotlin
DevSettings.setEnablePerfetto(true)
```

2. Start trace collection:
```bash
adb shell perfetto \
  -c - --txt \
  -o /data/local/tmp/trace.perfetto \
  <<EOF
buffers: {
  size_kb: 32768
  fill_policy: RING_BUFFER
}
data_sources: {
  config {
    name: "linux.ftrace"
    ftrace_config {
      atrace_apps: "com.rgbagif"
      atrace_categories: "view"
      atrace_categories: "camera"
    }
  }
}
duration_ms: 30000
EOF
```

3. Run your workflow

4. Pull and analyze:
```bash
adb pull /data/local/tmp/trace.perfetto
# Open in https://ui.perfetto.dev
```

## Macrobenchmark

### Running Benchmarks

```bash
# Run all benchmarks
./gradlew :macrobenchmark:connectedCheck

# Run specific benchmark
./gradlew :macrobenchmark:connectedCheck \
  -Pandroid.testInstrumentationRunnerArguments.class=com.rgbagif.macrobenchmark.StartupBenchmark

# Run with specific metrics
./gradlew :macrobenchmark:connectedCheck \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.perfettoSdkTracing.enable=true
```

### Key Metrics

The Macrobenchmark module tracks:

1. **Startup Performance**
   - Cold start: Full app initialization
   - Warm start: Process alive, activities destroyed
   - Hot start: Activities in background

2. **M1 Capture Performance**
   - Per-frame write times (P50, P90, P99)
   - JNI vs UniFFI comparison
   - Buffer pool efficiency
   - CBOR encoding overhead

3. **M3 Export Performance**
   - Quantization time by algorithm
   - Dithering impact
   - LZW compression speed
   - Memory usage during export

### Benchmark Results Format

Results are output as JSON:
```json
{
  "benchmarkName": "measureM1CapturePerformance",
  "metrics": {
    "M1_JNI_WRITE_ms": {
      "P50": 12.5,
      "P90": 15.2,
      "P99": 18.7
    },
    "M1_UNIFFI_WRITE_ms": {
      "P50": 27.3,
      "P90": 31.5,
      "P99": 35.2
    },
    "speedup_factor": 2.18
  }
}
```

## A/B Testing

### JNI vs UniFFI Comparison

The app supports runtime A/B testing:

```kotlin
class PerformanceTest {
    @Test
    fun compareJniVsUniffi() {
        val jniTimes = mutableListOf<Long>()
        val uniffiTimes = mutableListOf<Long>()
        
        repeat(100) { i ->
            // Test JNI path
            FastPathConfig.setUseFastPath(true)
            jniTimes.add(captureFrame())
            
            // Test UniFFI path
            FastPathConfig.setUseFastPath(false)
            uniffiTimes.add(captureFrame())
        }
        
        val jniMedian = jniTimes.sorted()[50]
        val uniffiMedian = uniffiTimes.sorted()[50]
        val speedup = uniffiMedian.toDouble() / jniMedian
        
        println("JNI median: ${jniMedian}ms")
        println("UniFFI median: ${uniffiMedian}ms")
        println("Speedup: ${speedup}x")
    }
}
```

### Quantizer Comparison

```kotlin
// Compare quantizers
DevSettings.setQuantizerType(DevSettings.QuantizerType.MEDIAN_CUT)
val medianCutTime = measureGifExport()

DevSettings.setQuantizerType(DevSettings.QuantizerType.OCTREE)
val octreeTime = measureGifExport()

// With/without dithering
DevSettings.setEnableDithering(false)
val noDitherTime = measureGifExport()

DevSettings.setEnableDithering(true)
val ditherTime = measureGifExport()
```

## Memory Profiling

### Buffer Pool Monitoring

The app tracks buffer pool usage:
```kotlin
// In FastPathFrameProcessor
private val bufferPool = mutableListOf<ByteBuffer>()
private val maxPoolSize = 3

// Monitor pool efficiency
val poolHitRate = poolHits.toFloat() / (poolHits + poolMisses)
```

### Heap Dump Analysis

```bash
# Capture heap dump during capture
adb shell am dumpheap com.rgbagif /data/local/tmp/heap.hprof

# Pull and analyze
adb pull /data/local/tmp/heap.hprof
# Open in Android Studio Memory Profiler
```

## Real-Time Stats

### Analyzer Stats Overlay

Enable the stats overlay to see real-time metrics:

```kotlin
DevSettings.setShowAnalyzerStats(true)
```

Displays:
- Current FPS
- Queue depth
- Frames processed/dropped
- Average processing time
- Memory usage

## Performance Targets

### M1 Capture
- **JNI path**: <15ms per frame (1440×1440 RGBA)
- **UniFFI path**: <35ms per frame
- **Memory**: <100MB peak for 3-frame buffer

### M3 Export
- **Quantization**: <300ms for 32 frames
- **GIF encoding**: <2s for 32-frame GIF
- **Memory**: <200MB peak during export

## Optimization Checklist

### Before Release

- [ ] Run Macrobenchmark suite
- [ ] Collect Perfetto traces on target devices
- [ ] Verify JNI fast-path is default
- [ ] Check buffer pool hit rate >90%
- [ ] Validate no memory leaks
- [ ] Test on min-spec device (2GB RAM, Snapdragon 450)

### Performance Regression Testing

The CI automatically checks for regressions:

```yaml
# In .github/workflows/device-tests.yml
regression_threshold: 1.2  # 20% regression threshold

if current_time > baseline_time * regression_threshold:
    fail("Performance regression detected")
```

## Debugging Performance Issues

### Common Issues

1. **Slow frame writes**
   - Check if JNI path is enabled
   - Verify DirectByteBuffer is being used
   - Check disk I/O (use systrace)

2. **Memory pressure**
   - Reduce buffer pool size
   - Check for ImageProxy leaks
   - Enable more aggressive GC

3. **Slow GIF export**
   - Try different quantizer
   - Disable dithering
   - Reduce frame count or resolution

### Tools

- **Systrace**: System-wide performance
- **Perfetto**: Detailed app traces
- **Android Studio Profiler**: CPU, memory, network
- **Macrobenchmark**: Automated performance testing
- **Firebase Performance**: Production monitoring

## Device-Specific Optimizations

### Pixel 6/7 (Tensor)
- Optimized for ML workloads
- Can handle higher resolution
- Good for NeuQuant quantizer

### Mid-range (Snapdragon 600/700 series)
- Default settings work well
- May need reduced buffer pool

### Low-end (Snapdragon 400 series)
- Use smaller preview resolution
- Reduce buffer pool to 2
- Consider disabling dithering