package org.openclub.api.members

import org.jooq.DSLContext

/**
 * Anonymous display-only directory. PostgreSQL formats and JSON-escapes each
 * name before anything leaves the database: no IDs, DOBs or minor surnames.
 */
internal class PublicMemberNames(private val database: DSLContext) {
    fun listJson(): String = database.fetchOne(
        """
        SELECT COALESCE(
            json_agg(json_build_object('displayName', public.display_name)
                ORDER BY public.display_name)::text, '[]')
        FROM (
            SELECT CASE WHEN m.date_of_birth >
                (CURRENT_DATE - make_interval(years => c.adult_age_years))::date
                THEN m.first_name || ' ' || left(m.last_name, 1) || '.'
                ELSE m.first_name || ' ' || m.last_name END AS display_name
            FROM members m CROSS JOIN clubs c
            WHERE m.archived_at IS NULL
        ) public
        """.trimIndent(),
    )?.get(0, String::class.java) ?: "[]"
}
