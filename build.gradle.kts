
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

tasks.register("assembleHeadunitLauncherHelperDebug") {
    group = "build"
    description = "Assembles the HeadUnitLauncher Helper debug APK."
    dependsOn(":headunitlauncher-helper-app:assembleDebug")
}

tasks.register("installHeadunitLauncherHelperDebug") {
    group = "installation"
    description = "Installs the HeadUnitLauncher Helper debug APK on a connected device."
    dependsOn(":headunitlauncher-helper-app:installDebug")
}
