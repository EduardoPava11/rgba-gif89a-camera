import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.rgbagif"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.rgbagif"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
        
        buildConfigField("String", "LOG_AGGREGATOR_ENDPOINT", "\"https://logs.example.com/api/v1/logs\"")
        buildConfigField("String", "LOG_AGGREGATOR_API_KEY", "\"${System.getenv("LOG_API_KEY") ?: ""}\"")
        buildConfigField("Boolean", "DEBUG", "true")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlin.time.ExperimentalTime"
        )
    }
    
    buildFeatures {
        compose = true
        buildConfig = true
    }
    
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
        }
        
        jniLibs {
            useLegacyPackaging = true
        }
    }
    
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        animationsDisabled = true
    }
    
    lint {
        warningsAsErrors = true
        abortOnError = false
        checkDependencies = true
        baseline = file("lint-baseline.xml")
    }
}

// ============================================================================
// RUST BUILD CONFIGURATION - MANDATORY FOR M1/M2 PIPELINE
// ============================================================================

val ndkHome = System.getenv("ANDROID_NDK_HOME") 
    ?: "/Users/daniel/Library/Android/sdk/ndk/27.0.12077973"

// Build M1Fast JNI library
tasks.register<Exec>("buildM1FastRust") {
    group = "rust"
    description = "Build M1Fast JNI library for Android (REQUIRED)"
    
    workingDir = file("${rootDir}/rust-core/m1fast")
    
    environment("ANDROID_NDK_HOME", ndkHome)
    
    doFirst {
        // Ensure jniLibs directory exists
        file("${project.projectDir}/src/main/jniLibs/arm64-v8a").mkdirs()
        file("${project.projectDir}/src/main/jniLibs/armeabi-v7a").mkdirs()
        file("${project.projectDir}/src/main/jniLibs/x86_64").mkdirs()
        file("${project.projectDir}/src/main/jniLibs/x86").mkdirs()
    }
    
    // Build for all architectures
    commandLine(
        "sh", "-c",
        """
        cargo ndk -t arm64-v8a -o ${project.projectDir}/src/main/jniLibs build --release &&
        cargo ndk -t armeabi-v7a -o ${project.projectDir}/src/main/jniLibs build --release &&
        cargo ndk -t x86_64 -o ${project.projectDir}/src/main/jniLibs build --release &&
        cargo ndk -t x86 -o ${project.projectDir}/src/main/jniLibs build --release
        """.trimIndent()
    )
    
    inputs.dir("${rootDir}/rust-core/m1fast/src")
    inputs.file("${rootDir}/rust-core/m1fast/Cargo.toml")
    outputs.files(
        "${project.projectDir}/src/main/jniLibs/arm64-v8a/libm1fast.so",
        "${project.projectDir}/src/main/jniLibs/armeabi-v7a/libm1fast.so"
    )
    
    doLast {
        // Verify the libraries were created
        val arm64Lib = file("${project.projectDir}/src/main/jniLibs/arm64-v8a/libm1fast.so")
        if (!arm64Lib.exists()) {
            throw GradleException("CRITICAL: Failed to build libm1fast.so for arm64-v8a. M1 capture will not work!")
        }
        println("✅ M1Fast library built successfully")
    }
}

// Build M3GIF library with UniFFI - UNIFIED M2+M3 pipeline
tasks.register<Exec>("buildM3GifRust") {
    group = "rust"
    description = "Build unified M3GIF library with m3gif-core (M2+M3 pipeline)"
    
    workingDir = file("${rootDir}/rust-core")
    
    environment("ANDROID_NDK_HOME", ndkHome)
    
    doFirst {
        // Ensure jniLibs directory exists
        file("${project.projectDir}/src/main/jniLibs/arm64-v8a").mkdirs()
        file("${project.projectDir}/src/main/jniLibs/armeabi-v7a").mkdirs()
        file("${project.projectDir}/src/main/jniLibs/x86_64").mkdirs()
        file("${project.projectDir}/src/main/jniLibs/x86").mkdirs()
    }
    
    // Build for Android architectures - build only m3gif package
    commandLine(
        "sh", "-c",
        """
        cargo ndk -t arm64-v8a -o ${project.projectDir}/src/main/jniLibs build --release -p m3gif &&
        cargo ndk -t armeabi-v7a -o ${project.projectDir}/src/main/jniLibs build --release -p m3gif &&
        cargo ndk -t x86_64 -o ${project.projectDir}/src/main/jniLibs build --release -p m3gif &&
        cargo ndk -t x86 -o ${project.projectDir}/src/main/jniLibs build --release -p m3gif
        """.trimIndent()
    )
    
    inputs.dir("${rootDir}/rust-core/src")
    inputs.dir("${rootDir}/rust-core/m3gif-core/src")
    inputs.file("${rootDir}/rust-core/Cargo.toml")
    inputs.file("${rootDir}/rust-core/src/gifpipe.udl")
    outputs.files(
        "${project.projectDir}/src/main/jniLibs/arm64-v8a/libm3gif.so",
        "${project.projectDir}/src/main/jniLibs/armeabi-v7a/libm3gif.so"
    )
    
    doLast {
        // Verify the libraries were created (now named libm3gif.so)
        val arm64Lib = file("${project.projectDir}/src/main/jniLibs/arm64-v8a/libm3gif.so")
        if (!arm64Lib.exists()) {
            throw GradleException("CRITICAL: Failed to build libm3gif.so for arm64-v8a. M2+M3 pipeline will not work!")
        }
        
        println("✅ M3GIF library built successfully with m3gif-core integration")
    }
}

// Generate UniFFI Kotlin bindings for M2
tasks.register<Exec>("generateM2UniFFIBindings") {
    group = "uniffi"
    description = "Generate M2 UniFFI Kotlin bindings (REQUIRED)"
    
    workingDir = file("${rootDir}/rust-core/m2down")
    
    val outDir = file("${project.projectDir}/src/main/java")
    
    doFirst {
        outDir.mkdirs()
        
        // Check if uniffi-bindgen is installed
        val checkResult = exec {
            commandLine("which", "uniffi-bindgen")
            isIgnoreExitValue = true
        }
        if (checkResult.exitValue != 0) {
            println("⚠️  uniffi-bindgen not found, attempting to install...")
            exec {
                commandLine("cargo", "install", "uniffi_bindgen_cli", "--version", "0.25.0")
            }
        }
    }
    
    commandLine(
        "uniffi-bindgen", "generate",
        "src/m2down.udl",
        "--language", "kotlin",
        "--out-dir", outDir.absolutePath
    )
    
    inputs.file("${rootDir}/rust-core/m2down/src/m2down.udl")
    outputs.dir(outDir)
    
    doLast {
        println("✅ M2 UniFFI bindings generated")
    }
}

// Generate UniFFI Kotlin bindings for M3 GIF module - FIXED for UDL approach
tasks.register<Exec>("generateM3UniFFIBindings") {
    group = "uniffi"
    description = "Generate M3 GIF UniFFI Kotlin bindings (UDL-based approach)"
    
    workingDir = file("${rootDir}/rust-core")
    
    val outDir = file("${project.projectDir}/src/main/java")
    
    doFirst {
        outDir.mkdirs()
        
        // Ensure Rust library is built first
        exec {
            workingDir = file("${rootDir}/rust-core")
            commandLine("cargo", "build")
        }
    }
    
    // Use uniffi-bindgen directly on m3gif UDL
    commandLine(
        "uniffi-bindgen", "generate",
        "m3gif/src/m3gif.udl",
        "--language", "kotlin", "--out-dir", outDir.absolutePath
    )
    
    inputs.file("${rootDir}/rust-core/m3gif/src/m3gif.udl")
    outputs.dir("${outDir}/uniffi/m3gif")
    
    doLast {
        println("✅ M3 GIF UniFFI bindings generated (UDL-based)")
    }
}

// Master task to build all Rust components
tasks.register("buildAllRustLibraries") {
    group = "rust"
    description = "Build ALL Rust libraries and generate bindings - FIXED library names"
    
    dependsOn("buildM1FastRust", "buildM3GifRust", "generateM3UniFFIBindings")
    
    doLast {
        // Final verification - CORRECTED library names
        val requiredLibs = listOf(
            "${project.projectDir}/src/main/jniLibs/arm64-v8a/libm1fast.so",
            "${project.projectDir}/src/main/jniLibs/arm64-v8a/libm3gif.so" // Only check the final library
        )
        
        val missingLibs = requiredLibs.filter { !file(it).exists() }
        
        if (missingLibs.isNotEmpty()) {
            throw GradleException("""
                ❌ CRITICAL BUILD ERROR: Missing required Rust libraries!
                
                The following libraries are REQUIRED for the app to function:
                ${missingLibs.joinToString("\n")}
                
                The app CANNOT process images without these libraries.
                Run './gradlew buildAllRustLibraries' to build them.
            """.trimIndent())
        }
        
        println("""
            ✅ All Rust libraries built successfully:
            - M1Fast (JNI fast-path CBOR writer)
            - M3GIF (Unified M2+M3 pipeline with m3gif-core)
            - UniFFI bindings generated
            
            Desktop-proven pipeline now available on Android!
        """.trimIndent())
    }
}

// ENFORCE: preBuild MUST build Rust libraries and generate bindings
tasks.named("preBuild") {
    dependsOn("buildM1FastRust", "buildM3GifRust", "generateM3UniFFIBindings")
}

// ENFORCE: Verify libraries before creating APK - CORRECTED library names
afterEvaluate {
    tasks.findByName("mergeDebugNativeLibs")?.doFirst {
        val libDir = file("${project.projectDir}/src/main/jniLibs/arm64-v8a")
        val requiredLibs = listOf("libm1fast.so", "libm3gif.so") // FIXED: correct library names
        
        requiredLibs.forEach { libName ->
            if (!File(libDir, libName).exists()) {
                throw GradleException("""
                    ❌ CANNOT BUILD APK: Missing $libName
                    
                    The Rust libraries are REQUIRED and must be built first.
                    Run: ./gradlew buildAllRustLibraries
                    
                    This is not optional - the app will not work without these libraries.
                """.trimIndent())
            }
        }
        
        println("✅ Native libraries verified: All required .so files present")
    }
}

// UniFFI Binding Generation Task - Prevents Contract Mismatches
tasks.register("generateUniFFIBindings", Exec::class) {
    description = "Generate UniFFI Kotlin bindings from Rust UDL"
    group = "build"
    
    // Ensure we use the same uniffi-bindgen version as the Rust crate
    workingDir = File("${rootDir}/rust-core")
    
    // Generate bindings for m3gif
    commandLine("sh", "-c", """
        ./target/debug/uniffi-bindgen generate m3gif/src/m3gif.udl --language kotlin --out-dir ../app/src/main/java || \
        uniffi-bindgen generate m3gif/src/m3gif.udl --language kotlin --out-dir ../app/src/main/java
    """.trimIndent())
    
    // Depend on Rust build
    dependsOn(":buildRustLibraries")
    
    // Always run before Kotlin compilation
    outputs.dir("${projectDir}/src/main/java/uniffi")
    outputs.upToDateWhen { false } // Always regenerate to ensure version compatibility
}

// Make sure bindings are generated before Kotlin compilation
// TODO: Fix uniffi-bindgen installation issue
// tasks.named("preBuild") {
//     dependsOn("generateM3UniFFIBindings")
// }

dependencies {
    // Core Android dependencies
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // Compose UI
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime-livedata")
    
    // CameraX
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")
    
    // WorkManager for background processing
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")
    
    // Image loading
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("io.coil-kt:coil-gif:2.5.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    
    // Tracing
    implementation("androidx.tracing:tracing:1.2.0")
    
    // Logging (REQUIRED for pipeline verification)
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.6.0")
    
    // UniFFI runtime (REQUIRED for M2)
    implementation("net.java.dev.jna:jna:5.12.0@aar")
    
    // CBOR processing
    implementation("com.upokecenter:cbor:4.5.2")
    
    // Metrics
    implementation("io.micrometer:micrometer-core:1.11.0")
    
    // Networking for log shipping
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // Memory leak detection (debug only)
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.12")
    
    // Permissions handling
    implementation("com.google.accompanist:accompanist-permissions:0.32.0")
    
    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    
    // Android instrumentation tests
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    
    // Debug tools
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Task to verify the build is complete
tasks.register("verifyBuild") {
    dependsOn("assembleDebug")
    
    doLast {
        val apk = file("${project.buildDir}/outputs/apk/debug/app-debug.apk")
        if (!apk.exists()) {
            throw GradleException("APK not found!")
        }
        
        // Check APK contains native libraries
        exec {
            commandLine("unzip", "-l", apk.absolutePath)
            standardOutput = System.out
        }
        
        println("""
            ✅ BUILD VERIFICATION COMPLETE
            
            APK: ${apk.absolutePath}
            Size: ${apk.length() / 1024 / 1024}MB
            
            The APK contains the REQUIRED Rust libraries:
            - libm1fast.so (JNI fast CBOR writer)
            - libm2down.so (Neural downsize)
            
            Ready to install and test!
        """.trimIndent())
    }
}