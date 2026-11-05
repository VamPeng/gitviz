import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("org.jetbrains.compose") version "1.8.2"
}

repositories {
    mavenCentral()
    google()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.3.0.202506031305-r")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    implementation("net.java.dev.jna:jna:5.17.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // 离屏截图也会初始化 AWT；测试 JVM 不应在 Dock 显示 Java 图标。
    systemProperty("java.awt.headless", "true")
}

val devDistribution = providers.gradleProperty("dev").orNull == "true"
val distributionIcon = if (devDistribution) "GitVizDev" else "GitViz"

compose.desktop {
    application {
        mainClass = "gitviz.MainKt"
        jvmArgs += listOf("-Xmx1g", "-XX:+UseSerialGC", "--add-opens=java.desktop/java.awt=ALL-UNNAMED")
        if (devDistribution) jvmArgs += "-Dgitviz.dev=true"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            modules("java.instrument", "java.management", "java.security.jgss", "java.sql", "jdk.unsupported")
            packageName = if (devDistribution) "GitViz Dev" else "GitViz"
            packageVersion = "1.0.0" // DMG 要求主版本号大于 0
            // 构建产物不参与 Spotlight 搜索，只展示 /Applications 中安装的两个版本。
            outputBaseDir.set(layout.buildDirectory.dir(
                if (devDistribution) "compose/packages.noindex/dev" else "compose/packages.noindex/release"
            ))
            // 图标由 scripts/icons.py 从 design/icon 的 SVG 导出
            macOS {
                packageName = if (devDistribution) "GitViz Dev" else "GitViz"
                bundleID = if (devDistribution) "gitviz.dev" else "gitviz"
                dockName = packageName
                iconFile.set(project.file("packaging/icons/$distributionIcon.icns"))
            }
            windows { iconFile.set(project.file("packaging/icons/$distributionIcon.ico")) }
            linux { iconFile.set(project.file("packaging/icons/$distributionIcon.png")) }
        }
    }
}

// 只影响开发启动，打包应用继续使用 nativeDistributions 的正式图标和名称。
tasks.withType<JavaExec>().matching { it.name == "run" || it.name == "runRelease" }.configureEach {
    if (System.getProperty("os.name").startsWith("Mac")) {
        val devIcon = project.file("packaging/icons/GitVizDev.icns").absolutePath
        jvmArgumentProviders.add(org.gradle.process.CommandLineArgumentProvider {
            listOf("-Xdock:name=GitViz Dev", "-Xdock:icon=$devIcon")
        })
    }
}
