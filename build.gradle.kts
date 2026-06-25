plugins {
    java
}

group = "com.lee.archery"
version = providers.gradleProperty("driverVersion").getOrElse("0.2.1")

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.processResources {
    inputs.property("driverVersion", project.version.toString())
    filesMatching("archery-jdbc-driver.properties") {
        expand("driverVersion" to project.version.toString())
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // DataGrip 只会加载用户添加的 Driver jar，因此需要把 OkHttp、Jackson 等运行时依赖一起打入 jar。
    from({
        configurations.runtimeClasspath.get()
            .filter { it.exists() }
            .map { if (it.isDirectory) it else zipTree(it) }
    })
}

tasks.test {
    useJUnitPlatform()
}
