import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.zhaoyi.maji"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zhaoyi.maji"
        minSdk = 33
        targetSdk = 37
        versionCode = 48
        versionName = "2.0-next"
        buildConfigField("String", "BUILD_DATE", "\"2026-06-19\"")
    }

    signingConfigs {
        create("release") {
            // 签名信息从 local.properties 读取（该文件已 gitignore，不会进入仓库）。
            // 在 local.properties 中补充以下四项后 release 构建才会被签名：
            //   KEYSTORE_FILE=D:/files/zhaoyi.jks
            //   KEYSTORE_PASSWORD=你的密钥库密码
            //   KEY_ALIAS=zhaoyi.tools
            //   KEY_PASSWORD=你的密钥密码
            // 若本地未配置，则 release 仅编译不签名（不影响源码备份与 debug 构建）。
            val props = Properties()
            val propsFile = rootProject.file("local.properties")
            if (propsFile.exists()) {
                propsFile.inputStream().use { props.load(it) }
            }
            val ksFile = props.getProperty("KEYSTORE_FILE")?.let { file(it) }
            val ksPass = props.getProperty("KEYSTORE_PASSWORD")
            val keyPass = props.getProperty("KEY_PASSWORD")
            if (ksFile != null && ksFile.exists() && !ksPass.isNullOrBlank() && !keyPass.isNullOrBlank()) {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = props.getProperty("KEY_ALIAS", "zhaoyi.tools")
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-navigation3-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.3")

    // 渐进模糊（Progressive blur）：Haze 库，用于设置「模糊样式=渐进」时替换 Miuix 默认毛玻璃
    implementation("dev.chrisbanes.haze:haze:1.7.2")

    // 页面层级跳转（关于页等）直接使用 Miuix 的 NavDisplay，动画与示例 app 完全一致
    implementation("androidx.navigation3:navigation3-runtime:1.1.4")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")

    implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
    implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.compose.ui:ui:1.11.1")
    implementation("org.jetbrains.compose.ui:ui-graphics:1.11.1")
    implementation("org.jetbrains.compose.ui:ui-tooling-preview:1.11.1")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    debugImplementation("org.jetbrains.compose.ui:ui-tooling:1.11.1")
}

tasks.register("printLifecycleCp") {
    doLast {
        val cfg = configurations.findByName("releaseCompileClasspath")
        cfg?.files?.filter { it.name.contains("lifecycle", true) || it.name.contains("savedstate", true) }
            ?.forEach { println("CPJAR: " + it.absolutePath) }
        println("--- ALL containing androidx/lifecycle/ViewTreeLifecycleOwner ---")
    }
}

// 输出 APK 文件名带上版本号，便于区分每次构建产物
// 输出 APK 文件名带上版本号，便于区分每次构建产物。
// 不直接改 variant API 的 outputFileName（AGP 8 + Kotlin DSL 下与 stdlib `Iterable.all` 有歧义且 API 已不可见），
// 改为在 assembleRelease 完成后直接重命名产物文件。
afterEvaluate {
    tasks.named("assembleRelease") {
        doLast {
            val vName = android.defaultConfig.versionName ?: "app"
            val outDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
            val src = File(outDir, "app-release.apk")
            val dst = File(outDir, "maji-$vName.apk")
            if (src.exists()) {
                if (dst.exists()) dst.delete()
                check(src.renameTo(dst)) { "重命名 APK 失败: ${src.absolutePath} -> ${dst.absolutePath}" }
            }
        }
    }
}
