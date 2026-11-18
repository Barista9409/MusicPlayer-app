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
        // NewPipe Extractor(2 つ目の取り方)はここから
        maven("https://jitpack.io")
    }
}
rootProject.name = "YtMp3"
include(":app")
