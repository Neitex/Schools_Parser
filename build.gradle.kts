import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.10"
    `maven-publish`
    application
}

group = "com.neitex"
version = "0.1.1"
val libraryVersion = version.toString()

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/ktor/eap") }
}

dependencies {
    implementation("io.ktor:ktor-client-core:2.0.0")
    implementation("io.ktor:ktor-client-cio:2.0.0")
    implementation("io.ktor:ktor-client-logging:2.0.0")
    implementation("ch.qos.logback:logback-classic:1.2.11")
    implementation("it.skrape:skrapeit:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.1-native-mt")
    testImplementation("org.junit.jupiter:junit-jupiter:5.8.2")
    testImplementation(kotlin("test"))
    implementation(kotlin("stdlib-jdk8"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "11"
}

tasks.getByName("publish") {
    dependsOn("test")
}

application {
    mainClass.set("MainKt")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            pom {
                name.set("Schools.by parser")
                description.set("Schools.by parser used by Eversity")
                url.set("https://github.com/Neitex/Schools_Parser")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("pavelm")
                        name.set("Pavel Matusevich")
                        email.set("neitex@protonmail.com")
                        url.set("https://neitex.me")
                        roles.add("Main developer")
                        timezone.set("GMT+3")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/Neitex/Schools_Parser.git")
                    url.set("https://github.com/Neitex/Schools_Parser.git")
                }
                versionMapping {
                    usage("java-api") {
                        fromResolutionOf("runtimeClasspath")
                    }
                    usage("java-runtime") {
                        fromResolutionResult()
                    }
                }
            }
            repositories {
                maven {
                    url = uri("https://packages.neitex.me/releases")
                    credentials.username = System.getenv("reposiliteAlias")
                    credentials.password = System.getenv("reposilitePassword")

                    authentication {
                        create<BasicAuthentication>("basic")
                    }
                }
            }
            groupId = "com.neitex"
            artifactId = "schools_parser"
            version = libraryVersion
            from(components["java"])
        }
    }
}
