package org.openclub.api.members

import org.jooq.DSLContext
import java.util.UUID

/**
 * Service tests that are not about concurrency replace a member at its current row version (OpenAPI 1.8.0) and
 * only ask whether the replacement applied. The version checks themselves are tested in
 * `MemberDetailsUpdateTest` and `MemberReplaceAndReactivateHttpTest`.
 */
internal fun MemberDetailsUpdate.replace(db: DSLContext, actor: UUID, id: UUID, input: MemberDetailsUpdate.Input): Boolean {
    val version = (db.fetchValue("SELECT row_version FROM members WHERE id = ?", id) as Number?)?.toLong() ?: 1L
    return update(actor, id, input, version) is MemberDetailsUpdate.Replaced.Updated
}
