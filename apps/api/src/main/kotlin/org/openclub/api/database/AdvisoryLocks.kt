package org.openclub.api.database

/** Stable transaction locks shared by operations that must serialize across processes. */
internal object AdvisoryLocks {
    const val BOOTSTRAP = "SELECT pg_advisory_xact_lock(672018, 1)"
    const val PERMISSION_MUTATION = "SELECT pg_advisory_xact_lock(672018, 3)"
}
