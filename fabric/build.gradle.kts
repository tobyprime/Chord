plugins {
    java
    // 26.x 无混淆时代的 loom（新插件 id，不做重映射）；旧 id fabric-loom 已改名 fabric-loom-remap
    // 版本在 settings.gradle.kts pluginManagement 里从 gradle.properties 的 loomVersion 锁定
    id("net.fabricmc.fabric-loom")
}

val minecraftVersion = project.property("minecraftVersion") as String
val loaderVersion = project.property("loaderVersion") as String
val fabricApiVersion = project.property("fabricApiVersion") as String

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    // 26.x 游戏无混淆：无需 mappings，loader/api 直接 implementation
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    implementation(project(":protocol"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

// protocol 模块类直接打进 mod jar（单 jar 分发，无需 loader 嵌套 jar 机制）
tasks.jar {
    from(project(":protocol").sourceSets.main.get().output)
}
