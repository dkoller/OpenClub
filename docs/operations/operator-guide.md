# OpenClub operator guide

For the person who installs and runs the server of one club, not for club members (they read the
[guides](../guides/README.md)). This page is the map of everything an operator does; each step links to its detailed
runbook. **Pre-production:** use synthetic data only until the M2 gates in the [roadmap](../roadmap.md) are met.

## What runs

| Part | What it does | Data |
| --- | --- | --- |
| `web` (nginx) | Serves the React app, the service worker `/sw.js` and proxies `/api/` | none |
| `api` (Kotlin/Ktor) | All business logic; migrates the database at start; background jobs every minute (email, push) and hourly (retention) | — |
| `postgres` (16) | The only data store; needs the contrib extension `btree_gist` (since V40) | everything |
| Identity provider | Keycloak or Rauthy (one per installation); members sign in there | sign-in accounts |
| `demo-https` (Caddy) | TLS and the public entry in the one-host demo | none |

One installation serves exactly one club. The demo kit for one Infomaniak VPS is in
[infomaniak-demo.md](../deployment/infomaniak-demo.md); the general reference is
[single-club.md](../deployment/single-club.md).

## Configuration

Host names, the club name and the optional email and push settings live in the YAML settings file
(`/etc/openclub/demo.yaml`, template [`demo.example.yaml`](../../dev/deployment/demo.example.yaml)); scripts read it
instead of taking host names on the command line. Secrets live only in the private env file the scripts create
(`envDirectory`, default `/opt/openclub-demo-env/.env`, mode 600). Never commit either with real values.

The API reads these environment variables (all set by the compose files from the env file):

| Variable | Purpose |
| --- | --- |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL connection |
| `OPENCLUB_AUTH_MODE` | `production` (HTTPS) or `development` (loopback); default `disabled` |
| `OPENCLUB_PUBLIC_ORIGIN` | The browser origin, for example `https://club.example.ch` |
| `OPENCLUB_OIDC_ISSUER`, `OPENCLUB_OIDC_AUTHORIZATION_ENDPOINT`, `OPENCLUB_OIDC_CLIENT_ID`, `OPENCLUB_OIDC_CLIENT_SECRET`, `OPENCLUB_OIDC_REDIRECT_URI` | Sign-in through the identity provider ([auth runtime](../security/auth-runtime.md)) |
| `OPENCLUB_SMTP_HOST`, `_PORT`, `_TLS`, `_USERNAME`, `_PASSWORD`, `_FROM` | Optional email notifications ([single-club, email](../deployment/single-club.md#email-notifications-optional)) |
| `OPENCLUB_TIME_ZONE` | Times in emails and absence days (default `Europe/Zurich`) |
| `OPENCLUB_PUSH_PUBLIC_KEY`, `OPENCLUB_PUSH_PRIVATE_KEY`, `OPENCLUB_PUSH_SUBJECT`, `OPENCLUB_PUSH_HOSTS` | Optional push notifications (below) |
| `PORT` | API port inside the container (default 8080) |

A partial or invalid email, push or sign-in configuration stops the API at startup instead of running half-configured.

## Installing the demo

Follow [infomaniak-demo.md](../deployment/infomaniak-demo.md): order the VPS, set DNS, write the settings file, run
`deploy-demo.sh`, optionally set up Rauthy email, bootstrap the first club and administrator, optionally load
fictional data, and record the evidence for the [M1 acceptance walkthrough](../deployment/m1-acceptance-walkthrough.md).

## Email and push

- **Email** goes through the club's own SMTP server; without `OPENCLUB_SMTP_HOST` it is off. Messages wait in an
  outbox, are retried three times, and are deleted 7 days after sending (2 days if never sent).
- **Push** (optional, works with or without email): add `push.hosts` to the settings file, run
  `bash dev/deployment/configure-demo-push.sh --synthetic-only`, then restart the API. The script creates the VAPID key
  pair with the app's own `PushKeysCli` and prints no key; a rerun keeps the keys (new keys make every device subscribe
  again). Outside the demo kit, `./gradlew generatePushKeys` in `apps/api` prints a pair. Allow outbound HTTPS to the
  listed push hosts. The server only sends empty pushes; devices fetch the text from the app, so the push services see
  no personal data. A `.suffix` in `push.hosts` needs at least three labels (`.push.apple.com`, not `.apple.com`); the
  API calls push services directly (no HTTP proxy) and only when the name resolves to public addresses. A device that
  fails 10 times in a row is removed and must turn push on again. Members turn push on per device under *Family* → *Email notifications*.

## Operator commands

All run against the same database as the API (`DB_URL`, `DB_USER`, `DB_PASSWORD`), never through the web. In the demo
they run inside the API container (`java -cp '/app/lib/*' <class>`), elsewhere through Gradle in `apps/api`.

| Task | Gradle task / class | Notes |
| --- | --- | --- |
| Create the club and first administrator | `bootstrapFirstClub` / `club.BootstrapCliKt` | Once; demo: `bootstrap-demo-club.sh` |
| Recover a lost sole administrator | `recoverSoleAdmin` | Only with the [recovery procedure](../security/admin-recovery-procedure.md) |
| Read the audit trail | `showAuditTrail` / `database.AuditTrailCliKt` | Identifiers only, read-only |
| Export one member's data | `exportMember` / `members.MemberExportCliKt` | For a verified access request |
| Erase an archived member | `eraseMember` / `members.MemberErasureCliKt` | Permanent; re-run after any restore |
| Create push keys | `generatePushKeys` / `notifications.PushKeysCliKt` | Prints two env lines |

Details and the confirmation variables of each command are in [single-club.md](../deployment/single-club.md).

## Automatic housekeeping

The API deletes on its own, in small batches: audit events after 12 months; sign-in accounts without a member after
2 years without sign-in; archived members 24 months after archiving; attendance answers, volunteer sign-ups, rides
and absences 12 months after the event or day; events (with their squads, attendance ticks and coach-message log)
24 months after they end; fee periods 24 months after the due date; announcements 6 months after show-until; join
requests after 60 days. See [retention cleanup](../security/retention-cleanup.md). Nothing here replaces a backup.

## Backups, upgrades and rollback

- **Backups:** the synthetic demo has none by owner decision; a real-data installation needs encrypted off-host
  backups before M2. Rehearse restores with [backup-restore-rehearsal.md](../deployment/backup-restore-rehearsal.md).
- **Upgrades:** take and verify a backup, deploy the new images, check that the API started (it migrates forward
  only). Since V40 the database needs `btree_gist`; it is a trusted extension the database owner can create, so only a
  managed PostgreSQL where the app role does not own the database needs an administrator to run
  `CREATE EXTENSION btree_gist` once. See [upgrade-and-rollback.md](../deployment/upgrade-and-rollback.md).
- **Rollback** means restoring the pre-upgrade backup; migrations are not undone.

## Logs and privacy

- The API logs to standard error (`demo-compose.sh logs api`). Every request that ends in *unavailable* (503) and
  every failed background job (email, push, cleanup) writes one line `org.openclub.api.failures`: the method and the
  route with IDs shown as `{id}` and secrets as `{secret}`, the exception classes, the SQL state and the OpenClub stack
  frames. Exception messages are never logged, because database messages can quote member data.
- Beyond that it logs no request paths, addresses, message texts or push endpoints.
- Calendar links carry a secret in the path: keep access logs off for `/api/v1/calendar/` on any extra proxy.
- The public join form trusts the last `X-Forwarded-For` entry: any extra proxy must replace it, not append.
- Never paste env files, member IDs or database dumps into issues, chats or CI output.

## Troubleshooting

| Symptom | Look at |
| --- | --- |
| *Unavailable* on a page | `demo-compose.sh logs api | grep failures`: the route, the exception class and where in the code |
| API restarts at startup | `demo-compose.sh logs api`: an invalid email, push or OIDC setting is named in the error |
| Sign-in fails | The identity provider's logs and the client secret (`fetch-demo-client-secret.sh`) |
| No emails | `OPENCLUB_SMTP_*`, the outbox failures counted in the API log |
| No push on a phone | `push.hosts` includes the vendor, outbound HTTPS allowed, the member turned push on for that device (iPhone: from the home-screen app) |
| Upgrade stops at migration V40 | `btree_gist` missing: create the extension as database owner |

More in the [demo troubleshooting](../deployment/infomaniak-demo.md#troubleshooting).
