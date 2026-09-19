@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

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
}
