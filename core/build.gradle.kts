import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy

repositories {
    flatDir {
        dirs(rootProject.file("libs"))
    }
    maven(url = "https://maven.refinedev.org/public-repo")
}

dependencies {
    implementation("com.github.luben:zstd-jni:1.5.7-4")
    implementation("co.aikar:fastutil-lite:1.0")

    testImplementation("junit:junit:4.13.1")

    compileOnly("io.papermc.paper:paper-api:1.8.8-R0.1-SNAPSHOT")
    compileOnly("com.sk89q.worldedit:worldedit-core:6.1.4-SNAPSHOT") {
        exclude(module = "bukkit-classloader-check")
    }
    compileOnly(rootProject.files("libs/redprotect.jar"))
    compileOnly(rootProject.files("libs/PlotSquared-Bukkit-3.823.jar"))
    compileOnly(rootProject.files("libs/BlocksHub.jar"))
}

tasks.named<Copy>("processResources") {
    from("src/main/resources") {
        include("fawe.properties")
        expand(
            mapOf(
                "version" to rootProject.version.toString(),
                "name" to rootProject.name
            )
        )
    }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
