plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "com.github.hurricup"
version = "2026.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.leakcanary:shark-graph:2.14")
    implementation("com.squareup.leakcanary:shark-hprof:2.14")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    implementation("ch.qos.logback:logback-classic:1.5.15")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.github.hurricup.leakscollector.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("generateVersionProperties") {
    val outputDir = layout.buildDirectory.dir("generated/resources")
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("version")
        dir.mkdirs()
        dir.resolve("version.properties").writeText("version=${project.version}\n")
    }
}

tasks.named("processResources") {
    dependsOn("generateVersionProperties")
}

sourceSets.main {
    resources.srcDir(layout.buildDirectory.dir("generated/resources"))
}
