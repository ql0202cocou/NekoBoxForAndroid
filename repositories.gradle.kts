repositories {
    google()
    mavenCentral()
    // jitpack serves arbitrary com.github.* builds; restrict it to the groups
    // this project actually resolves from there (add a group when adding one).
    maven(url = "https://jitpack.io") {
        content {
            includeGroup("com.github.jenly1314")
            includeGroup("com.github.daniel-stoneuk")
        }
    }
}