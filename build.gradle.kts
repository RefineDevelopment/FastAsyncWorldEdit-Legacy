import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions
import org.jetbrains.gradle.ext.runConfigurations
import org.jetbrains.gradle.ext.settings
import java.text.SimpleDateFormat
import java.util.Locale

buildscript {
    repositories {
        mavenCentral()
        maven(url = "https://oss.sonatype.org/content/repositories/snapshots/")
        maven(url = "https://plugins.gradle.org/m2/")
    }
    dependencies {
        classpath("com.gradleup.shadow:shadow-gradle-plugin:8.3.11")
        classpath("org.ajoberstar:grgit:1.7.0")
    }
    configurations.all {
        resolutionStrategy {
            force("org.ow2.asm:asm:6.0_BETA")
        }
    }
}

plugins {
    java
    id("org.jetbrains.gradle.plugin.idea-ext") version "1.4.1"
}

fun RepositoryHandler.faweRepositories() {
    mavenCentral()
    maven(url = "https://repo.destroystokyo.com/repository/maven-public//")
    maven(url = "https://ci.athion.net/plugin/repository/tools/")
    maven(url = "https://hub.spigotmc.org/nexus/content/groups/public/")
    maven(url = "https://maven.enginehub.org/repo/")
    maven(url = "https://repo.maven.apache.org/maven2")
    maven(url = "https://ci.frostcast.net/plugin/repository/everything")
    maven(url = "https://repo.spongepowered.org/maven")
    maven(url = "https://repo.inventivetalent.org/content/groups/public/")
    maven(url = "https://store.ttyh.ru/libraries/")
    maven(url = "https://repo.dmulloy2.net/nexus/repository/public/")
    maven(url = "https://maven.elmakers.com/repository/")
    maven(url = "https://ci.ender.zone/plugin/repository/everything/")
    maven(url = "https://repo.papermc.io/repository/maven-public/")
    maven(url = "https://jitpack.io")
    maven(url = "https://repo.codemc.org/repository/maven-public")
}

fun Project.computeFaweVersion(): String {
    var revision = ""
    var buildNumber = ""
    var semver = ""
    var date = ""

    try {
        val git = org.ajoberstar.grgit.Grgit.open()
        try {
            val head = git.head()
            date = SimpleDateFormat("yy.MM.dd").format(head.date)
            revision = "-${head.abbreviatedId}"

            var parents: List<String> = head.parentIds
            var index = -67
            var major = 0
            var minor = 0
            var patch = 0

            while (parents.isNotEmpty()) {
                var majorCount = 0
                var minorCount = 0
                var patchCount = if (minor == 0 && major == 0) 1 else 0
                val commit = git.resolve.toCommit(parents[0])

                commit.fullMessage.lineSequence().forEach { line: String ->
                    val normalizedPrefix = line
                        .removePrefix("- ")
                        .substringBefore(' ')
                        .lowercase(Locale.ROOT)

                    when (normalizedPrefix) {
                        "minor", "added", "add", "change", "changed", "changes" -> {
                            if (major == 0) {
                                minorCount = 1
                                patchCount = 0
                            }
                        }
                        "refactor", "remove", "major" -> {
                            majorCount = 1
                            minorCount = 0
                            patchCount = 0
                        }
                    }
                }

                major += majorCount
                minor += minorCount
                patch += patchCount
                parents = commit.parentIds
                index++
            }

            buildNumber = "-$index"
            semver = "-$major.$minor.$patch"
        } finally {
            git.close()
        }
    } catch (_: Throwable) {
        revision = "unknown"
    }

    return date + revision + buildNumber + semver
}

group = "com.boydti.fawe"
description = "FastAsyncWorldEdit"
version = if (hasProperty("lzNoVersion")) "unknown" else computeFaweVersion()

idea {
    project {
        settings {
            runConfigurations {
                create<org.jetbrains.gradle.ext.Gradle>("Build") {
                    taskNames = listOf("build")
                }
            }
        }
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "eclipse")
    apply(plugin = "idea")

    group = rootProject.group
    version = rootProject.version
    description = rootProject.description

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-parameters", "-Xprefer:source"))
    }

    repositories {
        faweRepositories()
    }
}

val aggregatedJavadocs by tasks.registering(Javadoc::class) {
    description = "Generate javadocs from all child projects as if it was a single project"
    group = "Documentation"
    outputs.dir(file("./docs/javadoc"))
    title = "${project.name} $version API"

    (options as StandardJavadocDocletOptions).apply {
        setAuthor(true)
        links(
            "https://docs.spring.io/spring/docs/4.3.x/javadoc-api/",
            "https://docs.oracle.com/javase/8/docs/api/",
            "https://docs.spring.io/spring-ws/docs/2.3.0.RELEASE/api/",
            "https://docs.spring.io/spring-security/site/docs/4.0.4.RELEASE/apidocs/"
        )
        addStringOption("Xdoclint:none", "-quiet")
    }

    doFirst {
        project.delete("./docs")
    }
}

gradle.projectsEvaluated {
    aggregatedJavadocs.configure {
        subprojects.forEach { subproject ->
            subproject.tasks.withType(Javadoc::class.java).forEach { javadocTask ->
                source(javadocTask.source)
                classpath = classpath.plus(javadocTask.classpath)
                javadocTask.excludes.forEach { exclude(it) }
                javadocTask.includes.forEach { include(it) }
            }
        }
    }
}
