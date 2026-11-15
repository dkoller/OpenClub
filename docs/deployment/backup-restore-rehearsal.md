# Disposable PostgreSQL backup and restore rehearsal

**Pre-production only.** Use synthetic OpenClub data. This procedure demonstrates the
commands for a local rehearsal; it is **not evidence that a restore has been run**,
nor approval to operate with real member records. See [single-club operations](single-club.md).

The example uses the repository's `compose.yaml` (`postgres` service, database
and user `openclub`) on a POSIX shell with Docker Compose available. Never run
these commands against an installation with data you need to retain without a
separately approved backup and recovery plan. A database dump can contain all
member and session data, including secrets; never commit, upload, or email it.

## 1. Capture a private backup from a disposable source

Start an isolated synthetic source project and confirm its identity. Populate it
with known **synthetic** club/member/team/grant fixtures and record the expected
counts; an empty database is not a meaningful restore test. Set a restrictive
file-creation mask before redirecting a dump:

```sh
umask 077
docker compose -p openclub_source up -d --build
docker compose -p openclub_source ps
docker compose -p openclub_source exec -T postgres \
  pg_dump -U openclub -d openclub --format=custom --no-owner \
  > openclub-synthetic.dump
test -s openclub-synthetic.dump
```

`pg_dump` must exit successfully; the nonempty-file check alone is insufficient.
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
  -U openclub -d openclub < openclub-synthetic.dump
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
successful command exit alone is not an application recovery drill. For a
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
This does not establish a backup schedule, encrypted off-site retention,
production recovery time objective, or an audited real-data restore. Those remain
open under [R18/#27](https://github.com/dkoller/OpenClub/issues/27).
