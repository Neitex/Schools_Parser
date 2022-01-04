import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.10"
    `maven-publish`
    application
}

group = "com.neitex"
version = "0.0.1"
val libraryVersion = version.toString()

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-client-core:1.6.7")
    implementation("io.ktor:ktor-client-cio:1.6.7")
    implementation("io.ktor:ktor-client-logging:1.6.7")
    implementation("ch.qos.logback:logback-classic:1.2.10")
    implementation("it.skrape:skrapeit:1.1.7")
    testImplementation("org.junit.jupiter:junit-jupiter:5.7.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

application {
    mainClass.set("MainKt")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.neitex"
            artifactId = "schools_parser"
            version = libraryVersion

            from(components["java"])
        }
    }
}