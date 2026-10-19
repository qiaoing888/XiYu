import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 正式签名只从 keystore.properties 或环境变量读取，不把密钥写进仓库
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
}

fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "RELEASE_STORE_FILE")
            if (!storePath.isNullOrBlank()) {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }
    namespace = "com.keyscafe.unlock"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.keyscafe.unlock"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "2.1"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "DebugProbesKt.bin"
        }
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:101.0.1")
    implementation(files("libs/libxposed-service-102.0.0.jar"))
    implementation(files("libs/libxposed-interface-102.0.0.jar"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    testImplementation("junit:junit:4.13.2")
}

// 自动对齐 16 KB 页面（适配 Android 15/16 原生动态库内存对齐要求）
fun register16kAlignmentTask(variantName: String, apkPath: String) {
    val taskName = "align${variantName.replaceFirstChar { it.uppercase() }}16k"
    tasks.register(taskName) {
        val targetApk = file(apkPath)
        val alignedApk = file(apkPath.replace(".apk", "-aligned16k.apk"))
        val buildTools = "${android.sdkDirectory}/build-tools/${android.buildToolsVersion}"
        val zipalign = file("$buildTools/zipalign")
        val javaBin = file("${System.getProperty("java.home")}/bin/java")
        val apksignerJar = file("$buildTools/lib/apksigner.jar")
        val isRelease = variantName == "Release"
        val keystorePath = if (isRelease) {
            signingValue("storeFile", "RELEASE_STORE_FILE")
        } else {
            "${System.getProperty("user.home")}/.android/debug.keystore"
        }
        val keystorePass = if (isRelease) {
            signingValue("storePassword", "RELEASE_STORE_PASSWORD")
        } else {
            "android"
        }
        val keyAlias = if (isRelease) signingValue("keyAlias", "RELEASE_KEY_ALIAS") else null
        val keyPass = if (isRelease) signingValue("keyPassword", "RELEASE_KEY_PASSWORD") else null
        doLast {
            val keystore = keystorePath?.takeIf { it.isNotBlank() }?.let { rootProject.file(it) }
            if (keystore == null || !keystore.exists()) {
                throw GradleException("${variantName} 签名 keystore 缺失，请配置 keystore.properties 或 RELEASE_* 环境变量")
            }
            if (!zipalign.exists() || !javaBin.exists() || !apksignerJar.exists() || !targetApk.exists()) {
                throw GradleException("16KB 对齐所需文件缺失: zipalign/apksigner/apk")
            }
            project.exec {
                commandLine(zipalign.absolutePath, "-P", "16", "-f", "4", targetApk.absolutePath, alignedApk.absolutePath)
            }
            project.exec {
                val args = mutableListOf(
                    javaBin.absolutePath, "-jar", apksignerJar.absolutePath, "sign",
                    "--ks", keystore.absolutePath,
                    "--ks-pass", "pass:${keystorePass.orEmpty()}"
                )
                if (!keyAlias.isNullOrBlank()) {
                    args += listOf("--ks-key-alias", keyAlias)
                }
                if (!keyPass.isNullOrBlank()) {
                    args += listOf("--key-pass", "pass:$keyPass")
                }
                args += alignedApk.absolutePath
                commandLine(args)
            }
            alignedApk.copyTo(targetApk, overwrite = true)
            alignedApk.delete()
        }
    }
    tasks.named("package${variantName.replaceFirstChar { it.uppercase() }}") {
        finalizedBy(taskName)
    }
}

afterEvaluate {
    register16kAlignmentTask("Debug", "build/outputs/apk/debug/app-debug.apk")
    register16kAlignmentTask("Release", "build/outputs/apk/release/app-release.apk")
}

gradle.taskGraph.whenReady {
    val releaseRequested = allTasks.any {
        it.name == "packageRelease" || it.name == "assembleRelease" || it.name == "bundleRelease"
    }
    if (releaseRequested && signingValue("storeFile", "RELEASE_STORE_FILE").isNullOrBlank()) {
        throw GradleException(
            "正式发布必须配置签名：在根目录 keystore.properties 或 RELEASE_STORE_FILE/" +
                "RELEASE_STORE_PASSWORD/RELEASE_KEY_ALIAS/RELEASE_KEY_PASSWORD 中提供"
        )
    }
}

