import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy
import org.gradle.kotlin.dsl.withGroovyBuilder

apply(plugin = "com.gradleup.shadow")

repositories {
    flatDir {
        dirs(rootProject.file("libs"))
    }
    maven(url = "https://maven.refinedev.org/public-repo")
}

dependencies {
    implementation(project(":core"))
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:6.1.4-SNAPSHOT")
    compileOnly("com.sk89q:worldguard:6.0.0-SNAPSHOT") {
        exclude("org.bukkit")
        exclude("com.sk89q", "worldedit")
    }

    compileOnly("xyz.refinedev.spigot:Carbon-API:1.8.8-R0.1-SNAPSHOT")
    compileOnly("io.papermc.paper:paper-server:1.8.8-R0.1-SNAPSHOT")

    compileOnly("com.github.MilkBowl:VaultAPI:1.7")
    compileOnly("net.dmulloy2:ProtocolLib:5.1.0")
    compileOnly("com.wasteofplastic:askyblock:3.0.9.4")
    compileOnly("org.inventivetalent:mapmanager:1.7.2-SNAPSHOT") {
        exclude("org.inventivetalent.packetlistener")
    }

    testImplementation("junit:junit:4.13.1")

    compileOnly(rootProject.files("libs/GriefPrevention.jar"))
    compileOnly(rootProject.files("libs/regios.jar"))
    compileOnly(rootProject.files("libs/Residence.jar"))
    compileOnly(rootProject.files("libs/plotme_core.jar"))
    compileOnly(rootProject.files("libs/BlocksHub.jar"))
    compileOnly(rootProject.files("libs/voxelsniper-5.171.0.jar"))
}

tasks.named<Copy>("processResources") {
    from("src/main/resources") {
        include("plugin.yml")
        expand(
            mapOf(
                "name" to rootProject.name,
                "version" to rootProject.version.toString()
            )
        )
    }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.named<ShadowJar>("shadowJar") {
    dependencies {
        include(dependency("com.github.luben:zstd-jni:1.5.7-4"))
        include(dependency("co.aikar:fastutil-lite:1.0"))
        include(project(":core"))
    }
    archiveFileName.set("${rootProject.name}-${project.name}-${rootProject.version}.jar")
    destinationDirectory.set(rootProject.file("jars"))

    doLast {
        ant.withGroovyBuilder {
            "checksum"("file" to archiveFile.get().asFile)
        }
    }
}

tasks.named("build") {
    dependsOn(tasks.named("shadowJar"))
}
