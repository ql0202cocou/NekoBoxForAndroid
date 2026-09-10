import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByName
import org.gradle.kotlin.dsl.register
import java.io.File
import java.util.Base64
import java.util.Properties
import kotlin.system.exitProcess

private val Project.android get() = extensions.getByName<ApplicationExtension>("android")

// Read on every call. buildSrc classes outlive a single build inside the Gradle daemon, so holding
// these in file-scope state made an edited nb4a.properties / local.properties invisible until the
// daemon was restarted — a bumped version silently produced APKs labelled with the previous one.
fun Project.requireMetadata(): Properties = Properties().apply {
    rootProject.file("nb4a.properties").inputStream().use { load(it) }
}

fun Project.requireLocalProperties(): Properties = Properties().apply {
    val base64 = System.getenv("LOCAL_PROPERTIES")
    if (!base64.isNullOrBlank()) {
        Base64.getDecoder().decode(base64).inputStream().use { load(it) }
    } else {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
}

fun Project.setupCommon() {
    android.apply {
        buildToolsVersion = "36.0.0"
        compileSdk = 37
        defaultConfig {
            minSdk = 23
            targetSdk = 36
        }
        buildTypes {
            getByName("release") {
                isMinifyEnabled = true
            }
        }
        // built-in Kotlin (AGP 9) takes its jvmTarget from targetCompatibility; core 1.19's
        // inline functions are JVM 11 bytecode, so the target cannot stay at 1.8 (AGP 9's
        // default is 11 as well; D8 desugars it for minSdk 23)
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
        lint {
            showAll = true
            checkAllWarnings = true
            checkReleaseBuilds = true
            warningsAsErrors = true
            textOutput = project.file("build/lint.txt")
            htmlOutput = project.file("build/lint.html")
        }
        packaging {
            resources.excludes.addAll(
                listOf(
                    "**/*.kotlin_*",
                    "/META-INF/*.version",
                    "/META-INF/native/**",
                    "/META-INF/native-image/**",
                    "/META-INF/INDEX.LIST",
                    "DebugProbesKt.bin",
                    "**/*.java",
                    "**/*.proto",
                    "okhttp3/**"
                )
            )
        }
        buildTypes {
            getByName("release") {
                isShrinkResources = true
                if (System.getenv("nkmr_minify") == "0") {
                    isShrinkResources = false
                    isMinifyEnabled = false
                }
            }
            getByName("debug") {
                applicationIdSuffix = "debug"
                isDebuggable = true
                isJniDebuggable = true
            }
        }
    }
}

fun Project.setupAppCommon() {
    setupCommon()

    val lp = requireLocalProperties()
    val keystorePwd = lp.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
    val alias = lp.getProperty("ALIAS_NAME") ?: System.getenv("ALIAS_NAME")
    val pwd = lp.getProperty("ALIAS_PASS") ?: System.getenv("ALIAS_PASS")

    android.apply {
        if (keystorePwd != null) {
            signingConfigs {
                create("release") {
                    storeFile = rootProject.file("release.keystore")
                    storePassword = keystorePwd
                    keyAlias = alias
                    keyPassword = pwd
                }
            }
        }
        buildTypes {
            val key = signingConfigs.findByName("release")
            if (key != null) {
                getByName("release").signingConfig = key
                getByName("debug").signingConfig = key
            }
        }
    }
}

fun Project.setupApp() {
    val pkgName = requireNotNull(requireMetadata().getProperty("PACKAGE_NAME")) {
        "PACKAGE_NAME is missing in nb4a.properties"
    }
    val verName = requireNotNull(requireMetadata().getProperty("VERSION_NAME")) {
        "VERSION_NAME is missing in nb4a.properties"
    }
    val verCode = requireNotNull(requireMetadata().getProperty("VERSION_CODE")) {
        "VERSION_CODE is missing in nb4a.properties"
    }.toInt() * 5
    android.apply {
        defaultConfig {
            applicationId = pkgName
            versionCode = verCode
            versionName = verName
            buildConfigField("String", "PRE_VERSION_NAME", "\"\"")
        }
    }
    setupAppCommon()

    android.apply {
        buildTypes {
            getByName("release") {
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    file("proguard-rules.pro")
                )
            }
        }

        splits.abi {
            reset()
            isEnable = true
            isUniversalApk = true
            include("arm64-v8a")
            include("x86_64")
        }

        flavorDimensions += "vendor"
        productFlavors {
            create("oss")
            create("fdroid")
            create("preview") {
                buildConfigField(
                    "String",
                    "PRE_VERSION_NAME",
                    "\"${requireNotNull(requireMetadata().getProperty("PRE_VERSION_NAME")) {
                        "PRE_VERSION_NAME is missing in nb4a.properties"
                    }}\""
                )
            }
        }

        for (abi in listOf("Arm64", "X64")) {
            tasks.register("assemble" + abi + "FdroidRelease") {
                // Historical task name kept for existing callers: it builds the full
                // fdroid release (all ABI splits), not just the ABI in the name.
                description = "Builds the full fdroid release (all ABIs); the ABI in the task name is historical."
                dependsOn("assembleFdroidRelease")
            }
        }

        sourceSets.getByName("main").apply {
            jniLibs.directories += "executableSo"
        }
    }

    // The geo assets are downloaded by buildScript/lib/assets.sh, not checked in. Without
    // them the APK builds fine and libcore only logs "Extract geoip.db failed" at runtime,
    // so an APK from a tree where the download never ran (or a replacement was killed
    // halfway) must fail here instead.
    tasks.named("preBuild").configure {
        doFirst {
            val missing = listOf(
                "geoip.db.xz", "geoip.version.txt", "geosite.db.xz", "geosite.version.txt"
            ).filterNot { file("src/main/assets/sing-box/$it").isFile }
            check(missing.isEmpty()) {
                "missing sing-box assets ${missing.joinToString()}: run ./run lib assets first"
            }
        }
    }

    // AGP 9 removed applicationVariants/outputFileName; the APKs are renamed by a
    // SingleArtifact.APK transform instead, so build/outputs/apk keeps the same names.
    extensions.getByName<ApplicationAndroidComponentsExtension>("androidComponents").onVariants { variant ->
        val rename = tasks.register<RenameApksTask>("rename${variant.name.replaceFirstChar { it.uppercase() }}Apks") {
            val preVersionName = requireNotNull(requireMetadata().getProperty("PRE_VERSION_NAME")) {
                "PRE_VERSION_NAME is missing in nb4a.properties"
            }
            projectName.set(project.name)
            newBaseName.set(
                if (variant.flavorName == "preview") "NekoBox-$preVersionName"
                else "NekoBox-$verName"
            )
        }
        val request = variant.artifacts.use(rename)
            .wiredWithDirectories(RenameApksTask::inputDir, RenameApksTask::outputDir)
            .toTransformMany(SingleArtifact.APK)
        rename.configure { transformationRequest.set(request) }
    }
}

abstract class RenameApksTask : DefaultTask() {
    @get:InputFiles
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val transformationRequest: Property<ArtifactTransformationRequest<RenameApksTask>>

    @get:Input
    abstract val projectName: Property<String>

    @get:Input
    abstract val newBaseName: Property<String>

    @TaskAction
    fun run() {
        // app-oss-arm64-v8a-release.apk -> NekoBox-<ver>-arm64-v8a.apk (preview: NekoBox-<pre>-arm64-v8a.apk)
        transformationRequest.get().submit(this) { artifact ->
            val input = File(artifact.outputFile)
            val name = input.name.replace(projectName.get(), newBaseName.get())
                .replace("-preview", "")
                .replace("-release", "")
                .replace("-oss", "")
            val output = File(outputDir.get().asFile, name)
            input.copyTo(output, overwrite = true)
            output
        }
    }
}