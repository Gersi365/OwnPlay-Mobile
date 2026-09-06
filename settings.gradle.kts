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
        ivy {
            name = "PinnedMedia3Ffmpeg"
            url = uri("https://raw.githubusercontent.com/moneytoo/Player/fb436e14a5cc03998e69a166f00401ddbc71a138/app/libs")
            patternLayout {
                artifact("[artifact].[ext]")
            }
            metadataSources {
                artifact()
            }
            content {
                includeGroup("app.ownplay.ffmpeg")
            }
        }
    }
}

rootProject.name = "OwnPlay"
include(":app")
