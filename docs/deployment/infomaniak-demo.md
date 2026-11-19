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

## 3. Deploy with one command

As root on the VPS:

```sh
apt-get update && apt-get -y install git
git clone https://github.com/dkoller/OpenClub.git /opt/openclub && cd /opt/openclub
bash dev/deployment/deploy-demo.sh --synthetic-only club.example.ch id.example.ch you@example.ch
```

[`deploy-demo.sh`](../../dev/deployment/deploy-demo.sh) does, in order:

1. installs the base packages, automatic security updates (`unattended-upgrades`) and Docker Engine with the Compose
   plugin from Docker's official repository;
2. enables the host firewall (`ufw`) for SSH, 80 and 443 only;
3. checks that both names resolve to this server's public IPv4 address, so Caddy does not burn ACME attempts;
4. generates the secrets once with [`generate-demo-env.sh`](../../dev/deployment/generate-demo-env.sh) into
   `/opt/openclub-demo-env` (owner-only `.env`, nothing printed; an existing directory is reused, never replaced);
5. starts Rauthy, which creates its administrator and the `openclub` client with its exact callback on the first
   start, and stores the client secret Rauthy generated in the env file
   ([`fetch-demo-client-secret.sh`](../../dev/deployment/fetch-demo-client-secret.sh), Rauthy's bootstrap accepts no
   preset secret);
6. builds and starts all five services (`unless-stopped`; only Caddy publishes 80/443), retrying once while the
   certificates are issued;
7. runs the read-only HTTPS and OIDC probe ([`verify-synthetic-https-demo.sh`](../../dev/verify-synthetic-https-demo.sh)).

Rerunning the script is safe and is also how you update (see below). For any other Compose command use the wrapper,
which picks the right files and env: `bash dev/deployment/demo-compose.sh /opt/openclub-demo-env ps` (or `logs`, `down`).

## 4. Email for Rauthy (Infomaniak mailbox)

Rauthy sends password-reset and notification emails. Create a mailbox in the Infomaniak Manager (for example
`noreply@example.ch`), then:

```sh
read -rs MAILBOX_PASSWORD && printf '%s' "$MAILBOX_PASSWORD" |
  bash dev/deployment/configure-demo-email.sh --synthetic-only /opt/openclub-demo-env noreply@example.ch
unset MAILBOX_PASSWORD
bash dev/deployment/deploy-demo.sh --synthetic-only club.example.ch id.example.ch you@example.ch
```

This stores Infomaniak's documented submission settings (`mail.infomaniak.com`, port 465, implicit TLS, username = full
address; the sender is the same address, as Infomaniak requires) and the password, which is read from standard input and
never printed. From then on the wrapper adds [`compose.rauthy-email.demo.yaml`](../../compose.rauthy-email.demo.yaml).
**Rauthy exits at startup if it cannot reach or log in to the SMTP server**, so a wrong password stops sign-in for the
whole demo: check `demo-compose.sh … logs rauthy`, rerun `configure-demo-email.sh` with the right password and deploy
again. Without this step everything works except outgoing email; the administrator then resets passwords in the
Rauthy admin UI.

## 5. First club and administrator

1. Sign in to Rauthy at `https://id.example.ch/auth/v1/admin` with `RAUTHY_ADMIN_EMAIL` and `RAUTHY_ADMIN_PASSWORD` from
   `/opt/openclub-demo-env/.env`, and create the synthetic user who will be the club administrator (or use the Rauthy
   administrator).
2. Sign in to `https://club.example.ch` as that user. The page says the account is not yet a club member. Open
   `https://club.example.ch/api/v1/auth/me` and copy `accountId`.
3. Create the club with the restricted operator bootstrap ([single-club operations](single-club.md#operator-only-first-club-bootstrap-pre-production)),
   run inside the API container with fictional administrator details:

   ```sh
   bash dev/deployment/bootstrap-demo-club.sh --synthetic-only /opt/openclub-demo-env <accountId> "Demo Club"
   ```

4. Reload the club page: the administrator sees the administration in DE/EN/FR/IT.

## 6. Fictional demo data (optional)

```sh
bash dev/deployment/seed-demo-data.sh --synthetic-only /opt/openclub-demo-env
```

Adds four teams (Juniors U12, Juniors U17, Women 1, Men 1) and 16 invented members across age groups (minors with
and without contacts, adult players, adult non-players in two teams, a member in no team), all with `example.invalid` addresses and
`+41 000` placeholder numbers, ages relative to today. It runs in one transaction and refuses any database that is not
a freshly bootstrapped club, so it cannot run twice or touch an installation in use. Seed rows are not in the audit
trail. Grants are left to the walkthrough: give permissions in the UI, then invite members (create their Rauthy
accounts in the Rauthy admin UI first).

## 7. Verify and record evidence

The deploy script already ran the HTTPS probe. Measure resources with:

```sh
COMPOSE_PROJECT_NAME=openclub-demo bash dev/measure-synthetic-compose.sh --synthetic-only
```

Then work through the [M1 acceptance walkthrough](m1-acceptance-walkthrough.md) (members, teams, scoped permissions,
invitations, guardians, the four languages, restart and reboot, resources and cost) and copy its results table into
#28; the [M1 evidence checklist](m1-resource-evidence.md) explains what counts as evidence.

## Updating

```sh
cd /opt/openclub && git pull
bash dev/deployment/deploy-demo.sh --synthetic-only club.example.ch id.example.ch you@example.ch
```

Database migrations run when the API starts ([upgrade and rollback](upgrade-and-rollback.md)). Without backups a
failed migration cannot be rolled back to the old data: for the synthetic demo the fallback is to start over with
`bash dev/deployment/demo-compose.sh /opt/openclub-demo-env down -v`, which **destroys all demo data and the Rauthy
accounts**; keep `/opt/openclub-demo-env` or delete it too for fresh secrets.

## Troubleshooting

- **DNS check fails:** the A records are missing or not yet propagated; `getent ahostsv4 club.example.ch` must show
  the VPS address. Wait, or fix the records.
- **Start does not become healthy:** `bash dev/deployment/demo-compose.sh /opt/openclub-demo-env ps` and `… logs demo-https`
  for certificate errors (ports 80/443 closed in the Infomaniak firewall?), `… logs api` for OIDC
  errors (the identity certificate not issued yet: rerun the deploy script after a minute).
- **Rauthy keeps restarting after email setup:** wrong mailbox password or address; see section 4.
- **Sign-in to the club fails with an invalid client:** the client secret in `.env` does not match Rauthy's (for example
  after deleting only the Rauthy volume). Set `OPENCLUB_OIDC_CLIENT_SECRET=pending-` in `.env` and rerun the deploy
  script, which fetches it again.

## What this kit does not do

No backups (owner decision for the synthetic demo), no monitoring or alerting, no real-data approval. M2
(real member data) needs the backup, restore drill, recovery and privacy gates in [#12](https://github.com/dkoller/OpenClub/issues/12),
[#26](https://github.com/dkoller/OpenClub/issues/26) and [#180](https://github.com/dkoller/OpenClub/issues/180).
