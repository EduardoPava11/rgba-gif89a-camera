// Top-level build file
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.0" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// CI Testing tasks for milestone validation
tasks.register("runMilestoneTests") {
    group = "verification"
    description = "Run all milestone tests on connected device"
    
    doLast {
        exec {
            commandLine("adb", "logcat", "-c")
        }
        exec {
            commandLine("chmod", "+x", "scripts/grab_logs.sh")
        }
        exec {
            commandLine("./scripts/grab_logs.sh")
        }
    }
}

tasks.register("collectPerfettoTrace") {
    group = "verification"
    description = "Collect Perfetto trace during milestone tests"
    
    doLast {
        exec {
            commandLine("chmod", "+x", "scripts/perfetto_trace.sh")
        }
        exec {
            commandLine("./scripts/perfetto_trace.sh")
        }
    }
}

tasks.register("validateLogs") {
    group = "verification"
    description = "Validate milestone log outputs"
    
    dependsOn("runMilestoneTests")
    
    doLast {
        exec {
            commandLine("chmod", "+x", "scripts/m1_validate.sh")
        }
        exec {
            commandLine("./scripts/m1_validate.sh")
        }
        // Additional validators will be added as milestones are implemented
    }
}

tasks.register("archiveTestArtifacts") {
    group = "verification"
    description = "Archive test logs and traces"
    
    doLast {
        val timestamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val artifactDir = file("build/test-artifacts/$timestamp")
        artifactDir.mkdirs()
        
        // Copy logs
        file(".").listFiles { _, name -> 
            name.endsWith(".log") || name.endsWith(".perfetto-trace")
        }?.forEach { file ->
            file.copyTo(java.io.File(artifactDir, file.name), overwrite = true)
        }
        
        println("Test artifacts archived to: $artifactDir")
    }
}

// Build m1fast JNI library
tasks.register<Exec>("buildM1Fast") {
    group = "build"
    description = "Build m1fast JNI library for Android"
    workingDir = file("rust-core/m1fast")
    commandLine("bash", "build.sh")
}

// Hook m1fast build into preBuild
tasks.whenTaskAdded {
    if (name == "preBuild") {
        dependsOn("buildM1Fast")
    }
}