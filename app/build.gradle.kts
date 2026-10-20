@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

// Robolectric 的 SDK 35 运行时（约 200 MB）声明成依赖配置：sha256 记在 gradle/verification-metadata.xml，
// 与其它下载一样被校验。LocalDependencyResolver 要求平铺目录，所以同步一份到 build 下
val robolectricRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val robolectricRuntimeDir = layout.buildDirectory.dir("robolectric-runtime")
val syncRobolectricRuntime = tasks.register<Sync>("syncRobolectricRuntime") {
    from(robolectricRuntime)
    into(robolectricRuntimeDir)
}

android {
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    namespace = "io.nekohasekai.sagernet"
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric 页面测试要读真实的资源与 manifest
            isIncludeAndroidResources = true
            all { test ->
                // Robolectric 沙箱 + 原生绘图占内存，默认 512 MB 不够
                test.maxHeapSize = "2g"
                // 截图重录开关：-Pscreenshots.record=<正则>，只重写文件名匹配的基线；不传则逐张比较，没有「全部重录」
                providers.gradleProperty("screenshots.record").orNull?.let {
                    test.systemProperty("screenshots.record", it)
                }
                // 截图总是先渲染到 build 下的 actual 目录（比较与重录由 uitest/Screenshots.kt 决定），
                // 对 Roborazzi 来说就是 record；显式设上，免得它每张图都警告「没有配置 record」
                test.systemProperty("roborazzi.test.record", "true")
                // 运行时 jar 由上面的 robolectricRuntime 经 Gradle 下载并校验 sha256，测试只读本地，不让 Robolectric 自己联网
                test.dependsOn(syncRobolectricRuntime)
                test.systemProperty("robolectric.offline", "true")
                test.systemProperty("robolectric.dependency.dir", robolectricRuntimeDir.get().asFile.absolutePath)
            }
        }
    }
}

dependencies {

    implementation(fileTree("libs"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.work.multiprocess)

    implementation(libs.material)
    implementation(libs.gson)

    implementation(libs.zxing.lite)
    implementation(libs.blacksquircle.editorkit)
    implementation(libs.blacksquircle.language.base)
    implementation(libs.blacksquircle.language.json)

    implementation(libs.okhttp)
    implementation(libs.snakeyaml)
    implementation(libs.material.about)
    implementation(libs.process.phoenix)
    implementation(libs.kryo)
    implementation(libs.guava)

    implementation(libs.recyclerview.fastscroll) {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    robolectricRuntime(libs.robolectric.android.all)
}
