package org.openclub.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL

object DatabaseBootstrap {
    fun migrateIfConfigured() {
        val url = System.getenv("DB_URL") ?: return
        val user = requireNotNull(System.getenv("DB_USER")) { "DB_USER is required when DB_URL is set" }
        val password = requireNotNull(System.getenv("DB_PASSWORD")) { "DB_PASSWORD is required when DB_URL is set" }

        val config = HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 4
        }
        HikariDataSource(config).use { dataSource ->
            Flyway.configure().dataSource(dataSource).load().migrate()
            check(DSL.using(dataSource, SQLDialect.POSTGRES).fetchValue("select 1") == 1) {
                "PostgreSQL connectivity check failed"
            }
        }
        // Future API features will own a long-lived, properly scoped pool.
    }
}
