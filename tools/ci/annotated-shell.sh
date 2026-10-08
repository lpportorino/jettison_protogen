#!/usr/bin/env bash
# annotated-shell.sh — the default `run:` shell for this repo's workflows.
#
# WHY IT EXISTS. This origin is public and its CI is observed without a token
# (tools/claude/monitors/ci-watch.sh), but an unauthenticated reader gets only a
# step NAME and a CONCLUSION: the raw job log answers 403, and `make` collapses
# every recipe failure to exit 2. So a red step said "failed, exit 2" and nothing
# about which clause fired — a gate whose verdict cannot be read is only half a
# gate. Check-run ANNOTATIONS, unlike logs, are served unauthenticated
# (GET /repos/{owner}/{repo}/check-runs/{job_id}/annotations), so on failure this
# wrapper emits one `::error` annotation carrying the lines that name the failure.
#
# SEMANTICS ARE THE RUNNER'S OWN. GitHub's default bash shell is
# `bash --noprofile --norc -eo pipefail {0}`; this runs the step script exactly
# that way and preserves its exit status. The only additions are a tee of the
# combined output and, on a non-zero exit, the annotation. Stdout and stderr are
# merged into one stream so the annotation shows them in the order they happened.
#
# Wire it per workflow:   defaults: { run: { shell: bash tools/ci/annotated-shell.sh {0} } }
# The path is relative to the step's working directory, so a step that sets
# `working-directory:` or runs before checkout must name `shell: bash` itself.
#
# Exit status: the step script's own. A missing script is 127, as bash reports it.
set -u
script="${1:?usage: annotated-shell.sh STEP_SCRIPT}"
log="$(mktemp)"
trap 'rm -f "$log"' EXIT

bash --noprofile --norc -eo pipefail "$script" 2>&1 | tee "$log"
rc=${PIPESTATUS[0]}
[ "$rc" -eq 0 ] && exit 0

# The annotation body: the TAIL first — the last lines are where a gate prints
# its verdict, and a body that loses them is the defect this file exists to
# fix — then as many of the most recent failure-naming lines as still fit.
# ANSI colour stripped, lines capped, total bounded well under the annotation
# limit. Workflow-command escaping (below): % \r \n -> %25 %0D %0A.
clean="$(sed -e 's/\x1b\[[0-9;]*[A-Za-z]//g' "$log" | awk 'length($0) > 300 { $0 = substr($0, 1, 300) "…" } { print }')"
tail_part="$(printf '%s\n' "$clean" | tail -n 20)"
hits_part="$(printf '%s\n' "$clean" | head -n -20 \
  | grep -E 'FAIL|ERROR|Error|error:|Traceback|Exception|CANNOT RUN|refus|survived|\*\*\* |assert|No such file|not found|denied|exit code|exited' \
  | grep -vE '^ *(ok|killed) ' | tail -n 15)"
budget=$(( 5500 - ${#tail_part} ))
[ "$budget" -lt 0 ] && budget=0
hits_part="$(printf '%s' "$hits_part" | tail -c "$budget")"
body="== last lines ==
${tail_part}"
[ -n "$hits_part" ] && body="${body}
== earlier lines naming a failure ==
${hits_part}"
title="step failed with exit ${rc}"
[ -n "${GITHUB_JOB:-}" ] && title="${GITHUB_JOB}: ${title}"
escape() { local s="${1//'%'/'%25'}"; s="${s//$'\r'/'%0D'}"; printf '%s' "${s//$'\n'/'%0A'}"; }
printf '::error title=%s::%s\n' "$(escape "$title")" "$(escape "$body")"
exit "$rc"
