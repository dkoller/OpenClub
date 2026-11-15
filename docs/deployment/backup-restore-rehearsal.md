# Disposable PostgreSQL backup and restore rehearsal

**Pre-production only.** Use synthetic OpenClub data. This procedure demonstrates the
commands for a local rehearsal. A passing GitHub synthetic-restore job verifies
an isolated development backup/restore, **not** a production restore drill,
nor approval to operate with real member records. See [single-club operations](single-club.md).

The example uses the repository's `compose.yaml` (`postgres` service, database
and user `openclub`) on a POSIX shell with Docker Compose available. Never run
these commands against an installation with data you need to retain without a
separately approved backup and recovery plan. A database dump can contain all
member and session data, including secrets; never commit, upload, or email it.

## Repeatable disposable smoke rehearsal

From the repository root, on a machine with Docker Compose and no conflicting
ports 5432/5173, run:

```sh
bash dev/verify-synthetic-restore.sh --synthetic-only
```

The script builds a **fresh temporary Compose project**, inserts fixed synthetic
club/member/team/assignment fixtures, creates a private custom-format PostgreSQL
backup outside the checkout, restores it into a **different fresh project** and
verifies relationships, club/team grants, a preserved archived member timestamp and lack of restored privileges for that archived record, and Flyway migration history against the source. The source also contains a fixed dummy browser session and unfinished OIDC login transaction. After restore verification, the script deletes both volatile authentication tables *before* starting the recovered API, then checks that no saved session or pending login survives. It then starts the real API against the restored database and verifies its health endpoint, proving Flyway startup and connectivity succeed on the restored schema. Before invoking `pg_restore`, the script validates the archive's readable
custom-format manifest and **refuses a destination containing any public base
tables**, even though its temporary destination is expected to be empty.
Both temporary project volumes and the private dump are removed on exit
(including failure); the script
must **never** be run against a project containing records worth keeping.
It does not access any existing Compose project by name. This is a disposable
development smoke check, not encrypted/off-site backup or production approval.

The [synthetic backup/restore GitHub Actions workflow](../../.github/workflows/synthetic-restore.yml)
runs the **full Docker rehearsal** for PRs changing the script, Compose
configuration, migrations or workflow, and can also be started manually.
A green check on that specific workflow is reproducible evidence of a synthetic
database restore, invalidation of restored browser-authentication state, **and recovered API startup** at that commit. The ordinary build job continues to syntax-check
the script, which is not by itself a restore test. The workflow deliberately does
not run on every unrelated PR to avoid unnecessary CI usage.

## 1. Capture a private backup from a disposable source

Start an isolated synthetic source project and confirm its identity. Populate it
with known **synthetic** club/member/team/grant fixtures and record the expected
counts; an empty database is not a meaningful restore test. Set a restrictive
file-creation mask before redirecting a dump. Keep the file **outside the repository**
and retain its private path for the restore command in the same shell:

```sh
set -e
umask 077
DUMP_PATH="$(mktemp /tmp/openclub-synthetic.XXXXXX)"
docker compose -p openclub_source up -d --build
docker compose -p openclub_source ps
docker compose -p openclub_source exec -T postgres \
  pg_dump -U openclub -d openclub --format=custom --no-owner \
  > "$DUMP_PATH"
test -s "$DUMP_PATH"
```

`pg_dump` must exit successfully; `set -e` stops this shell sequence on failure,
and the nonempty-file check alone is insufficient. Do not store the dump in the
checkout, even though common dump extensions are gitignored as defense in depth.
Use an appropriately secured location for the dump. Do not put passwords in
command arguments or captured logs. The example Compose stack uses development
credentials only, not production-safe defaults.

## 2. Restore to a *different* empty Compose project

The stock Compose file binds PostgreSQL to localhost port 5432. Stop the
**source project without removing its volumes**, so the isolated restore can
reuse that port. Keep the source stopped during the rehearsal.

```sh
docker compose -p openclub_source stop
docker compose -p openclub_restore_drill up -d postgres
docker compose -p openclub_restore_drill exec -T postgres \
  pg_isready -U openclub -d openclub
docker compose -p openclub_restore_drill exec -T postgres \
  pg_restore --exit-on-error --no-owner --no-privileges \
  -U openclub -d openclub < "$DUMP_PATH"
```

Only restore into a fresh disposable database, **never** a populated or live
club database. The distinct Compose project name gives it a separate named
PostgreSQL volume. A rerun into the same populated restore project will not
be a clean restore; create another disposable project instead.

## 3. Validate the restored database, then clean up

```sh
docker compose -p openclub_restore_drill exec -T postgres \
  psql -U openclub -d openclub -v ON_ERROR_STOP=1 \
  -c 'SELECT COUNT(*) AS club_count FROM clubs'
docker compose -p openclub_restore_drill exec -T postgres \
  psql -U openclub -d openclub -v ON_ERROR_STOP=1 \
  -c 'SELECT COUNT(*) AS member_count FROM members'
```

Compare counts with the synthetic source and confirm the *same* known test
records, Flyway migration history and expected team/grant relationships. A
successful command exit alone is not an application recovery drill. Restoring a snapshot may otherwise **resurrect sessions revoked after the backup** or pending authorization transactions. Before exposing a recovered production instance, a responsible operator must invalidate restored `auth_sessions` and `oidc_login_transactions` in a reviewed and audited recovery procedure, requiring everyone to authenticate again. That step must happen only against the intended recovered database, not an existing live installation. For a
production plan, separately validate app startup, session/key recovery,
permission boundaries, encryption, backup retention, monitoring, and the
responsible operator's sign-off.

**Only after independently verifying the restore project name and that it has
no valuable data**, discard the temporary restore volume:

```sh
docker compose -p openclub_restore_drill down --volumes
docker compose -p openclub_source start
```

Securely retain or dispose of the dump according to the applicable data policy.
For this disposable local rehearsal, remove the file with `rm -- "$DUMP_PATH"`
after verification and **only** after confirming that it is the intended temporary
synthetic backup. Do not delete a production backup using this command.

This does not establish a backup schedule, encrypted off-site retention,
production recovery time objective, or an audited real-data restore. Those remain
open under [R18/#27](https://github.com/dkoller/OpenClub/issues/27).
