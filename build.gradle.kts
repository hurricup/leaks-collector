plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "com.github.hurricup"
version = "2026.2"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.leakcanary:shark-graph:2.14")
    implementation("com.squareup.leakcanary:shark-hprof:2.14")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.3")
    implementation("ch.qos.logback:logback-classic:1.5.15")
    testImplementation(kotlin("test"))
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
    testImplementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    testImplementation("com.networknt:json-schema-validator:1.5.6")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.github.hurricup.leakscollector.MainKt")
}

tasks.test {
    useJUnitPlatform()
    outputs.upToDateWhen { false }
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
