// Top-level build file where you can add configuration options common to all sub-projects/modules.

import com.diffplug.spotless.extra.wtp.EclipseWtpFormatterStep

buildscript {
    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    extra.set("TRANSLATIONS_ONLY", System.getenv("TRANSLATIONS_ONLY")?.trim())
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath(libs.findLibrary("kotlin-gradle-plugin").get())
        classpath(libs.findLibrary("ksp-gradle-plugin").get())
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.dynamic.feature) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.spotless)
}

subprojects {
    buildscript {
        repositories {
            google()
            mavenCentral()
        }
    }
}

allprojects {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}

spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    ratchetFrom = "origin/main"

    java {
        target("app/src/main/java/org/connectbot/**/*.java")
        importOrder()
        removeUnusedImports()
        googleJavaFormat()
        licenseHeaderFile("spotless/license-header.txt")
    }

    format("buildJava", com.diffplug.gradle.spotless.JavaExtension::class.java) {
        target("buildSrc/src/main/java/**/*.java")
        importOrder()
        removeUnusedImports()
        googleJavaFormat()
        licenseHeaderFile("spotless/agent-license-header.txt")
    }

    kotlin {
        target("app/src/**/*.kt")
        targetExclude(
            "app/src/main/java/org/connectbot/agent/**/*.kt",
            "app/src/main/java/org/connectbot/ui/screens/agents/**/*.kt",
            "app/src/main/java/org/connectbot/service/AgentConnectionService.kt",
            "app/src/main/java/org/connectbot/util/PrivateKeyProtector.kt",
            "app/src/main/java/org/connectbot/util/HostKeyTrust.kt",
            "app/src/main/java/org/connectbot/service/BackupFilter.kt",
            "app/src/test/kotlin/org/connectbot/agent/**/*.kt",
            "app/src/test/kotlin/org/connectbot/ui/screens/agents/**/*.kt",
            "app/src/test/kotlin/org/connectbot/util/PrivateKeyProtectorTest.kt",
            "app/src/test/kotlin/org/connectbot/transport/HostKeyTrustTest.kt",
            "app/src/test/kotlin/org/connectbot/data/PubkeyRepositoryTest.kt",
            "app/src/test/kotlin/org/connectbot/ui/AppViewModelPrivateKeySecurityTest.kt",
            "app/src/test/kotlin/org/connectbot/service/BackupFilterTest.kt",
            "app/src/test/kotlin/org/connectbot/ui/screens/console/AgentTerminalLaunchTest.kt",
        )
        ktlint("1.8.0")
            .customRuleSets(listOf("io.nlopez.compose.rules:ktlint:0.6.7"))
        licenseHeaderFile("spotless/license-header.txt")
            .onlyIfContentMatches("(?s)^(?!.*Copyright[^\\n]*rmAgentMa contributors).*$")
    }

    format("agentKotlin", com.diffplug.gradle.spotless.KotlinExtension::class.java) {
        target(
            "agent-core/src/**/*.kt",
            "agent-ui/src/**/*.kt",
            "app/src/main/java/org/connectbot/agent/**/*.kt",
            "app/src/main/java/org/connectbot/ui/screens/agents/**/*.kt",
            "app/src/main/java/org/connectbot/service/AgentConnectionService.kt",
            "app/src/main/java/org/connectbot/util/PrivateKeyProtector.kt",
            "app/src/main/java/org/connectbot/util/HostKeyTrust.kt",
            "app/src/main/java/org/connectbot/service/BackupFilter.kt",
            "app/src/test/kotlin/org/connectbot/agent/**/*.kt",
            "app/src/test/kotlin/org/connectbot/ui/screens/agents/**/*.kt",
            "app/src/test/kotlin/org/connectbot/util/PrivateKeyProtectorTest.kt",
            "app/src/test/kotlin/org/connectbot/transport/HostKeyTrustTest.kt",
            "app/src/test/kotlin/org/connectbot/data/PubkeyRepositoryTest.kt",
            "app/src/test/kotlin/org/connectbot/ui/AppViewModelPrivateKeySecurityTest.kt",
            "app/src/test/kotlin/org/connectbot/service/BackupFilterTest.kt",
            "app/src/test/kotlin/org/connectbot/ui/screens/console/AgentTerminalLaunchTest.kt",
        )
        ktlint("1.8.0")
            .customRuleSets(listOf("io.nlopez.compose.rules:ktlint:0.6.7"))
        licenseHeaderFile("spotless/agent-license-header.txt")
    }

    val localArtifacts = arrayOf(".android-sdk/**", ".gradle*/**", ".kotlin/**", ".git/**", ".build-network/**", "**/build/**")

    kotlinGradle {
        target(
            fileTree(projectDir) {
                include("**/*.gradle.kts")
                exclude(*localArtifacts)
            },
        )
        ktlint("1.8.0")
    }

    format("xml") {
        target(
            fileTree(projectDir) {
                include("**/*.xml")
                exclude("**/.idea/**/*.xml", *localArtifacts)
            },
        )
        trimTrailingWhitespace()
        endWithNewline()
    }

    yaml {
        target(".github/**/*.yml", ".github/**/*.yaml")
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("toml") {
        target(
            fileTree(projectDir) {
                include("**/*.toml")
                exclude(*localArtifacts)
            },
        )
        trimTrailingWhitespace()
        endWithNewline()
    }

    format("misc") {
        target(
            fileTree(projectDir) {
                include("**/*.md", "**/.gitignore", "**/.gitattributes", "**/.editorconfig")
                exclude(*localArtifacts)
            },
        )
        trimTrailingWhitespace()
        endWithNewline()
    }
}
