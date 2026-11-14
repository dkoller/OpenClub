package org.openclub.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Existing authenticated components are composed only after fail-closed runtime setup.
 * Database operations use the process-owned bounded JDBC dispatcher; remote token
 * exchange uses a bounded I/O dispatcher, never a Ktor event loop.
 */
internal class PostgresBrowserLogin(
    private val database: DatabaseRuntime,
    config: OidcRuntimeConfig,
    provider: OidcProviderDiscovery.Provider,
) : BrowserLoginOperations {
    private val requests = OidcAuthorizationRequests(config.settings)
    private val exchange = OidcAuthorizationCodeExchange(config.settings, provider, config.clientSecret)
    private val settings = config.settings

    override suspend fun begin(): PendingOidcLogin =
        database.onDatabaseThread { db -> OidcLoginTransactions(db, requests).start() }

    override suspend fun finish(
        state: String, browserBinding: String?, code: String, previousSession: String?,
    ): IssuedSession? {
        val consumed = database.onDatabaseThread { db ->
            OidcLoginTransactions(db, requests).consume(state, browserBinding)
        } ?: return null
        val verified = withContext(Dispatchers.IO.limitedParallelism(4)) {
            exchange.redeem(code, consumed)
        }
        return database.onDatabaseThread { db ->
            val person = VerifiedPersonIdentities(db, settings).resolveOrCreate(verified)
            val sessions = AuthSessions(db)
            // Prevent two active sessions after re-login. A nonexistent/expired prior
            // cookie cannot authorize anything and does not block creating a new one.
            if (previousSession != null) sessions.revoke(previousSession)
            sessions.create(person)
        }
    }
}
