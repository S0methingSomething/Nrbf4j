plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply false
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

allprojects {
    pluginManager.withPlugin("com.diffplug.spotless") {
        extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
            kotlin {
                target("**/*.kt")
                ktlint()
            }
            kotlinGradle {
                target("**/*.kts")
                ktlint()
            }
        }
    }
}

subprojects {
    apply(plugin = "com.diffplug.spotless")
}

tasks.register("qualityCheck") {
    group = "verification"
    description = "Runs the local quality gate."
    dependsOn(
        ":lib:spotlessCheck",
        ":lib:detekt",
        ":lib:test",
    )
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://s01.oss.sonatype.org/service/local/"))
            snapshotRepositoryUrl.set(uri("https://s01.oss.sonatype.org/content/repositories/snapshots/"))
            username.set(System.getenv("MAVEN_CENTRAL_USERNAME"))
            password.set(System.getenv("MAVEN_CENTRAL_PASSWORD"))
        }
    }
}
