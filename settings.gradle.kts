pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        mavenCentral()
        gradlePluginPortal()
    }
    // 插件版本统一从 gradle.properties 读（CI 与本地构建可复现，不用 SNAPSHOT）
    val loomVersion: String by settings
    plugins {
        id("net.fabricmc.fabric-loom") version loomVersion
    }
}

rootProject.name = "chord"

include("protocol")
include("fabric")
include("plugin")
