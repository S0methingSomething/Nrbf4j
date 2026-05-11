plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    `java-library`
    `maven-publish`
}

group = "io.github.s0methingsomething"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

java {
    withJavadocJar()
    withSourcesJar()
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
    ignoreFailures = false
    parallel = true
}

dependencies {
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Nrbf4j")
                description.set("Kotlin library for parsing and editing MS-NRBF (BinaryFormatter) byte streams")
                url.set("https://github.com/S0methingSomething/Nrbf4j")
            }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/S0methingSomething/Nrbf4j")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}
