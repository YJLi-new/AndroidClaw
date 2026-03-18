pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal {
            content {
                includeGroup("com.google.devtools.ksp")
                includeGroup("org.jetbrains.kotlin")
                includeGroup("org.jetbrains.kotlinx")
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "AndroidClaw"
include(":app")
