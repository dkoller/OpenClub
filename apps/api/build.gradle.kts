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
    implementation("com.nimbusds:oauth2-oidc-sdk:11.39")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    jooqGenerator("org.jooq:jooq-codegen:$jooqVersion")
    jooqGenerator("org.postgresql:postgresql:42.7.8")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("org.openclub.api.MainKt")
}

tasks.test {
    useJUnitPlatform()
    // Integration tests depend on the live PostgreSQL service, which is not a task
    // input: never restore their result from the build cache, always run them.
    outputs.cacheIf("PostgreSQL-backed tests must always execute") { false }
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

tasks.register<JavaExec>("bootstrapFirstClub") {
    group = "application"
    description = "Operator-only, one-time setup of the sole club and first administrator."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openclub.api.club.BootstrapCliKt")
}

tasks.register<JavaExec>("recoverSoleAdmin") {
    group = "application"
    description = "Restricted operator-only rebind of the sole administrator after login loss."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openclub.api.club.AdminRecoveryCliKt")
}
