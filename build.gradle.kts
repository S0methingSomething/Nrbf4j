plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.spotless) apply false
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
