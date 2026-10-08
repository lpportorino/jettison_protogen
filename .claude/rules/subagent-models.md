---
description: Every subagent runs on Sonnet unless its task needs open-ended judgement; a dispatch with no model silently inherits the session's larger model.
---

# Subagent model tier — Sonnet for bounded work

Dispatch a subagent on **Sonnet** — `model: "sonnet"` on the call, or an agent in
`.claude/agents/` that pins `model: sonnet` — whenever its task is BOUNDED: a
read against a stated checklist, a visual review, an inventory or a search, a
fact-check of claims against the tree, a report digest, a reproduction with a
named command. Prefer a pinned agent over a hand-written prompt: it fixes the
tier and the brief in one place.

Keep the session's own model only for OPEN judgement — design, root cause with
no lead, the claims-and-design lens of an antagonistic review — and say which
it is when you dispatch.

A general-purpose agent with no `model` INHERITS the session's model, so a
bounded task dispatched that way silently runs on the larger model. Set the
model, or use a pinned agent, on every dispatch.
