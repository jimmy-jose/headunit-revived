
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:9.2.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
    }
}

tasks.register("assembleHeadunitGithubDebug") {
    group = "build"
    description = "Assembles the Headunit Launcher GitHub debug APK."
    dependsOn(":app:assembleGithubDebug")
}

tasks.register("installHeadunitGithubDebug") {
    group = "installation"
    description = "Installs the Headunit Launcher GitHub debug APK on a connected device."
    dependsOn(":app:installGithubDebug")
}

tasks.register("assembleHeadunitHelperDebug") {
    group = "build"
    description = "Assembles the HeadUnit Helper debug APK."
    dependsOn(":headunit-helper-app:assembleDebug")
}

tasks.register("installHeadunitHelperDebug") {
    group = "installation"
    description = "Installs the HeadUnit Helper debug APK on a connected device."
    dependsOn(":headunit-helper-app:installDebug")
}
