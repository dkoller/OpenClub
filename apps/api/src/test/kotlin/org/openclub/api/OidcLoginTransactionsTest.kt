package org.openclub.api

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.URI

class OidcLoginTransactionsTest {
    private fun requests() = OidcAuthorizationRequests(
        OidcSettings(
            URI("http://localhost:8180/realms/openclub-dev"),
            URI("http://localhost:8180/realms/openclub-dev/protocol/openid-connect/auth"),
            "openclub-bff",
            URI("http://localhost:5173/api/v1/auth/callback"),
        )
    )

    @Test
    fun transactionsAreBoundSingleUseAndFailClosed() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL via DB_URL")
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
            maximumPoolSize = 2
        }).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            val db = DSL.using(source, SQLDialect.POSTGRES)
            val store = OidcLoginTransactions(db, requests())

            val one = store.start()
            val two = store.start()
            try {
                assertEquals(2L, db.fetchValue("SELECT COUNT(*) FROM oidc_login_transactions WHERE expires_at > CURRENT_TIMESTAMP"))
                assertFalse(db.fetchExists(
                    DSL.table(DSL.name("oidc_login_transactions")),
                    DSL.field(DSL.name("state_hash"), String::class.java).eq(one.authorization.state)
                ))
                assertNull(store.consume(one.authorization.state, two.browserBinding))
                assertNull(store.consume(one.authorization.state, one.browserBinding))
                assertNull(store.consume(null, two.browserBinding))
                assertNull(store.consume(two.authorization.state, null))
                assertNull(store.consume("invalid", two.browserBinding))
                val consumed = store.consume(two.authorization.state, two.browserBinding)
                assertEquals(two.authorization.nonce, consumed?.nonce)
                assertEquals(two.authorization.codeVerifier, consumed?.codeVerifier)
                assertNull(store.consume(two.authorization.state, two.browserBinding))
            } finally {
                db.execute("DELETE FROM oidc_login_transactions")
            }
        }
    }

    @Test
    fun expiredAndWrongBrowserTransactionsCannotBeReused() {
        val url = System.getenv("DB_URL")
        assumeTrue(!url.isNullOrBlank(), "Requires PostgreSQL via DB_URL")
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = requireNotNull(System.getenv("DB_USER"))
            password = requireNotNull(System.getenv("DB_PASSWORD"))
            maximumPoolSize = 2
        }).use { source ->
            Flyway.configure().dataSource(source).load().migrate()
            val db = DSL.using(source, SQLDialect.POSTGRES)
            val store = OidcLoginTransactions(db, requests())
            val login = store.start()
            try {
                db.execute("UPDATE oidc_login_transactions SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'")
                assertNull(store.consume(login.authorization.state, login.browserBinding))
                assertNull(store.consume(login.authorization.state, login.browserBinding))
                val stale = store.start()
                db.execute("UPDATE oidc_login_transactions SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'")
                assertTrue(store.pruneExpired() >= 1)
                assertNull(store.consume(stale.authorization.state, stale.browserBinding))
            } finally {
                db.execute("DELETE FROM oidc_login_transactions")
            }
        }
    }
}
