# Reject a bare apostrophe inside a single-quoted `NAME='...'` shell payload.
#
# WHY `bash -n` DOES NOT COVER THIS. generate-protos.sh builds each language leg
# as a single-quoted payload handed to `bash -c`. An apostrophe inside one closes
# the string. With an ODD count the parse breaks and `bash -n` catches it. With
# an EVEN count the quoting REBALANCES: `bash -n` passes, the text between the
# two apostrophes is reparsed as an assignment prefix plus a command, and the
# variable ends up EMPTY — every leg then dies at runtime with "command not
# found". Measured on a real regression: payload length 0 against 2690 after the
# fix, with `bash -n` green in both cases. Parity is not the invariant; absence
# is.
#
# THIS SCRIPT MUST NOT BE ABLE TO PASS BY SEEING NOTHING. Its own discovery is a
# regex, so a payload written in a shape the regex misses would make it print
# nothing and exit 0 — reproducing, one level up, the exact silence it exists to
# break. A draft did precisely that: indenting the assignments by two spaces took
# recognized blocks from 11 to 0 and turned a red gate green over an unchanged
# bug. Hence the floor in END, and the deliberately tolerant opener.
#
# It lives in a FILE rather than inline in lint.mk because an apostrophe-checking
# program embedded in a Makefile recipe would carry the very hazard it detects.
#
# Invoke over ALL files in ONE call (not xargs -n 1): the floor is global, since
# most shell scripts legitimately contain no payload at all.

# Opener: an assignment whose value starts a single-quoted string. Tolerant on
# purpose — leading indentation, an `export`/`declare` prefix, and content after
# the quote are all still payloads, and a stricter regex is how the draft went
# blind.
/^[[:space:]]*(export[[:space:]]+|declare[[:space:]]+(-[A-Za-z]+[[:space:]]+)?|local[[:space:]]+)?[A-Za-z_][A-Za-z0-9_]*='/ {
  if (!inblk) {
    rest = $0
    sub(/^[^=]*='/, "", rest)      # everything after the opening quote
    # A single-line assignment (RED='\033[0;31m') opens AND closes here — it is
    # not a payload block, and treating it as one floods the gate with false
    # positives on ordinary shell. Only a quote left OPEN starts a block.
    probe = rest
    gsub(/'"'"'/, "", probe)
    gsub(/'\\''/, "", probe)
    if (probe ~ /'/) next          # closed on this line
    inblk = 1
    blocks++
    name = $0
    sub(/^[[:space:]]*(export[[:space:]]+|declare[[:space:]]+(-[A-Za-z]+[[:space:]]+)?|local[[:space:]]+)?/, "", name)
    sub(/=.*/, "", name)
    start = FNR
    check(rest, FNR)
    next
  }
}

# Closer: a lone quote, tolerating surrounding whitespace.
inblk && /^[[:space:]]*'[[:space:]]*$/ { inblk = 0; next }

inblk { check($0, FNR) }

function check(line, lineno,   probe) {
  probe = line
  # Strip BOTH legitimate ways to place a literal apostrophe inside a
  # single-quoted string. Missing the second one would reject correct code, and
  # a gate with false positives is a gate people switch off.
  gsub(/'"'"'/, "", probe)         # close, double-quoted apostrophe, reopen
  gsub(/'\\''/, "", probe)         # close, backslash-escaped apostrophe, reopen
  if (probe ~ /'/) {
    printf "%s:%d: bare apostrophe inside %s (payload opened line %d)\n", FILENAME, lineno, name, start
    printf "    %s\n", line
    bad++
  }
}

END {
  if (bad) {
    printf "\n  A bare apostrophe closes the single-quoted payload. An EVEN count is\n"
    printf "  worse than an odd one: the quoting rebalances, bash -n passes, and the\n"
    printf "  payload silently becomes EMPTY. Rewrite the prose without apostrophes,\n"
    printf "  or use one of the escape idioms already in the file.\n"
    exit 1
  }
  # NON-VACUITY FLOOR. Zero recognized payloads across the whole file set means
  # DISCOVERY broke, not that the tree is clean — this repo has payload blocks,
  # so an empty match set is a green tick over zero coverage.
  if (blocks == 0) {
    printf "payload-apostrophes: FAIL — recognized ZERO single-quoted payload blocks.\n" > "/dev/stderr"
    printf "  This tree contains them (generate-protos.sh), so an empty set means the\n" > "/dev/stderr"
    printf "  opener stopped matching, not that there is nothing to check.\n" > "/dev/stderr"
    exit 1
  }
  printf "  %d payload block(s) checked\n", blocks
}
