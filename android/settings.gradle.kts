pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        mavenLocal {
            content {
                includeGroup("io.github.libxposed")
            }
        }
    }
    versionCatalogs {
        create("libs")
    }
}

rootProject.name = "DouyinSparkCC"

// 共享业务库：续火花引擎、配置、宿主 hook、界面组件
include(":core-spark")
// 唯一的 application 模块：只做续火花，没有 flavor
include(":app")
