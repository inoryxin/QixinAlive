pluginManagement {
    repositories {
        maven { url = uri("file:///C:/Users/anzu/Documents/xin/tools/m2") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = uri("file:///C:/Users/anzu/Documents/xin/tools/m2") }
        google()
        mavenCentral()
    }
}

rootProject.name = "QixinAlive"
include(":app")
