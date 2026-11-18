package org.openclub.api.database

import org.jooq.exception.DataAccessException

/**
 * True when PostgreSQL rejected the statement because of the data it was given: a data exception
 * (SQLSTATE class 22, such as a value that is too long), an integrity constraint (class 23: CHECK,
 * UNIQUE, NOT NULL, foreign key) or one of this schema's business-rule triggers, which use a plain
 * `RAISE EXCEPTION` (P0001). Anything else, such as a lost connection, a timeout, a serialization
 * failure or exhausted resources, is a server fault and must not be reported to the client as a
 * rejected request.
 */
internal fun DataAccessException.rejectedTheData(): Boolean {
    val state = sqlState() ?: return false
    return state.startsWith("22") || state.startsWith("23") || state == "P0001"
}
