plugins {
    kotlin("jvm") version "2.2.10"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "mingovvv"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(kotlin("test"))
    intellijPlatform {
        intellijIdeaCommunity("2025.2.2")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        id = "mingovvv.endpoint-lens"
        name = "Endpoint Lens"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "252"
        }
    }
}

tasks.register("runId") {
    group = "run"
    description = "Alias for runIde"
    dependsOn("runIde")
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    // Workaround for IntelliJ Platform Gradle Plugin 2.2.1 generating an empty coroutines javaagent jar.
    coroutinesJavaAgentFile.set(layout.buildDirectory.file("disabled-coroutines-agent.jar"))
    // Dynamic reload can unload non-unload-safe plugin pieces during development.
    systemProperty("idea.auto.reload.plugins", "false")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}
