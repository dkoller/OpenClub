package org.openclub.api.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.util.concurrent.Executors

/**
 * Process-owned PostgreSQL pool and bounded dispatcher. HTTP routes MUST use [onDatabaseThread] for all JDBC work, including
 * the database readiness probe; never block the Ktor event loop.
 */
internal class DatabaseRuntime private constructor(
    private val source: HikariDataSource,
) : AutoCloseable {
    private val worker: ExecutorCoroutineDispatcher = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "openclub-db-io").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val sql: DSLContext = DSL.using(source, SQLDialect.POSTGRES)

    suspend fun <T> onDatabaseThread(block: (DSLContext) -> T): T =
        withContext(worker) { block(sql) }

    override fun close() {
        worker.close()
        source.close()
    }

    companion object {
        /** `OPENCLUB_TIME_ZONE` (default Europe/Zurich): a valid region ID, also used for times in emails. */
        fun clubTimeZone(env: Map<String, String>): String {
            val zone = env["OPENCLUB_TIME_ZONE"]?.trim()?.ifEmpty { null } ?: "Europe/Zurich"
            require(Regex("^[A-Za-z]+(/[A-Za-z0-9_+-]+)+$").matches(zone)) { "OPENCLUB_TIME_ZONE must be a region such as Europe/Zurich" }
            java.time.ZoneId.of(zone)
            return zone
        }

        fun openIfConfigured(env: Map<String, String>): DatabaseRuntime? {
            val url = env["DB_URL"] ?: return null
            require(url.startsWith("jdbc:postgresql://")) { "PostgreSQL DB_URL is required" }
            val user = requireNotNull(env["DB_USER"]?.takeIf(String::isNotBlank)) { "DB_USER is required" }
            val password = requireNotNull(env["DB_PASSWORD"]?.takeIf(String::isNotBlank)) {
                "DB_PASSWORD is required"
            }
            val zone = clubTimeZone(env)
            val config = HikariConfig().apply {
                // Calendar days of absences (V32, OpenAPI 1.20.0) use the club's time zone; the ID is validated below.
                connectionInitSql = "SELECT set_config('openclub.time_zone', '$zone', false)"
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = 4
                minimumIdle = 0
                connectionTimeout = 5_000
                poolName = "openclub-api"
            }
            val source = HikariDataSource(config)
            try {
                Flyway.configure().dataSource(source).load().migrate()
                check(DSL.using(source, SQLDialect.POSTGRES).fetchValue("SELECT 1") == 1) {
                    "PostgreSQL connectivity check failed"
                }
                return DatabaseRuntime(source)
            } catch (failure: Exception) {
                source.close()
                throw failure
            }
        }
    }
}
