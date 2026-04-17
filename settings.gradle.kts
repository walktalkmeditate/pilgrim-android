import java.util.Properties

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

        // Mapbox Maps Android SDK lives behind an authenticated Maven repo.
        // The token (scope: DOWNLOADS:READ) comes from local.properties or
        // the MAPBOX_DOWNLOADS_TOKEN env var. Absent tokens make artifact
        // resolution fail loudly with a 401 rather than silently missing.
        val mapboxDownloadsToken: String = run {
            val props = Properties().apply {
                val localFile = file("local.properties")
                if (localFile.exists()) load(localFile.inputStream())
            }
            props.getProperty("MAPBOX_DOWNLOADS_TOKEN")
                ?: System.getenv("MAPBOX_DOWNLOADS_TOKEN")
                ?: ""
        }
        maven {
            name = "mapboxDownloads"
            url = uri("https://api.mapbox.com/downloads/v2/releases/maven")
            authentication {
                create<BasicAuthentication>("basic")
            }
            credentials {
                username = "mapbox"
                password = mapboxDownloadsToken
            }
            content {
                // Mapbox pulls in a fleet of transitive artifacts under
                // com.mapbox.maps / common / base / annotation / mapboxsdk /
                // extension / plugin / turf / etc. Rather than enumerate
                // each one, cover the whole namespace — it's Mapbox's own.
                includeGroupByRegex("com\\.mapbox\\..*")
            }
        }
    }
}

rootProject.name = "Pilgrim"
include(":app")
