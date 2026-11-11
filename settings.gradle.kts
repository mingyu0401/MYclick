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
        google()
        mavenCentral()
        // 仅当你想用远程 Xposed API 替代 app/libs/xposed-api-82.jar 时才需要
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "MYclick"
include(":app")
