// Core testing dependencies
dependencies {
    // Existing dependencies...
    
    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("app.cash.turbine:turbine:1.0.0")
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    
    // Compose testing
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
    
    // Accessibility testing
    androidTestImplementation("androidx.test.espresso:espresso-accessibility:3.5.1")
    
    // Macrobenchmark
    androidTestImplementation("androidx.benchmark:benchmark-macro-junit4:1.2.0")
    
    // Image/GIF analysis
    testImplementation("org.apache.commons:commons-imaging:1.0.0-alpha5")
}

// Test configurations
android {
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
        animationsDisabled = true
    }
}
