## Purpose / user-visible outcome

<!-- What changes, and why? Link the issue. -->

## Scope and size

- [ ] This PR changes **fewer than 1,000 added + deleted lines** (including tests, docs, config and lockfiles).
- [ ] The change is independently reviewable; no required tests were omitted to reduce size.
- [ ] Any binary changes are explained.

## Documentation impact (required)

- [ ] I reviewed the documentation affected by this PR and updated it **in this PR** (README, runbooks, setup commands, examples, OpenAPI contract, ADRs, roadmap, and/or relevant `AGENTS.md`).
- [ ] If no documentation changes were needed, I explain why below; I have not knowingly left stale instructions behind.

<!-- List updated docs and any commands/links checked, OR write "Documentation: no changes needed" with a reason. Historical ADRs/notes should be annotated, not silently rewritten. -->

## Verification

<!-- Exact build, test, formatting or manual checks run; state what you could not run. -->

## Security and behavior review (apply where relevant)

- [ ] I identified changed trust boundaries and tested relevant missing, malformed, ambiguous and adversarial inputs; or explain why this is not applicable.
- [ ] For service/configuration changes, the affected service was started and its behavior verified; or explain why runtime validation was not possible.
- [ ] For authentication, authorization, sessions or other high-risk changes, I requested independent security-focused review before merge.

<!-- Describe negative tests, runtime evidence, review findings and residual risk. Reference docs/contributing/security-review.md. -->

## Review notes

<!-- Authorization and club isolation impact? Dependencies? Contract/migrations? Risks or follow-ups? -->

- [ ] I reviewed the diff for unnecessary abstractions, secrets and unrelated changes.
- [ ] No direct or autonomous merge; human approval required.
