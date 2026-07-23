pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
        maven("https://repo.boox.com/repository/maven-public/") {
            content {
                includeGroup("com.onyx.android.sdk")
            }
        }
    }
}

rootProject.name = "CodexBarInk"
include(":app")
include(":reader-core")
