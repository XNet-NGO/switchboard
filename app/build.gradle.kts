plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// --- portmux prebuilt artifact fetch ----------------------------------------
// Switchboard consumes portmux as prebuilt static libraries (GitHub release),
// not as source. Per-ABI tarballs are downloaded into app/.portmux/ and the
// .a files extracted to app/.portmux/libs/<abi>/. CMake imports them.
val portmuxVersion = (project.findProperty("portmuxVersion") as String?) ?: "v0.2.0"
val portmuxRepo = "XNet-NGO/portmux"
val portmuxAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val portmuxDir = layout.projectDirectory.dir(".portmux")
val portmuxLibsDir = portmuxDir.dir("libs")
val portmuxIncludeDir = portmuxDir.dir("include")

val fetchPortmux by tasks.registering {
    description = "Download and extract portmux $portmuxVersion prebuilt static libs."
    val marker = portmuxDir.file(".fetched-$portmuxVersion").asFile
    outputs.file(marker)
    outputs.dir(portmuxLibsDir)
    doLast {
        if (marker.exists()) return@doLast
        portmuxDir.asFile.mkdirs()
        portmuxLibsDir.asFile.mkdirs()
        portmuxIncludeDir.asFile.mkdirs()
        portmuxAbis.forEach { abi ->
            val tgz = portmuxDir.file("portmux-$portmuxVersion-android-$abi.tar.gz").asFile
            val url =
                "https://github.com/$portmuxRepo/releases/download/" +
                    "$portmuxVersion/portmux-$portmuxVersion-android-$abi.tar.gz"
            logger.lifecycle("portmux: fetching $abi from $url")
            ant.invokeMethod("get", mapOf("src" to url, "dest" to tgz.absolutePath, "verbose" to false))
            // extract: android/<abi>/*.a  and  include/portmux.h
            val outAbi = portmuxLibsDir.dir(abi).asFile
            outAbi.mkdirs()
            val proc = ProcessBuilder(
                "tar", "-xzf", tgz.absolutePath, "-C", portmuxDir.asFile.absolutePath,
            ).inheritIO().start()
            if (proc.waitFor() != 0) throw GradleException("tar extract failed for $abi")
            // move extracted android/<abi>/*.a into libs/<abi>/
            val extracted = portmuxDir.dir("android").dir(abi).asFile
            extracted.listFiles()?.filter { it.extension == "a" }?.forEach { a ->
                a.copyTo(File(outAbi, a.name), overwrite = true)
            }
        }
        marker.writeText("portmux $portmuxVersion fetched\n")
        logger.lifecycle("portmux: artifacts ready under ${portmuxDir.asFile}")
    }
}

// Make sure the native build can see the libs before CMake configures.
tasks.withType<com.android.build.gradle.tasks.ExternalNativeBuildTask>().configureEach {
    dependsOn(fetchPortmux)
}
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }
    .configureEach { dependsOn(fetchPortmux) }

android {
    namespace = "org.xnet.switchboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.xnet.switchboard"
        minSdk = 23
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.1"

        ndk {
            abiFilters += portmuxAbis
        }
        externalNativeBuild {
            cmake {
                arguments += "-DPORTMUX_LIBS_DIR=${portmuxLibsDir.asFile.absolutePath}"
                arguments += "-DPORTMUX_INCLUDE_DIR=${portmuxIncludeDir.asFile.absolutePath}"
                cppFlags += "-std=c11"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            // Credentials come from the environment, never hardcoded in the
            // repo. Set XNET_KEYSTORE / XNET_KEYSTORE_PASS / XNET_KEY_ALIAS
            // (see XNet-NGO/xnet-keystore). When unset, release builds are
            // produced unsigned.
            val ksPath = System.getenv("XNET_KEYSTORE")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("XNET_KEYSTORE_PASS")
                keyAlias = System.getenv("XNET_KEY_ALIAS") ?: "xnet-key"
                keyPassword = System.getenv("XNET_KEY_PASS")
                    ?: System.getenv("XNET_KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("XNET_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
