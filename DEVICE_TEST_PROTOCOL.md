// File: app/src/main/java/com/rgbagif/camera/CameraXManager.kt

fun startPreview(
    context: Context,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    onRgbaFrame: (rgbaBytes: ByteArray, width: Int, height: Int) -> Unit
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
    cameraProviderFuture.addListener({
        val cameraProvider = cameraProviderFuture.get()

        // Unbind first to avoid duplicate use cases
        cameraProvider.unbindAll()

        // Build Preview use case and attach to PreviewView
        val preview = Preview.Builder()
            .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        // Build ImageAnalysis for RGBA_8888 with KEEP_ONLY_LATEST to avoid backpressure stalls
        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        // Single-thread executor for analyzer
        val analyzerExecutor = Executors.newSingleThreadExecutor()

        imageAnalysis.setAnalyzer(analyzerExecutor) { imageProxy ->
            try {
                // CameraX supplies RGBA_8888 in PlaneProxy[0]
                val plane = imageProxy.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val width = imageProxy.width
                val height = imageProxy.height

                // Copy into a tightly packed RGBA buffer, then crop square center 729x729
                val rgba = ByteArray(height * rowStride)
                buffer.get(rgba, 0, rgba.size)

                // Compute center-crop to exact 729x729 (assumes source >= 729 on both dims)
                val cropSize = 729
                val startX = (width - cropSize).coerceAtLeast(0) / 2
                val startY = (height - cropSize).coerceAtLeast(0) / 2

                // Pack the cropped pixels into a contiguous RGBA array (no stride)
                val out = ByteArray(cropSize * cropSize * 4)
                var dst = 0
                var srcBase = startY * rowStride + startX * 4
                for (y in 0 until cropSize) {
                    var src = srcBase
                    System.arraycopy(rgba, src, out, dst, cropSize * 4)
                    dst += cropSize * 4
                    srcBase += rowStride
                }

                onRgbaFrame(out, cropSize, cropSize)
            } catch (t: Throwable) {
                Log.e("CameraXManager", "Analyzer failure: ${t.message}", t)
            } finally {
                // Always close or analyzer will stall with maxImages reached
                imageProxy.close()
            }
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        // Bind to lifecycle
        cameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview,
            imageAnalysis
        )

        Log.i("CameraXManager", "Preview+Analysis bound: RGBA_8888, KEEP_ONLY_LATEST")
    }, ContextCompat.getMainExecutor(context))
}


// File: app/src/main/java/com/rgbagif/ui/MilestoneWorkflowScreen.kt

@Composable
fun MilestoneWorkflowScreen(cameraXManager: CameraXManager) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewViewRef = remember { PreviewView(context) }

    // ... your existing UI code that adds previewViewRef to the layout ...

    LaunchedEffect(Unit) {
        cameraXManager.startPreview(
            context = context,
            lifecycleOwner = lifecycleOwner,
            previewView = previewViewRef,
        ) { rgba, w, h ->
            // Route frames to M1/M2 pipeline (M1 capture gate decides whether to store)
            MilestoneManager.onRgbaFrame(rgba, w, h)
        }
    }

    // ... rest of your composable ...
}


// File: app/src/main/java/com/rgbagif/processing/M2Processor.kt

@Volatile private var nnAvailable: Boolean = false

private fun blockReduce9x9(rgba729: ByteArray): ByteArray {
    // Deterministic 9x9 block average downscale to 81x81 RGBA
    val out = ByteArray(81 * 81 * 4)
    var dst = 0
    for (by in 0 until 81) {
        for (bx in 0 until 81) {
            var r = 0; var g = 0; var b = 0; var a = 0
            val sx = bx * 9
            val sy = by * 9
            for (yy in 0 until 9) {
                var src = ((sy + yy) * 729 + sx) * 4
                for (xx in 0 until 9) {
                    r += rgba729[src].toInt() and 0xFF
                    g += rgba729[src + 1].toInt() and 0xFF
                    b += rgba729[src + 2].toInt() and 0xFF
                    a += rgba729[src + 3].toInt() and 0xFF
                    src += 4
                }
            }
            val count = 81
            out[dst]     = (r / count).toByte()
            out[dst + 1] = (g / count).toByte()
            out[dst + 2] = (b / count).toByte()
            out[dst + 3] = (a / count).toByte()
            dst += 4
        }
    }
    return out
}

fun initNeural() {
    Timber.i("M2_INIT start")
    try {
        val ver = uniffi.m2down.getM2Version()
        nnAvailable = true
        Timber.i("M2_INIT ok version=$ver")
    } catch (t: Throwable) {
        nnAvailable = false
        Timber.e(t, "M2_INIT fail msg=${t.message}")
    }
}

suspend fun downsizeFrame(idx: Int, rgba729: ByteArray): ByteArray = withContext(Dispatchers.Default) {
    Timber.i("M2_FRAME_BEGIN idx=$idx in=729x729 out=81x81")
    val out = try {
        if (nnAvailable) {
            uniffi.m2down.m2_downsize_9x9_cpu(rgba729, 729u, 729u)
        } else {
            blockReduce9x9(rgba729)
        }
    } catch (t: Throwable) {
        Timber.e(t, "M2_FRAME_FALLBACK idx=$idx reason=${t.message}")
        blockReduce9x9(rgba729)
    }
    Timber.i("M2_FRAME_END idx=$idx bytes=${out.size}")
    out
}


// File: app/src/main/java/com/rgbagif/processing/M3Processor.kt

fun exportGifAsync(sessionDir: File, framesRgba81: List<ByteArray>) {
    Timber.i("M3_START frames=${framesRgba81.size}")
    CoroutineScope(Dispatchers.Default).launch {
        try {
            if (framesRgba81.size != 81) {
                Timber.e("PIPELINE_ERROR stage=\"M3\" reason=\"expected 81 frames, got ${framesRgba81.size}\"")
                return@launch
            }
            val gifBytes = uniffi.m3gif.encode_gif89a(framesRgba81, /*delay schema*/ intArrayOf(4,4,4,5), true)
            val out = File(sessionDir, "final.gif")
            out.outputStream().use { it.write(gifBytes) }
            Timber.i("M3_GIF_DONE frames=81 loop=true sizeBytes=${out.length()} path=${out.absolutePath}")
        } catch (t: Throwable) {
            Timber.e(t, "PIPELINE_ERROR stage=\"M3\" reason=\"${t.message}\"")
        }
    }
}
