plugins {
    id("java")
    id("com.gradleup.shadow") version "9.0.0-beta4"
}

group = "su.enji"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains:annotations:26.1.0")
    implementation("org.json:json:20250517")
    implementation("org.yaml:snakeyaml:2.6")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}


tasks {
    shadowJar {
        archiveClassifier.set("")
    }

    jar {
        enabled = false
        manifest {
            attributes["Main-Class"] = "su.enji.Main"
        }
    }

    build {
        dependsOn(shadowJar)
    }
}