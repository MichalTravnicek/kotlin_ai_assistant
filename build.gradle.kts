plugins {
    kotlin("jvm") version "2.1.10"
    id("com.gradleup.shadow") version "9.3.1"
    id("io.ktor.plugin") version "3.1.0"
    kotlin("plugin.serialization") version "2.1.10"
}

application {
    mainClass.set("com.assistant.ApplicationKt")
}

// Fixed visual mapping needed for certain internal Ktor task properties
extra["mainClassName"] = "com.assistant.ApplicationKt"

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1") // Upgraded for compatibility

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.16")
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
