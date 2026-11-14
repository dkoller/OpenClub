# R05: Authentication admission budget

Login and callback are unauthenticated entry points. A process-local, fixed-memory
one-minute budget admits up to 60 login requests and 120 callback requests per
API instance. Exhausted budgets return HTTP 429 with `Retry-After: 60` and
`Cache-Control: no-store` before DB/token work. Callback rejection still
clears the pre-authentication binding cookie.

This is intentionally a small, static safety bound. It does not trust
`X-Forwarded-For`, store unbounded IP keys, or install a rate-limiter framework.
It is **not** per-client fairness, shared across replicas, or a substitute for
reverse-proxy/edge DDoS controls. An attacker can exhaust the shared budget and
temporarily deny legitimate login. Review/adjust deployment edge controls before
public exposure. Already-issued sessions and `/auth/me` are unaffected.

Run `./gradlew test --tests '*AuthAdmissionLimiterTest'` in `apps/api`.
