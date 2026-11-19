# One-host synthetic M1 demo on Infomaniak (operator runbook)

**Synthetic data only.** This runbook deploys the hosted M1 demo ([#27](https://github.com/dkoller/OpenClub/issues/27),
[#28](https://github.com/dkoller/OpenClub/issues/28)) on one Infomaniak VPS: the club app, PostgreSQL, the Rauthy
identity provider and the Caddy TLS ingress. The owner chose Infomaniak and decided that the demo has **no backups for
now** ([ADR-0008 addendum 6](../decisions/0008-owner-decisions-2026-11-18.md)). Losing the VPS therefore loses the demo
data, which is acceptable only because every record is fictional. Never enter real member data.

## 1. Choose and order the server

Infomaniak **VPS Lite** (hosted in Switzerland, unmanaged, root access, Linux of your choice). Prices on
[infomaniak.com/en/hosting/vps-lite](https://www.infomaniak.com/en/hosting/vps-lite), checked 2026-11-19, monthly
billing, 10% less when paid yearly:

| Plan | vCPU | RAM | Disk | CHF/month | Fit |
| --- | --- | --- | --- | --- | --- |
| VPS Lite 2 GB | 2 | 2 GB | 40 GB | 5.40 | Too small: the JVM API, PostgreSQL and Rauthy together need more |
| **VPS Lite 4 GB** | **2** | **4 GB** | **60 GB** | **7.20** | **Recommended** for the synthetic demo |
| VPS Lite 4 GB, 4 vCPU | 4 | 4 GB | 80 GB | 9.00 | Only if the 2 vCPU plan proves slow under the browser workflows |

CHF 7.20 is about €7.70, inside the approved ≤ €10/month ([ADR-0007](../decisions/0007-owner-decisions-2026-11-17.md)),
plus the domain (yearly). Record the actual invoice amount in #27; this table is not cost evidence. VPS Lite has no
availability SLA and no snapshots (VPS Cloud has both, from about CHF 38/month); for a synthetic demo without backups
that is acceptable. Upgrading Lite to Cloud is a manual, irreversible migration.

Pick **Ubuntu 24.04 LTS** and an SSH key at ordering. In the Infomaniak Manager firewall for the VPS, allow inbound
TCP 22 (SSH, ideally only from your address), 80 and 443; nothing else.

## 2. DNS

Create two A records (and AAAA if the VPS has IPv6) pointing to the VPS, for example:

- `club.example.ch` for the app (`OPENCLUB_DEMO_HOST`)
- `id.example.ch` for Rauthy (`OPENCLUB_ID_HOST`)

Both must resolve before the first start: Caddy requests public ACME certificates for both names.

## 3. Prepare the host

As root on the VPS:

```sh
apt-get update && apt-get -y upgrade
apt-get -y install ca-certificates curl git jq openssl unattended-upgrades
# Docker Engine and the Compose plugin from Docker's official repository:
# https://docs.docker.com/engine/install/ubuntu/
git clone https://github.com/dkoller/OpenClub.git /opt/openclub
```

Keep automatic security updates on (`unattended-upgrades`). The repository holds no secrets.

## 4. Generate the secrets (once)

```sh
cd /opt/openclub
bash dev/deployment/generate-demo-env.sh --synthetic-only club.example.ch id.example.ch you@example.ch /opt/openclub-demo-env
```

The script writes `/opt/openclub-demo-env/.env` (owner-only) with a fresh database password, Rauthy encryption key,
cluster secrets and Rauthy administrator password, and `rauthy-bootstrap/clients.json` with the `openclub` client and
its exact callback `https://club.example.ch/api/v1/auth/callback`. Rauthy generates the client secret itself on its
first start (its bootstrap accepts no preset secret); step 5 copies it into the env file. It prints no secret and refuses
to overwrite an existing directory, so it cannot replace the keys of a running installation. Keep this directory out
of the repository and of any issue, chat or CI log.

## 5. Start

```sh
cd /opt/openclub
demo() { docker compose --env-file /opt/openclub-demo-env/.env -p openclub-demo \
  -f compose.yaml -f compose.https.demo.yaml -f compose.rauthy.demo.yaml "$@"; }
demo config --quiet
demo up -d rauthy                       # first start: Rauthy creates its administrator and the openclub client
bash dev/deployment/fetch-demo-client-secret.sh --synthetic-only /opt/openclub-demo-env
demo up -d --build --wait               # now the API starts with the real client secret
```

All five services restart automatically after a reboot (`unless-stopped`). Only Caddy publishes ports (80, 443).
Rauthy reads the `openclub` client and its administrator from the bootstrap on its **first** start only. The API checks
the issuer over HTTPS at startup; `compose.rauthy.demo.yaml` maps the identity hostname to the host, so this does not
rely on hairpin NAT. If the API fails at first start because the certificate for the identity name was not issued
yet, run `demo up -d` again after a minute.

## 6. First club and administrator

1. Sign in to Rauthy at `https://id.example.ch/auth/v1/admin` with `RAUTHY_ADMIN_EMAIL` and `RAUTHY_ADMIN_PASSWORD` from
   the env file, and create the synthetic user who will be the club administrator (or use the Rauthy administrator).
2. Sign in to `https://club.example.ch` as that user. The page says the account is not yet a club member.
3. Read the account ID with the browser at `https://club.example.ch/api/v1/auth/me`, then run the operator-only
   bootstrap from [single-club operations](single-club.md#operator-only-first-club-bootstrap-pre-production) inside the
   API container, with synthetic names, for example:

   ```sh
   demo exec -e OPENCLUB_BOOTSTRAP_CONFIRM=create-first-club -e OPENCLUB_BOOTSTRAP_ACCOUNT_ID=<account id> \
     -e OPENCLUB_BOOTSTRAP_CLUB_NAME='Demo Club' -e OPENCLUB_BOOTSTRAP_CLUB_SLUG=demo-club \
     -e OPENCLUB_BOOTSTRAP_FIRST_NAME=Demo -e OPENCLUB_BOOTSTRAP_LAST_NAME=Administrator \
     -e OPENCLUB_BOOTSTRAP_BIRTH_DATE=1990-01-01 -e OPENCLUB_BOOTSTRAP_EMAIL=admin@example.invalid \
     -e OPENCLUB_BOOTSTRAP_PHONE=+41000000000 \
     api java -cp '/app/lib/*' org.openclub.api.club.BootstrapCliKt
   ```

   The API image ships the application libraries in `/app/lib` and already has the database settings.
4. Reload the club page: the administrator sees the administration in DE/EN/FR/IT. Further members join through
   invitations: create each synthetic member's Rauthy account in the Rauthy admin UI, then send the invitation link.

Rauthy has no outgoing email configured in this kit, so password-reset emails and email OTP do not work; the
administrator resets passwords in the Rauthy admin UI. Configure SMTP (`SMTP_URL`, `SMTP_USERNAME`, `SMTP_PASSWORD`,
`SMTP_FROM`) in a later change if needed.

## 7. Verify and record evidence

```sh
OPENCLUB_DEMO_ORIGIN=https://club.example.ch OPENCLUB_OIDC_ISSUER=https://id.example.ch/auth/v1/ \
  bash dev/verify-synthetic-https-demo.sh --synthetic-only
COMPOSE_PROJECT_NAME=openclub-demo bash dev/measure-synthetic-compose.sh --synthetic-only
```

Then walk through the synthetic browser workflows (members, teams, permissions, invitations, the four languages),
reboot the VPS once and confirm the club and its members are still there, and record the results, the measured
memory and disk, and the real monthly cost in #27/#28 using the [M1 evidence checklist](m1-resource-evidence.md).

## Updating

```sh
cd /opt/openclub && git pull
demo up -d --build --wait
```

Database migrations run when the API starts ([upgrade and rollback](upgrade-and-rollback.md)). Without backups a
failed migration cannot be rolled back to the old data: for the synthetic demo the fallback is to delete the volumes
and start over (`demo down -v`, which **destroys all demo data and the Rauthy accounts**).

## What this kit does not do

No backups (owner decision for the synthetic demo), no monitoring or alerting, no email, no real-data approval. M2
(real member data) needs the backup, restore drill, recovery and privacy gates in [#12](https://github.com/dkoller/OpenClub/issues/12),
[#26](https://github.com/dkoller/OpenClub/issues/26) and [#180](https://github.com/dkoller/OpenClub/issues/180).
