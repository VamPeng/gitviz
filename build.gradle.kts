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
}

compose.desktop {
    application {
        mainClass = "gitviz.MainKt"
        jvmArgs += listOf("-Xmx1g", "-XX:+UseSerialGC", "--add-opens=java.desktop/java.awt=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            modules("java.instrument", "java.management", "java.security.jgss", "java.sql", "jdk.unsupported")
            packageName = "GitViz"
            packageVersion = "1.0.0" // DMG 要求主版本号大于 0
            // 图标由 scripts/icons.py 从 design/icon 的 SVG 导出
            macOS { iconFile.set(project.file("packaging/icons/GitViz.icns")) }
            windows { iconFile.set(project.file("packaging/icons/GitViz.ico")) }
            linux { iconFile.set(project.file("packaging/icons/GitViz.png")) }
        }
    }
}
