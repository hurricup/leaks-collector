plugins {
    kotlin("jvm") version "2.1.0"
    application
}

group = "com.github.hurricup"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.leakcanary:shark-graph:2.14")
    implementation("com.squareup.leakcanary:shark-hprof:2.14")
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
