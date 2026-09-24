plugins {
    java
    application
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("a429.Main")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation("io.javalin:javalin:6.6.0")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
    implementation("org.slf4j:slf4j-simple:2.0.20")

    testImplementation(platform("org.junit:junit-bom:5.14.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    systemProperty("file.encoding", "UTF-8")
}
