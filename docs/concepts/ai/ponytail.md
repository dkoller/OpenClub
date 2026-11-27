# Ponytail development workflow

Ponytail is an optional upstream coding-agent skill/plugin that encourages **the smallest complete and correct implementation**. Its preferences do not override OpenClub security rules, tests, API contracts, ADRs or the PR size limit.

- Official source: https://github.com/DietrichGebert/ponytail
- Upstream installation guide: https://github.com/DietrichGebert/ponytail/blob/main/INSTALL.md

## Install manually after reviewing the upstream source

For Claude Code, enter these as **two separate commands**:

```text
/plugin marketplace add DietrichGebert/ponytail
/plugin install ponytail@ponytail
```

For Codex CLI (subject to the version supporting plugins):

```sh
codex plugin marketplace add DietrichGebert/ponytail
codex plugin add ponytail@ponytail
```

Review and explicitly trust any lifecycle hooks before activating them. Installing from a moving upstream branch can change behavior; review or pin a known version if reproducibility matters. Node.js must be available for hook execution.

## How to use it in OpenClub

Ponytail is not required. The mandatory diff review in `AGENTS.md` checks each change against its engineering-approach rules; Ponytail's review skill is one way to perform it. State in the PR which review was used.

- Use the regular/full Ponytail approach when implementing or refactoring.
- Run Ponytail's review skill on focused code diffs, where available.
- Use broader audit/debt workflows occasionally, not as a substitute for PR review.
- Prefer deleting needless code over introducing speculative abstractions.
- Never delete tests, security controls, or required documentation just to reduce the line count.

We **do not vendor the upstream skill** or automatically install third-party hooks in CI. The root and nested `AGENTS.md` files give stable project rules to agents even without the plugin.
