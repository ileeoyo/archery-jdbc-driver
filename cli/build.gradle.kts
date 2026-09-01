plugins {
    java
    application
}

group = rootProject.group
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation(project(":"))
}

application {
    mainClass.set("com.lee.archery.tool.QueryRunner")
}

tasks.jar {
    archiveBaseName.set("archery-query-runner")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "com.lee.archery.tool.QueryRunner"
    }

    from({
        configurations.runtimeClasspath.get()
            .filter { it.exists() }
            .map { if (it.isDirectory) it else zipTree(it) }
    })
}
