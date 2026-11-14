pluginManagement {
    resolutionStrategy.eachPlugin {
        when (requested.id.id) {
            // The plugin MARKER POM is flaky on mirrors; resolve the real artifact directly.
            "com.google.devtools.ksp" -> useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
        }
    }
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Nanote"

include(":app")

include(":core:common")
include(":core:model")
include(":core:navigation")
include(":core:design")
include(":core:database")
include(":core:storage")
include(":core:data")
include(":core:graphics")
include(":core:pdf")

include(":feature:home")
include(":feature:library")
include(":feature:notebooks")
include(":feature:folders")
include(":feature:settings")
include(":feature:editor")
