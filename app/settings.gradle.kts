pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JOGL, which the embedded browser (JCEF) draws through; it isn't on Central.
        maven("https://jogamp.org/deployment/maven/") {
            content { includeGroupByRegex("org\\.jogamp.*") }
        }
    }
}

rootProject.name = "NovelScraper"
include(":composeApp", ":androidApp")
