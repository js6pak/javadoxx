plugins {
    kotlin("jvm") version "1.6.10"
}

group = "pl.js6pak"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jsoup:jsoup:1.14.3")
    implementation("com.squareup:javapoet:1.13.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
