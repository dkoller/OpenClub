package org.openclub.api.auth

import org.openclub.api.database.*
import org.openclub.api.members.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Existing authenticated components are composed only after fail-closed runtime setup.
 * Database operations use the process-owned bounded JDBC dispatcher; remote token
 * exchange uses a bounded I/O dispatcher, never a Ktor event loop.
 */
/** A single shared scheduling limit across all callbacks served by this login service. */
internal class BoundedOidcExchange(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4),
) {
    suspend fun <T> run(block: () -> T): T = withContext(dispatcher) { block() }
}

internal class PostgresBrowserLogin(
    private val database: DatabaseRuntime,
    config: OidcRuntimeConfig,
    provider: OidcProviderDiscovery.Provider,
) : BrowserLoginOperations {
    private val requests = OidcAuthorizationRequests(config.settings)
    private val exchange = OidcAuthorizationCodeExchange(config.settings, provider, config.clientSecret)
    private val settings = config.settings
    private val tokenExchange = BoundedOidcExchange()

    override suspend fun begin(): PendingOidcLogin =
        database.onDatabaseThread { db -> OidcLoginTransactions(db, requests).start() }

    override suspend fun finish(
        state: String, browserBinding: String?, code: String, previousSession: String?,
    ): IssuedSession? {
        val consumed = database.onDatabaseThread { db ->
            OidcLoginTransactions(db, requests).consume(state, browserBinding)
        } ?: return null
        val verified = tokenExchange.run {
            exchange.redeem(code, consumed)
        }
        return database.onDatabaseThread { db ->
            val person = VerifiedAccounts(db, settings).resolveOrCreate(verified)
            // The old session must survive any failure to insert its replacement.
            AuthSessions(db).replace(previousSession, person)
        }
    }
}
