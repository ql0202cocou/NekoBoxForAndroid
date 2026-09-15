// Top-level build file where you can add configuration options common to all sub-projects/modules.
allprojects {
    apply(from = "${rootProject.projectDir}/repositories.gradle.kts")
}

tasks.register<Delete>("clean") {
    // Project.buildDir is deprecated (removed in Gradle 9)
    delete(rootProject.layout.buildDirectory)
    delete(project(":app").layout.buildDirectory)
}

plugins {
    id("com.google.devtools.ksp") version "2.3.11" apply false
}
