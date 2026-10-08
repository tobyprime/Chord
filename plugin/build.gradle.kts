plugins {
    `java-library`
}

val foliaApiVersion = project.property("foliaApiVersion") as String

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "PaperMC"
    }
    maven("https://repo.momirealms.net/releases") {
        name = "CraftEngine"
    }
    mavenCentral()
}

dependencies {
    compileOnly("dev.folia:folia-api:$foliaApiVersion")
    // CraftEngine 仅编译期依赖（软依赖）：家具右键经 FurnitureInteractEvent 接入，
    // 运行时未装 CE 不受影响，jar 不打包 CE 类
    compileOnly("net.momirealms:craft-engine-bukkit:26.9.1")
    compileOnly("net.momirealms:craft-engine-core:26.9.1")
    implementation(project(":protocol"))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

// 贫民版 shade：把 protocol 模块的产物一并打进插件 jar，服务端单文件即用
tasks.jar {
    from(project(":protocol").sourceSets.main.get().output)
}
