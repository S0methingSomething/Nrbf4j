plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    `java-library`
    id("com.vanniktech.maven.publish") version "0.34.0"
}

group = "io.github.s0methingsomething"
version = System.getenv("RELEASE_VERSION") ?: "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
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

mavenPublishing {
    coordinates(group.toString(), "nrbf4j", version.toString())

    publishToMavenCentral()
    signAllPublications()

    pom {
        name.set("Nrbf4j")
        description.set("Kotlin library for parsing and editing MS-NRBF (BinaryFormatter) byte streams")
        url.set("https://github.com/S0methingSomething/Nrbf4j")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer {
                id.set("S0methingSomething")
                name.set("S0methingSomething")
                url.set("https://github.com/S0methingSomething")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/S0methingSomething/Nrbf4j.git")
            developerConnection.set("scm:git:ssh://git@github.com/S0methingSomething/Nrbf4j.git")
            url.set("https://github.com/S0methingSomething/Nrbf4j")
        }
    }
}
