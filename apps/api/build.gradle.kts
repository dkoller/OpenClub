plugins {
    kotlin("jvm") version "2.4.21"
    application
}

group = "org.openclub"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.6.1"
val jooqVersion = "3.20.20"
val flywayVersion = "13.11.1"

val jooqGenerator = configurations.create("jooqGenerator")

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("org.jooq:jooq:$jooqVersion")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")
    implementation("org.postgresql:postgresql:42.7.8")
    implementation("com.zaxxer:HikariCP:6.3.3")

    jooqGenerator("org.jooq:jooq-codegen:$jooqVersion")
    jooqGenerator("org.postgresql:postgresql:42.7.8")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.3")
    testImplementation("org.testcontainers:postgresql:1.21.3")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.openclub.api.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("generateJooq") {
    group = "database"
    description = "Generate jOOQ Java sources from an already migrated local PostgreSQL schema."
    classpath = jooqGenerator
    mainClass.set("org.jooq.codegen.GenerationTool")
    args("src/main/resources/jooq-codegen.xml")
}

sourceSets.main {
    java.srcDir(layout.buildDirectory.dir("generated-src/jooq"))
}
