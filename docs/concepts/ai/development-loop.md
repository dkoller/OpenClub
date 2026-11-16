# Controlled development-loop checkpoints

The root `loopcounterN.txt` is a **resume marker** for chat-driven development, not a PR/batch counter, background worker or timer. Root [AGENTS.md](../../../AGENTS.md#controlled-development-loop-owner-direction-2026-11-15) is authoritative for task selection, review, merging, stopping and recovery.

- Start each execution by checking the latest `main`, open PRs, issues, documentation, required checks, and the sole root `loopcounterN.txt`.
- Prefer complete, independently reviewable **user workflow** increments over counting PRs. Review each diff with Ponytail; run applicable tests and **do not merge until all required GitHub checks pass**. Never bypass security, privacy, branch protections or payment restrictions.
- **Only when the current execution genuinely must end**, write a checkpoint in its **own PR**, including verified handoff details. If no marker exists, create `loopcounter0.txt` containing `0` and a newline; otherwise replace the sole `loopcounterN.txt` with `loopcounter(N+1).txt`, containing the new decimal value and a newline. Never leave two root markers. A completed batch, merge or normal handoff opportunity does **not** justify a checkpoint while more safe work can proceed.
- **A checkpoint is a resume hint, never a stop condition.** Continue the next development increment after a successful merge while execution can make progress. Never create a checkpoint after each source PR or stop because its PR merged.
- If the existing number is **100**, do not advance the counter or create a replacement marker; the cap is on checkpoint numbering, **not on implementation iterations**. Handle malformed or multiple markers as a blocker requiring review.
- Record merged PRs, outstanding checks and any blockers in the checkpoint PR description. A normal PR may change at most **5,000** added + deleted lines.
- Root `loopcounterN.txt` marker changes are excluded from ordinary build/Compose classification and the normal PR line budget. A counter-only PR skips expensive application checks; a PR that also modifies other paths still undergoes the relevant checks. The special `Loop counter checkpoint` workflow runs for marker changes and waits 20 seconds.
- A checkpoint never conceals failures or authorizes force-merging. If further work is blocked or the current chat execution ends, report the exact repository/checkpoint state; a following chat can resume from that state without inventing an unattended worker.

No paid infrastructure or continuous background agent is required. The controlled loop only executes **while the assistant is actively working in the conversation**; it cannot continue autonomously between turns.
