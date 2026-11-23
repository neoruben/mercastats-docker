import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("io.github.neoruben.mercadonastats.server.ApplicationKt")
}

dependencies {
    implementation(project(":core"))

    implementation(platform("io.ktor:ktor-bom:3.3.3"))
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("io.ktor:ktor-server-status-pages")
    implementation("io.ktor:ktor-server-call-logging")
    implementation("io.ktor:ktor-server-default-headers")

    implementation("org.xerial:sqlite-jdbc:3.54.1.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.eclipse.angus:jakarta.mail:2.0.5")
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("ch.qos.logback:logback-classic:1.5.38")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host")
}
