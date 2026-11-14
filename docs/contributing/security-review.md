# Security-oriented implementation and review checklist

Use for authentication, authorization, identity linking, tenant boundaries, sessions, secrets, input parsing, cryptography and infrastructure that affects security. This complements the [repository agent rules](../../AGENTS.md) and [PR template](../../.github/pull_request_template.md); it cannot replace automated tests or a human review.

## Before implementation

1. Identify the entry points, external inputs, trusted state, trust boundaries and what must **never** be inferred from input. Draw a short threat model for nontrivial changes.
2. Look up the behavior of standard-library/framework functions on failure, missing values and ambiguous encodings. Avoid assuming parsing success implies a valid security value.
3. Prefer maintained libraries for protocols and cryptographic verification; pin dependencies, validate expected issuers/audiences/algorithms and document version/cost implications.
4. Decide observable behavior for denied access and failure, including database and identity-provider outages: default **fail closed**. Keep diagnostics free of secrets.
5. Choose unit, integration and runtime smoke tests that actually exercise the behavior being changed.

## Negative and boundary cases

For each changed trust boundary, test applicable cases, not merely the happy path:

- **Missing:** null, blank, absent cookie/header/claim, missing authority/host or required identifier.
- **Malformed:** invalid URI/JSON, bad encoding, partial/opaque URLs, illegal values or corrupt tokens.
- **Ambiguous:** Unicode/IDN hostnames, normalization, case/port differences, duplicate fields, conflicting claims, unexpected redirects.
- **Adversarial:** wrong issuer/audience/host, forged/replayed/expired state, mismatched nonce, cross-club identity, invalid CSRF and fixation attempts.
- **Operational:** process restart, fresh data volume, database/provider outage, incorrect secrets and production versus local-only mode.

**Example incident 1 — Keycloak import (PR #50):** Valid Compose YAML and valid realm JSON did not prove Keycloak actually imported `openclub-dev`. A service-start smoke test and discovery request caught the gap. When configuration affects a runtime service, test **that service's behavior**, not syntax alone.

**Example incident 2 — Java URI (PR #51):** `URI.host` may be `null` for hostless or unparsed Unicode authorities. Never compare nullable hosts as evidence that authorities match. For network security URLs, require hierarchical URLs and parsed nonblank hosts, reject or explicitly normalize IDNs, and verify scheme/host/port/user-info/path/query/fragment rules appropriate to the operation. Include regression tests for `https:/authorize` and distinct Unicode domains.

## Before requesting review

- Report **exactly what ran** and what did not. CI success means those tests passed, not that all security properties were proven.
- Check that tests assert the failure mode, not just that code compiles or that an error occurred somewhere.
- For infrastructure, distinguish `config --quiet`, container startup, service readiness and an end-to-end request.
- Ask for a second **independent security-focused review** on high-risk changes; provide the intended invariants and ask the reviewer to find counterexamples, not just approve style.
- Record findings as reproducible regression tests and update instructions where a generally reusable lesson exists.
- Human approval is mandatory. No automatic merges.

Do not turn this list into a requirement for irrelevant tests in low-risk PRs. Select cases based on the actual trust boundary and keep PRs under 1,000 changed lines.
