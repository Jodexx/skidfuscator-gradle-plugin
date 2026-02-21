plugins {
    `java-library`
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "2.0.0"
    `maven-publish`
    id("com.gradleup.shadow") version "9.3.1"
}

group = "dev.skidfuscator"
version = "0.1.4"

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")

    implementation("com.typesafe:config:1.4.3")
    implementation("commons-io:commons-io:2.11.0")

    compileOnly(gradleApi())
    compileOnly("org.codehaus.groovy:groovy-all:3.0.25")

    api("com.github.skidfuscatordev.skidfuscator-java-obfuscator:depend-analysis:2.0.11-EMERGENCY")
}

tasks.shadowJar {
    archiveClassifier = ""
}

gradlePlugin {
    website = "https://github.com/terminalsin/skidfuscator-java-obfuscator"
    vcsUrl = "https://github.com/terminalsin/skidfuscator-gradle-plugin"

    plugins {
        create("skidfuscator") {
            id = "dev.skidfuscator"
            displayName = "Skidfuscator"
            description = "The skidfuscator gradle plugin that will automatically obfuscates your jar on compile."
            implementationClass = "dev.skidfuscator.gradle.SkidfuscatorPlugin"
            tags.set(listOf("obfuscator", "skidfuscator"))
            group = "dev.skidfuscator"
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}