pluginManagement {
    repositories {
        google { url = uri("https://dl.google.com/dl/android/maven2") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Google Maven 只服务 Google/AndroidX 官方 group；其它依赖走 Maven Central。
        // 避免代理环境下 google 源对 org.jetbrains/junit 等返回 502 拖慢构建。
        google {
            url = uri("https://dl.google.com/dl/android/maven2")
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx\\..*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "yuezhou-reader"

include(":app")
include(":core")
include(":reader")
include(":speech")
