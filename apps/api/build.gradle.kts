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

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("org.jooq:jooq:$jooqVersion")
    // Logging (owner-approved 2026-11-27): failures reach the server log; see src/main/resources/logback.xml.
    implementation("ch.qos.logback:logback-classic:1.5.18")
    implementation("org.flywaydb:flyway-core:$flywayVersion")
    implementation("org.flywaydb:flyway-database-postgresql:$flywayVersion")
    implementation("org.postgresql:postgresql:42.7.8")
    implementation("com.zaxxer:HikariCP:6.3.3")
    implementation("com.nimbusds:oauth2-oidc-sdk:11.39")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // SMTP for email notifications (OpenAPI 1.19.0, ADR-0008 addendum 23): the Jakarta Mail implementation.
    implementation("org.eclipse.angus:angus-mail:2.0.5")
    // Swiss QR bills for fees (OpenAPI 1.26.0, ADR-0008 addendum 30): MIT; brings Apache PDFBox 3 (Apache-2.0) and
    // Nayuki's QR code generator (MIT) for its own PDF output.
    implementation("net.codecrete.qrbill:qrbill-generator:3.4.0")

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
    // Print the assertion message of a failing test in CI, not only its location.
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Integration tests depend on the live PostgreSQL service, which is not a task
    // input: never restore their result from the build cache, always run them.
    outputs.cacheIf("PostgreSQL-backed tests must always execute") { false }
}


tasks.register<JavaExec>("bootstrapFirstClub") {
    group = "application"
    description = "Operator-only, one-time setup of the sole club and first administrator."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openclub.api.club.BootstrapCliKt")
}

tasks.register<JavaExec>("showAuditTrail") {
    group = "application"
    description = "Read-only operator command: print the audit trail (newest first), optionally filtered."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openclub.api.database.AuditTrailCliKt")
}

tasks.register<JavaExec>("exportMember") {
    group = "application"
    description = "Restricted operator-only JSON export of one member's stored data for a verified request."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openclub.api.members.MemberExportCliKt")
}

tasks.register<JavaExec>("generatePushKeys") {
    group = "application"
    description = "Operator command: print a new VAPID key pair for push notifications (OpenAPI 1.33.0)."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openclub.api.notifications.PushKeysCliKt")
}

tasks.register<JavaExec>("eraseMember") {
    group = "application"
    description = "Restricted operator-only permanent erasure of one archived member after a verified request."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openclub.api.members.MemberErasureCliKt")
}

tasks.register<JavaExec>("recoverSoleAdmin") {
    group = "application"
    description = "Restricted operator-only rebind of the sole administrator after login loss."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("org.openclub.api.club.AdminRecoveryCliKt")
}
