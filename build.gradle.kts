plugins {
    alias(libs.plugins.fabric.loom)
    `maven-publish`
    java
}

version = project.property("mod_version") as String
group   = project.property("maven_group") as String

repositories {
    maven("https://maven.meteordev.org/snapshots")
    maven("https://maven.meteordev.org/releases")
    mavenCentral()
}

dependencies {
    minecraft(libs.minecraft)
    mappings(variantOf(libs.yarn) { classifier("v2") })
    modImplementation(libs.fabric.loader)
    modImplementation(libs.fabric.api)
    modImplementation(libs.meteor.client)

    // Baritone API (compile only — provided at runtime by baritone-meteor in mods folder)
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    jar {
        from("LICENSE")
    }

    processResources {
        val map = mapOf("version" to project.version)
        inputs.properties(map)
        filesMatching("fabric.mod.json") { expand(map) }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}
