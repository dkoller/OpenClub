# Upgrading an installation and rolling back (synthetic M1; operator runbook)

**Pre-production.** This describes the supported path from an older schema version to the current one,
and what to do when an upgrade goes wrong. It is tested with synthetic data on disposable PostgreSQL
(`MigrationUpgradeTest`). It is **not** a witnessed production upgrade or recovery drill and does not
approve real member data (see [single-club operations](single-club.md) and
[R18/#27](https://github.com/dkoller/OpenClub/issues/27)).

## How an upgrade works

The API runs Flyway migrations when it starts (`DatabaseRuntime`). Upgrading is therefore: take a
backup, check the data, deploy the new API image, confirm it started. Migrations are **forward-only**:
Flyway Community has no undo. A migration runs in one PostgreSQL transaction, so a migration that
fails leaves the schema at its previous version with no partial change (tested).

Run **one** API instance during an upgrade. Flyway locks the schema history, but a second instance
running old code against a newly migrated schema is not supported.

## 1. Before: back up and note the current version

1. Take a private custom-format backup as in the
   [backup and restore rehearsal](backup-restore-rehearsal.md#1-capture-a-private-backup-from-a-disposable-source).
   Keep it outside the repository; it can contain every member record and session.
2. Record where the schema is now:

   ```sql
   SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 3;
   ```

## 2. Preflight: find data that blocks a migration

Data that an older schema accepted can make a later migration fail. Run these read-only queries; each
should return **no rows** before you upgrade. They are the same queries `MigrationUpgradeTest` runs.

```sql
-- V6: team names must be unique ignoring case and surrounding spaces.
SELECT lower(trim(name)) FROM teams GROUP BY 1 HAVING COUNT(*) > 1;

-- V8: member values may not exceed their raw maximum length (padding with spaces counted).
SELECT id FROM members WHERE length(first_name) > 200 OR length(last_name) > 200
   OR length(email) > 320 OR length(phone) > 40 OR length(gender) > 200;

-- V8/V9: team and club names may not exceed 200 raw characters.
SELECT id FROM teams WHERE length(name) > 200 UNION ALL SELECT id FROM clubs WHERE length(name) > 200;
```

If a query returns rows, fix those rows first (rename the duplicate team, trim the padded value) on
the live database before upgrading. Do not edit migration files.

## 3. Upgrade

Deploy the new API image (for the Compose reference: `docker compose up -d --build`). Watch the API
log for the Flyway messages. Then confirm:

- `/api/ready` answers 200 (the readiness check queries PostgreSQL).
- `flyway_schema_history` shows the newest version with `success = true`.
- Row counts for `clubs`, `members`, `teams` and the grant tables match the numbers you noted before.
- An administrator can sign in and the member list loads.

## 4. If the upgrade fails

| Symptom | What it means | Action |
|---|---|---|
| API exits at start with a Flyway/SQL error | The failing migration was rolled back; the schema is still at the previous version and no data changed (tested). | Read the error, fix the offending data (see preflight), start the new image again. |
| Flyway reports a checksum mismatch | A migration file differs from what was applied (tested). | Never edit an applied migration. Deploy the exact release that matches the database. |
| The upgrade succeeded but the release is bad | Migrations cannot be undone. | Roll back as below. |

## 5. Roll back

1. Stop the API (and the web container).
2. Restore the backup from step 1 into an **empty** database, using the restore commands in the
   [rehearsal](backup-restore-rehearsal.md#2-restore-to-a-different-empty-compose-project), then point the
   previous API image at it. Do not restore over a database you still need.
3. Deploy the **previous** release's image, which expects the older schema.
4. A restore can bring back sessions or login transactions that were revoked after the backup was taken.
   Revoke browser sessions deliberately after a restore (the synthetic rehearsal does this check) and
   tell users to sign in again.
5. Anything written after the backup is lost. Keep the failed upgrade's database for investigation until
   you are sure you do not need it.

## What is and is not evidenced

| Evidenced by `MigrationUpgradeTest` (synthetic, disposable PostgreSQL) | Not evidenced |
|---|---|
| Schema 5 with members, grants, teams and assignments upgrades to the latest schema with every row intact, checksums valid, nothing pending | Any real installation or real member data |
| Schema 1 with a club, account and administrator upgrades to the latest schema | Duration or locking on a large database |
| Application authorization (`ClubCapabilityAccess`) works on the upgraded data; constraints from later migrations apply | A witnessed upgrade by an accountable operator |
| Preflight queries find data that blocks V6/V8; the blocked migration leaves the schema at version 5 with all rows; fixing the data lets the upgrade finish | Encrypted or off-site backups, retention, monitoring (M2, #27/#180) |
| A changed checksum on an applied migration is refused | Zero-downtime upgrades (not supported) |
