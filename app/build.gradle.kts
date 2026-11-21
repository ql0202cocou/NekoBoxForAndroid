@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.BuildConfigField

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

// 只给调试采集器标记构建时的 HEAD；源码压缩包、无 Git 环境留空。
val goldenGitRevision = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", "--git-dir=.git", "--work-tree=.", "describe", "--always", "--abbrev=64", "--dirty", "--match", "")
    isIgnoreExitValue = true
}.standardOutput.asText
androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    requireNotNull(variant.buildConfigFields).put("GOLDEN_BUILD_REVISION", providers.provider {
        val revision = try {
            goldenGitRevision.get().trim()
        } catch (_: GradleException) {
            ""
        }
        val stamp = revision.takeIf { it.matches(Regex("(?:[0-9a-f]{40}|[0-9a-f]{64})(?:-dirty)?")) }.orEmpty()
        BuildConfigField("String", "\"$stamp\"", null)
    })
}

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
    if (providers.gradleProperty("xhttpProbeExporter").isPresent) {
        sourceSets.getByName("test").kotlin.srcDir(rootProject.file("experiments/core-probe/xray/xhttp/exporter"))
    }
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
            // Xray 上游已剥离调试符号，保留原始字节才能按发布摘要核对包内核心。
            keepDebugSymbols += "**/libxray.so"
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
                // CI 默认日志只显示断言位置，完整栈才能定位后台线程的未捕获异常
                test.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
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
