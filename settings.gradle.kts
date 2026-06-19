fun String?.isAndroidClawTruthy(): Boolean =
    equals("true", ignoreCase = true) ||
        equals("1", ignoreCase = true) ||
        equals("yes", ignoreCase = true)

val androidClawUseMavenLocal =
    providers.gradleProperty("androidclaw.mavenLocal").orNull.isAndroidClawTruthy() ||
        System.getenv("ANDROIDCLAW_MAVEN_LOCAL").isAndroidClawTruthy()

pluginManagement {
    repositories {
        val androidClawLocalMavenFallback = rootDir.resolve(".gradle/local-maven")
        if (androidClawLocalMavenFallback.isDirectory) {
            maven {
                name = "AndroidClawLocalMavenFallback"
                url = uri(androidClawLocalMavenFallback)
                content {
                    includeGroup("androidx.concurrent")
                    includeGroup("com.android.tools")
                    includeGroup("com.android.tools.external.com-intellij")
                    includeGroup("com.android.tools.external.org-jetbrains")
                    includeGroup("com.android.tools.lint")
                    includeGroup("com.google.errorprone")
                    includeGroup("commons-codec")
                    includeGroup("org.apache.httpcomponents")
                    includeGroup("org.codehaus.groovy")
                    includeGroup("org.jetbrains.kotlin")
                }
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val androidClawLocalMavenFallback = rootDir.resolve(".gradle/local-maven")
        if (androidClawLocalMavenFallback.isDirectory) {
            maven {
                name = "AndroidClawLocalMavenFallback"
                url = uri(androidClawLocalMavenFallback)
                content {
                    includeGroup("androidx.concurrent")
                    includeGroup("com.android.tools")
                    includeGroup("com.android.tools.external.com-intellij")
                    includeGroup("com.android.tools.external.org-jetbrains")
                    includeGroup("com.android.tools.lint")
                    includeGroup("com.google.errorprone")
                    includeGroup("commons-codec")
                    includeGroup("org.apache.httpcomponents")
                    includeGroup("org.codehaus.groovy")
                    includeGroup("org.jetbrains.kotlin")
                }
            }
        }
        if (androidClawUseMavenLocal) {
            mavenLocal {
                content {
                    includeGroup("com.google.devtools.ksp")
                    includeGroup("org.jetbrains.kotlin")
                    includeGroup("org.jetbrains.kotlinx")
                }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "AndroidClaw"
include(":app")
