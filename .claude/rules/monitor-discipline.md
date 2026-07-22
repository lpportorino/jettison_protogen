<!-- LOAD-TEST: monitor-discipline -->

# Monitor discipline — arm the observers; idle until fed; never blind-poll

protogen is TRUNK-ONLY and the pinned upstream for a consumer fleet, so the two
things that hurt most both happen OUT OF BAND: someone else advances `master`
while you work, and the battery goes red after you push. Neither announces
itself. A session therefore arms a small set of persistent Monitor-tool
observers up front; their events — not guesses, not blind polls — are how those
states are learned.

## The rule

1. **Arm the always-arm pair on turn ONE of EVERY session, unconditionally:**
   `Monitor(command="tools/claude/monitors/git-behind.sh", persistent=true)` and
   `Monitor(command="tools/claude/monitors/ci-watch.sh", persistent=true)`.
   Both are cheap and idle until something happens. `git-behind` polls the
   remote and reports when this checkout falls behind — the difference between
   rebasing deliberately and discovering it at push-reject time. `ci-watch`
   polls the public Actions API (no token needed) and reports run transitions,
   including every failing conclusion. The SessionStart banner and the
   per-prompt `[WARN] <monitor> NOT armed` hook carry the obligation
   mechanically; both go quiet once armed.
2. **Do not ask, do not narrate — arm, then answer the prompt.** Arming is not a
   decision to surface; it is the precondition for the rest of the session being
   honest about remote state.
3. **Re-arm rules differ for compact vs restart.** A `/compact` does NOT kill
   monitors, it only wipes your memory of arming them — a re-arm is a harmless
   no-op because each script `flock`-self-dedups. A SESSION RESTART kills every
   monitor regardless of `persistent: true` — re-arm for real. Never reason "I
   armed it earlier, so it is alive."
4. **The lock is the liveness signal, not the task API.** `TaskList` / `TaskGet`
   do not surface armed Monitor tasks. Liveness is the `flock` probe of
   `.protogen/<monitor>.lock` — which is exactly what the per-prompt `[WARN]`
   does. If the WARN is absent, the monitor is live.
5. **Trust the event; follow its pointer.** Every event is self-describing and
   carries where-to-dig (the rebase command, the failing run's URL). When remote
   state "feels stale", read the monitor's event or arm one — never blind-poll
   an API in a loop, and never treat silence as green.

## Why blind-polling is the anti-pattern
A backgrounded `curl` loop that prints only at the end is indistinguishable from
a hang, costs a turn to check, and reports nothing while it runs. The Monitor
tool turns each stdout line into a notification as it happens; that is the whole
point. If you catch yourself backgrounding a poll loop to watch remote state,
arm the monitor instead.

## Authoring contract (when touching the scripts)
- `tail -F` (follow by name) over `-f`; `grep --line-buffered` / `awk … fflush()`
  in every pipe, or events lag.
- Truncate with `awk`, never `| head` — `head` SIGPIPEs the producer and trips
  `set -o pipefail`.
- Degrade gracefully when `flock` is absent: poll WITHOUT the lock. A missing
  `flock` under `set -euo pipefail` otherwise inverts into the "already armed"
  branch, so the monitor exits immediately and never polls at all.
- Emit an ARM-TIME current-state read. A change-detection loop only catches the
  NEXT transition, so arming after the event already fired would be silent
  forever.
- Cover every terminal state, not just the happy path — silence must never be
  mistakable for success.
- Filter to the lines worth acting on; a firehose monitor gets auto-stopped.
- Respect the remote's budget: the Actions API allows 60 req/hour per IP
  unauthenticated, and an unauthenticated conditional request still costs one,
  so poll slowly and back off when idle.

The shorthand: **arm both on turn one; the lock is liveness; read the event,
never blind-poll; silence is not success.**
