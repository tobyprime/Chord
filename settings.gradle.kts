pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        mavenCentral()
        gradlePluginPortal()
    }
    // 插件版本统一从 gradle.properties 读（CI 与本地构建可复现，不用 SNAPSHOT）。
    // 两个 id 同属 loom：fabric-loom = 26.x 无混淆时代（不做重映射），
    // fabric-loom-remap = 1.21.x 混淆时代（mojmap + remapJar）
    val loomVersion: String by settings
    plugins {
        id("net.fabricmc.fabric-loom") version loomVersion
        id("net.fabricmc.fabric-loom-remap") version loomVersion
    }
}

rootProject.name = "chord"

include("protocol")
include("plugin")

// fabric 是共享源码容器（无构建脚本）；每个 MC 版本一个子项目，源码 = 版本目录 overlay 共享目录
include("fabric")

// 多版本接线（参照 Mcedia）：fabric/versions/<mc>/ 各自携带 gradle.properties，
// 26.x 走 build.fabric.gradle（免重映射），1.21.x 走 build.fabric.remap.gradle
val supportedMcVersions = providers.gradleProperty("supported_mc_versions")
    .orElse("26.2")
    .get()
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

fun parseMcVersionToNumber(mcVersion: String): Long {
    val parts = mcVersion.split("-")[0].replace(Regex("[^0-9.]"), "")
        .split(".").filter { it.isNotEmpty() }
    val major = parts.getOrElse(0) { "0" }.toLong()
    val minor = parts.getOrElse(1) { "0" }.toLong()
    val patch = parts.getOrElse(2) { "0" }.toLong()
    return major * 10000 + minor * 100 + patch
}

supportedMcVersions.forEach { mcVersion ->
    val versionedProjectName = "fabric-$mcVersion"
    include(versionedProjectName)
    project(":$versionedProjectName").projectDir = file("fabric/versions/$mcVersion")
    project(":$versionedProjectName").buildFileName =
        if (parseMcVersionToNumber(mcVersion) > 260000) "../../build.fabric.gradle"
        else "../../build.fabric.remap.gradle"
}

// 全版本合一 jar：分发入口 + mixin 过滤，汇集两套版本化字节码
include("fabric-allinone")
project(":fabric-allinone").projectDir = file("fabric/allinone")
project(":fabric-allinone").buildFileName = "../build.fabric.allinone.gradle"
