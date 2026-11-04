import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Android ships org.json; on the JVM (tests, calibration) it comes from Maven
    compileOnly("org.json:json:20250517")
    testImplementation("org.json:json:20250517")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed"); showStandardStreams = true }
}

// real-route evidence for the ΔT·ΔD limits (needs network; writes docs/calibration.md)
tasks.register<JavaExec>("calibrate") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ai.ontheway.core.CalibrateKt")
    args(rootDir.resolve("app/src/main/assets/places.csv").absolutePath, rootDir.resolve("../docs/calibration.md").absolutePath)
}

// what choosing by detour saves against the closest-looking station (needs network; writes docs/savings.md)
tasks.register<JavaExec>("savings") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ai.ontheway.core.SavingsKt")
    args(rootDir.resolve("app/src/main/assets/places.csv").absolutePath, rootDir.resolve("../docs/savings.md").absolutePath)
}
