plugins {
    kotlin("jvm") version "2.1.10"
    id("com.gradleup.shadow") version "9.3.1"
    id("io.ktor.plugin") version "3.1.0"
    kotlin("plugin.serialization") version "2.1.10"
    jacoco
}

application {
    mainClass.set("com.assistant.ApplicationKt")
}

// Fixed visual mapping needed for certain internal Ktor task properties
extra["mainClassName"] = "com.assistant.ApplicationKt"

jacoco {
    toolVersion = "0.8.14"
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.16")

    // Database
    implementation("com.h2database:h2:2.3.232")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // Test
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.1.10")
    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation("io.ktor:ktor-client-content-negotiation")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.10")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport) // Automatically run jacocoTestReport after tests finish
}

tasks.jacocoTestReport {
    dependsOn(tasks.test) // Ensure tests have actually run before generating reports

    reports {
        xml.required.set(true)  // Useful for CI/CD pipelines (SonarQube, GitHub Actions, etc.)
        html.required.set(true) // Human-readable report
    }
}
