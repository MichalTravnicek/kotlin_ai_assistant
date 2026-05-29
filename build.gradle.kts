plugins {
    kotlin("jvm") version "2.1.10"
    id("io.ktor.plugin") version "3.0.3"
    kotlin("plugin.serialization") version "1.9.22"
}

application {
    mainClass.set("com.assistant.ApplicationKt")
}

tasks.named<JavaExec>("run") {
    mainClass.set("com.assistant.ApplicationKt")
}

// Add this for Ktor plugin compatibility
project.setProperty("mainClassName", "com.assistant.ApplicationKt")

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
