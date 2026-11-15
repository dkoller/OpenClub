package org.openclub.api.database

import org.openclub.api.auth.*
import org.openclub.api.database.*
import org.openclub.api.members.*
import org.openclub.api.*

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class DatabaseIntegrationTest {
    @Test
    fun migrationsCreateClubsTable() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "DB_URL required for PostgreSQL integration test")
        val config = HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
        }
        HikariDataSource(config).use { ds ->
            Flyway.configure().dataSource(ds).load().migrate()
            val sql = DSL.using(ds, SQLDialect.POSTGRES)
            assertEquals(0, sql.fetchCount(DSL.table(DSL.name("clubs"))))
        }
    }
}
