import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    kotlin("jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
}

group = "com.olegs.jsonl"
version = "0.3.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.1.7")
    }

    compileOnly("com.google.code.gson:gson:2.10.1")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    buildSearchableOptions = false
}

// Sandbox the documentation screenshots are captured from (docs/screenshots/capture/). It runs the
// IDE release the published shots show, with JetBrains' robot server so the capture scripts can
// stage state and paint components over HTTP instead of driving the mouse. Its plugin repository
// points at a closed local port: with no marketplace to ask, the Settings tree shows no update count
// on Plugins, and nothing a shot shows depends on the network.
val runIdeForScreenshots by intellijPlatformTesting.runIde.registering {
    type = IntelliJPlatformType.IntellijIdea
    version = "2026.1"
    task {
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Drobot-server.port=8582",
                "-Djb.privacy.policy.text=<!--999.999-->",
                "-Djb.consents.confirmation.enabled=false",
                "-Didea.initially.ask.config=never",
                "-Didea.trust.all.projects=true",
                "-Dide.show.tips.on.startup.default.value=false",
                "-Didea.suppress.statistics.report=true",
                "-Didea.plugins.host=http://127.0.0.1:9",
                "-Dsun.java2d.uiScale=1.5",
            )
        }
    }
    plugins {
        robotServerPlugin("0.11.23")
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    wrapper {
        gradleVersion = "9.0.0"
    }
}
