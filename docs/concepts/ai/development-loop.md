# Development run stop counter

This marker tracks **completed chat-driven development runs that stop while additional work remains**. It is not a background worker, timer, or authorization to bypass review.

- After a run has made the feasible progress available in the current session, and it must stop with pending work (for example while waiting for CI), open a small PR that advances the root counter.
- If there is no existing root marker, create `loopcounter0.txt` containing `0` and a newline.
- Otherwise replace the sole `loopcounterN.txt` with `loopcounter(N+1).txt`, containing the corresponding decimal value and a newline. Never leave two counters in the branch.
- If the existing number is **100**, stop the loop; do not advance the marker or create another iteration. Handle an invalid or multiple-marker state as a blocker requiring review.
- Record the latest useful work and outstanding checks in the counter PR description. Keep the counter PR below the existing 999-line limit.
- Root `loopcounterN.txt` marker changes are excluded from ordinary build/Compose classification and the normal PR line budget. A counter-only PR skips expensive application checks; a PR that also modifies other paths still undergoes the applicable ordinary checks. The special `Loop counter checkpoint` workflow runs for counter changes and waits 20 seconds.
- Continue to require relevant green checks, security/Ponytail review and applicable repository approvals for **every** PR. A counter PR must never conceal failures, force-merge other changes, or imply that work runs unattended between chat turns.

This convention records runs; it does not launch an autonomous loop. No paid services or always-on infrastructure are required.
