plugins {
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

val minecraftVersion = property("minecraft_version") as String
version = "${property("mod_version")}+$minecraftVersion"
group = property("maven_group") as String

base {
    archivesName = "antifreecam-fabric"
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

repositories {
    mavenCentral()
    maven("https://maven.nucleoid.xyz/") { name = "Nucleoid" } // fabric-permissions-api
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation(include("com.moandjiezana.toml:toml4j:${property("toml_version")}")!!)
    implementation(include("me.lucko:fabric-permissions-api:${property("fabric_permission_api_version")}")!!)
}

loom {
    accessWidenerPath = file("src/main/resources/antifreecam.accesswidener")
}

tasks.processResources {
    val props = mapOf("version" to project.version, "minecraftVersion" to minecraftVersion)
    inputs.properties(props)
    filesMatching("fabric.mod.json") {
        expand(props)
    }
}

tasks.jar {
    from("LICENSE") { rename { "LICENSE_antifreecam" } }
    from("THIRD_PARTY_LICENSES.md")
}
