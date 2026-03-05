plugins {
    kotlin("jvm") version "2.2.10"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "io.github.mingovvv"
version = "1.1.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(kotlin("test"))
    intellijPlatform {
        // Build against the lowest platform we want to support (wider compatibility range).
        intellijIdeaCommunity("2024.2")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

tasks.test {
    useJUnitPlatform()
}

sourceSets {
    main {
        kotlin.srcDir("src/main/io")
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "io.github.mingovvv.endpoint-lens"
        name = "Endpoint Lens"
        version = project.version.toString()

        ideaVersion {
            // 2024.2 (branch 242) to infinity
            sinceBuild = "242"
            untilBuild = "999.*"
        }
    }
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
