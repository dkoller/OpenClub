# Controlled development-loop checkpoints

This marker tracks the progress of **chat-driven** OpenClub development. It is not a background worker or timer and never authorizes skipping review.

- Start each execution by checking the latest `main`, open PRs, issues, documentation, required checks, and the sole root `loopcounterN.txt`.
- Work in focused, independently reviewable PRs. Review each diff with Ponytail; run relevant tests and **do not merge until all required GitHub checks pass**. Never bypass security, privacy, branch protections or payment restrictions.
- At a completed batch or natural handoff, advance the counter in its **own checkpoint PR**. If no marker exists, create `loopcounter0.txt` containing `0` and a newline; otherwise replace the sole `loopcounterN.txt` with `loopcounter(N+1).txt`, containing the new decimal value and a newline. Never leave two root markers.
- **A checkpoint is a progress marker, not a stop condition. Continue the next development increment immediately after its PR has passed CI and merged, when the current execution can still make progress.** Do not create a checkpoint for every source PR, and do not stop merely because a checkpoint has merged.
- If the existing number is **100**, stop advancing the counter or creating additional iterations. Handle malformed or multiple markers as a blocker requiring review.
- Record merged PRs, outstanding checks and any blockers in the checkpoint PR description. A normal PR may change at most **5,000** added + deleted lines.
- Root `loopcounterN.txt` marker changes are excluded from ordinary build/Compose classification and the normal PR line budget. A counter-only PR skips expensive application checks; a PR that also modifies other paths still undergoes the relevant checks. The special `Loop counter checkpoint` workflow runs for marker changes and waits 20 seconds.
- A checkpoint never conceals failures or authorizes force-merging. If further work is blocked or the current chat execution ends, report the exact repository/checkpoint state; a following chat can resume from that state without inventing an unattended worker.

No paid infrastructure or continuous background agent is required. The controlled loop only executes **while the assistant is actively working in the conversation**; it cannot continue autonomously between turns.
