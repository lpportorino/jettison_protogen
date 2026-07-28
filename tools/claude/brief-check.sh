#!/usr/bin/env bash
# brief-check.sh — refuse to dispatch a fork whose brief is defective.
#
# WHY THIS EXISTS. Two waves of isolated forks ran in this repo and every single
# one corrected its brief; not one was clean. Prose rules about writing good
# briefs have now failed twice, while a worker or an auditor caught every defect
# after dispatch — which is to say, after the cost had already been paid. The
# checks below are each traced to a defect that actually happened.
#
# WHAT THIS IS NOT. It is not a prose critic. Every check reads either a
# DECLARED list (the brief's own `## FILES YOU OWN` / `## FORBIDDEN` sections,
# already a convention here) or a DECLARED directive, and resolves it against
# the real base tree. Two candidate checks that could only be implemented by
# pattern-matching English were REFUSED rather than shipped unreliable; see
# "REFUSED CHECKS" at the foot of this file for the false positives that were
# measured on a good brief, and the honest replacements that shipped instead.
#
# THE THIRD ANSWER. A check that cannot see something says so. `clean` and
# `I could not look` are never printed as the same result: every run ends with
# an UNJUDGED block naming what was out of scope and why. That block is not
# decoration — it is the only thing that stops this gate going green over a
# brief it never actually read.
#
# EXIT CODES, and they are load-bearing for the canaries:
#   0  no findings
#   1  FINDINGS — a defective brief (this is a FAIL)
#   2  usage error
#   3  internal error (this is an ERROR, never a verdict about the brief)
# A canary that asserts only "non-zero" cannot tell a FAIL from a broken script
# wearing the right colour, so the test suite asserts the code AND the clause id.
#
# NOGLOB IS GLOBAL AND DELIBERATE. This script word-splits brief text to find
# tokens, and brief text is FULL of globs: an unprotected split expanded
# `renderer/**` into forty real directory entries and silently replaced the
# author's fence with a snapshot of the tree. Pathname expansion is re-enabled
# only inside the one subshell that genuinely wants it (exists_on_disk).
set -euof pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
DEFAULT_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd -P)"

# Files every brief this harness issues necessarily names but which cannot exist
# at the base sha, because the worker creates them. Naming them here is a
# decision on the record: without it, check 6 fires on every well-formed brief,
# and a check that fires on good briefs is disabled within a week.
HARNESS_DELIVERABLES="FINAL_REPORT.md DONATION_BRIEF.md DONATION_OWNER.md"

FINDINGS=0
UNJUDGED_LINES=()

die() {
  printf '[brief-check] ERROR — %s\n' "$*" >&2
  exit 3
}

usage() {
  cat >&2 <<'EOF'
usage:
  tools/claude/brief-check.sh check <brief> [--root <dir>] [--sibling <path>]...
  tools/claude/brief-check.sh ship-list <brief> [--root <dir>]
  tools/claude/brief-check.sh cite-paths <brief> [--root <dir>]

check     runs every armed check and exits 1 on findings.
ship-list prints the cited sources that exist in <root> but are NOT tracked, so
          a clone cannot carry them; forks.sh copies exactly these into the fork.
cite-paths prints the in-scope cited paths (the shared extractor, exposed so the
          shipping half and the checking half can never drift apart).

--sibling may name a brief file or a live fork directory (whose committed
DONATION_BRIEF.md is then read). Repeat it once per concurrently OWNED fork.
EOF
  exit 2
}

finding() {
  local clause="$1"
  shift
  printf '[brief-check] FAIL — %s: %s\n' "$clause" "$*" >&2
  FINDINGS=$((FINDINGS + 1))
}

unjudged() {
  UNJUDGED_LINES+=("$1")
}

git_at() {
  local repo="$1"
  shift
  env -u GIT_DIR -u GIT_WORK_TREE git -C "$repo" "$@"
}

# ---------------------------------------------------------------------------
# Glob algebra. Entries are matched against the base tree as EXACT regexes
# derived from the glob, never by hand-rolled prefix guessing: `**` crosses a
# slash, `*` does not, and conflating the two is how an overlap check quietly
# stops reporting.
# ---------------------------------------------------------------------------
glob_to_regex() {
  local g="$1" out="" i ch n
  g="${g//\*\*/$'\001'}"
  n=${#g}
  for ((i = 0; i < n; i++)); do
    ch="${g:i:1}"
    case "$ch" in
      $'\001') out+='.*' ;;
      '*') out+='[^/]*' ;;
      '?') out+='[^/]' ;;
      '.' | '+' | '(' | ')' | '[' | ']' | '{' | '}' | '^' | '$' | '|' | '\')
        out+="\\$ch"
        ;;
      *) out+="$ch" ;;
    esac
  done
  # A glob-free entry naming a directory must cover the files beneath it, or
  # `renderer` in a fence would protect nothing at all.
  case "$1" in
    *'*'* | *'?'*) printf '^%s$\n' "$out" ;;
    *) printf '^%s(/.*)?$\n' "$out" ;;
  esac
}

has_glob() {
  case "$1" in
    *'*'* | *'?'*) return 0 ;;
    *) return 1 ;;
  esac
}

# Literal path depth before the first glob metacharacter. This is the
# specificity metric used when neither side of a comparison resolves to real
# files (a brief may legitimately own a file it is about to create).
literal_depth() {
  local entry="$1" prefix
  prefix="${entry%%[*?]*}"
  prefix="${prefix%/}"
  if [ -z "$prefix" ]; then
    printf '0\n'
    return 0
  fi
  printf '%s\n' "${prefix//[^\/]/}" | awk '{print length($0) + 1}'
}

# ---------------------------------------------------------------------------
# Token normalisation and the SCOPE RULE.
#
# The scope rule is the whole false-positive defence for check 1. A brief
# legitimately names paths in OTHER repositories (a consumer that pins this one)
# and paths that do not exist yet. Judging those would fire on good briefs. So a
# cited path is judged only when its FIRST SEGMENT is a real top-level entry of
# the base tree; everything else is reported UNJUDGED, out loud, with a count.
# ---------------------------------------------------------------------------
normalise_token() {
  local t="$1"
  t="${t//\`/}"
  t="${t%%#*}"
  t="${t#\"}"
  t="${t%\"}"
  t="${t#\'}"
  t="${t%\'}"
  t="${t#(}"
  while :; do
    case "$t" in
      *, | *";" | *: | *. | *")" | *"]") t="${t%?}" ;;
      */) t="${t%/}" ;;
      *) break ;;
    esac
  done
  printf '%s\n' "$t"
}

is_path_like() {
  local t="$1"
  [ -n "$t" ] || return 1
  case "$t" in
    *://* | /* | '~'* | -*) return 1 ;;
  esac
  case "$t" in
    */*) return 0 ;;
  esac
  case "$t" in
    *.md | *.sh | *.mk | *.clj | *.cljc | *.cljs | *.edn | *.c | *.h | *.rs \
      | *.py | *.yml | *.yaml | *.json | *.awk | *.proto | *.ts | *.go \
      | *.java | *.kt | *.zig)
      return 0
      ;;
  esac
  return 1
}

TOP_LEVEL=""
load_top_level() {
  local root="$1"
  TOP_LEVEL="$(
    {
      git_at "$root" ls-tree --name-only HEAD 2>/dev/null || true
      ls -A -- "$root" 2>/dev/null || true
    } | sort -u
  )"
  [ -n "$TOP_LEVEL" ] || die "could not enumerate top-level entries of $root"
}

in_scope() {
  local t="$1" first="${1%%/*}"
  printf '%s\n' "$TOP_LEVEL" | grep -qxF -- "$first"
}

TRACKED=""
load_tracked() {
  local root="$1"
  TRACKED="$(git_at "$root" ls-files)" ||
    die "git ls-files failed in $root; this is an ERROR, not a clean brief"
  [ -n "$TRACKED" ] ||
    die "git ls-files returned NOTHING in $root — discovery broke; refusing to judge a brief against an empty tree"
}

resolve_entry() {
  local entry="$1" re
  re="$(glob_to_regex "$entry")"
  printf '%s\n' "$TRACKED" | grep -E -- "$re" || true
}

entry_matches() {
  local entry="$1" pattern="$2" re
  re="$(glob_to_regex "$pattern")"
  printf '%s\n' "$entry" | grep -qE -- "$re"
}

# ---------------------------------------------------------------------------
# Brief parsing. Sections are located by heading text; the two conventions
# already in use here are `## FILES YOU OWN` and `## FORBIDDEN — ...`.
# ---------------------------------------------------------------------------
section_body() {
  local brief="$1" want="$2"
  awk -v want="$want" '
    /^#+[[:space:]]/ {
      h = $0
      sub(/^#+[[:space:]]*/, "", h)
      inside = (toupper(h) ~ want) ? 1 : 0
      next
    }
    inside { print }
  ' "$brief"
}

# Emits `NEW<TAB>entry` or `OLD<TAB>entry`. The NEW marker is read PER LINE
# before annotations are stripped, because `(NEW)` is what separates "the fork
# will create this" from check 6's real defect: a brief that opens by calling a
# file existing and working when the base sha does not contain it.
parse_entries() {
  local brief="$1" want="$2" line flag stripped tok norm
  while IFS= read -r line; do
    flag="OLD"
    case "$line" in
      *'(NEW'*) flag="NEW" ;;
    esac
    stripped="$(printf '%s\n' "$line" | sed 's/([^)]*)//g')"
    for tok in $stripped; do
      norm="$(normalise_token "$tok")"
      is_path_like "$norm" || continue
      printf '%s\t%s\n' "$flag" "$norm"
    done
  done < <(section_body "$brief" "$want")
}

OWNED_RE='OWNED|FILES YOU OWN|YOU OWN'
FORBID_RE='FORBIDDEN|DO NOT TOUCH|FENCED|OFF LIMITS'

owned_entries() { parse_entries "$1" "$OWNED_RE" | cut -f2 | sort -u; }
forbid_entries() { parse_entries "$1" "$FORBID_RE" | cut -f2 | sort -u; }

# Every path-like token anywhere in the brief: the OWNED/FORBIDDEN blocks plus
# every backticked span. One extractor, shared by `check` and `ship-list`, so
# the set that gets SHIPPED is by construction the set that gets JUDGED.
cited_tokens() {
  local brief="$1" span tok norm
  {
    grep -o '`[^`]*`' -- "$brief" || true
    section_body "$brief" "$OWNED_RE"
    section_body "$brief" "$FORBID_RE"
  } | while IFS= read -r span; do
    span="$(printf '%s\n' "$span" | sed 's/([^)]*)//g')"
    for tok in $span; do
      norm="$(normalise_token "$tok")"
      is_path_like "$norm" || continue
      printf '%s\n' "$norm"
    done
  done | sort -u
}

exists_on_disk() {
  local root="$1" entry="$2"
  if has_glob "$entry"; then
    local hit
    hit="$(
      cd -- "$root" 2>/dev/null || exit 0
      set +f
      shopt -s nullglob dotglob globstar
      # shellcheck disable=SC2086
      set -- $entry
      [ "$#" -gt 0 ] && printf 'yes\n'
    )"
    [ -n "$hit" ]
  else
    [ -e "$root/$entry" ]
  fi
}

# ---------------------------------------------------------------------------
# CHECK 1 — a cited path that does not exist and is not shipped in.
# ---------------------------------------------------------------------------
check_cited_paths() {
  local brief="$1" root="$2" entry newset owned skipped=0
  newset="$(parse_entries "$brief" "$OWNED_RE" | awk -F'\t' '$1 == "NEW" {print $2}' | sort -u)"
  # OWNED entries are check 6's jurisdiction, not this one's. Judging them here
  # too would make a single missing owned path fire TWO clauses, and a canary
  # that cannot isolate its clause proves nothing about which one refused —
  # which is the exact failure mode this whole file exists to stop.
  owned="$(owned_entries "$brief")"
  while IFS= read -r entry; do
    [ -n "$entry" ] || continue
    if [ -n "$owned" ] && printf '%s\n' "$owned" | grep -qxF -- "$entry"; then
      continue
    fi
    if ! in_scope "$entry"; then
      skipped=$((skipped + 1))
      unjudged "cited path outside the base tree, not judged: $entry"
      continue
    fi
    [ -z "$(resolve_entry "$entry")" ] || continue
    if exists_on_disk "$root" "$entry"; then
      continue # untracked but present: Part 1 ships it into the fork
    fi
    if printf '%s\n' "$newset" | grep -qxF -- "$entry"; then
      continue
    fi
    if printf '%s\n' $HARNESS_DELIVERABLES | grep -qxF -- "$entry"; then
      continue
    fi
    finding cited-path-missing \
      "the brief cites '$entry', which is neither in the base tree nor present in $root to be shipped in, and is not marked (NEW)"
  done < <(cited_tokens "$brief")
  [ "$skipped" -eq 0 ] ||
    printf '[brief-check] note — %s cited path(s) were out of scope; see UNJUDGED\n' "$skipped"
}

# ---------------------------------------------------------------------------
# CHECK 2 — a path in BOTH the OWNED and FORBIDDEN lists of the same brief.
#
# A naive intersection is WRONG and would fire on good briefs: `tools/**` owned
# with `tools/devcards/**` forbidden is a legitimate CARVE-OUT, not a
# contradiction. Precedence is by specificity, exactly as a fence should read.
# The defect this fires on is the opposite arrangement — a brief naming a
# SPECIFIC file to fix inside a tree it fences — which is not completable to
# green by any worker.
# ---------------------------------------------------------------------------
is_carve_out() {
  local owned="$1" forbid="$2" ores fres
  ores="$(resolve_entry "$owned")"
  fres="$(resolve_entry "$forbid")"
  if [ -n "$ores" ] && [ -n "$fres" ]; then
    # forbidden is a strict subset of owned => a carve-out inside owned scope
    if [ -z "$(comm -13 <(printf '%s\n' "$ores" | sort -u) <(printf '%s\n' "$fres" | sort -u))" ] &&
      [ -n "$(comm -23 <(printf '%s\n' "$ores" | sort -u) <(printf '%s\n' "$fres" | sort -u))" ]; then
      return 0
    fi
    return 1
  fi
  [ "$(literal_depth "$forbid")" -gt "$(literal_depth "$owned")" ]
}

entries_overlap() {
  local a="$1" b="$2" ares bres
  entry_matches "$a" "$b" && return 0
  entry_matches "$b" "$a" && return 0
  ares="$(resolve_entry "$a")"
  bres="$(resolve_entry "$b")"
  [ -n "$ares" ] && [ -n "$bres" ] || return 1
  [ -n "$(comm -12 <(printf '%s\n' "$ares" | sort -u) <(printf '%s\n' "$bres" | sort -u))" ]
}

check_owned_vs_forbidden() {
  local brief="$1" owned forbid o f
  owned="$(owned_entries "$brief")"
  forbid="$(forbid_entries "$brief")"
  if [ -z "$owned" ] || [ -z "$forbid" ]; then
    unjudged "owned/forbidden conflict NOT judged: the brief declares $([ -z "$owned" ] && printf 'no OWNED list' || printf 'no FORBIDDEN list')"
    return 0
  fi
  while IFS= read -r o; do
    [ -n "$o" ] || continue
    while IFS= read -r f; do
      [ -n "$f" ] || continue
      entries_overlap "$o" "$f" || continue
      if is_carve_out "$o" "$f"; then
        continue
      fi
      finding owned-forbidden-conflict \
        "'$o' is OWNED and '$f' is FORBIDDEN in the same brief, and the fence is not a narrower carve-out — no worker can complete this to green"
    done <<< "$forbid"
  done <<< "$owned"
}

# ---------------------------------------------------------------------------
# CHECK 3 — OWNED sets overlapping ACROSS sibling briefs. No precedence applies
# here: two workers named on one file is a collision however specific either
# claim is.
# ---------------------------------------------------------------------------
resolve_sibling_brief() {
  local p="$1"
  if [ -d "$p" ]; then
    if [ -f "$p/DONATION_BRIEF.md" ]; then
      printf '%s\n' "$p/DONATION_BRIEF.md"
      return 0
    fi
    return 1
  fi
  [ -f "$p" ] || return 1
  printf '%s\n' "$p"
}

check_sibling_overlap() {
  local brief="$1"
  shift
  local sib sib_brief mine theirs o t
  if [ "$#" -eq 0 ]; then
    unjudged "cross-brief OWNED overlap NOT judged: no sibling brief was supplied"
    return 0
  fi
  mine="$(owned_entries "$brief")"
  if [ -z "$mine" ]; then
    unjudged "cross-brief OWNED overlap NOT judged: this brief declares no OWNED list"
    return 0
  fi
  for sib in "$@"; do
    if ! sib_brief="$(resolve_sibling_brief "$sib")"; then
      unjudged "sibling '$sib' has no readable brief; its OWNED set was NOT judged"
      continue
    fi
    theirs="$(owned_entries "$sib_brief")"
    if [ -z "$theirs" ]; then
      unjudged "sibling brief '$sib_brief' declares no OWNED list; NOT judged"
      continue
    fi
    while IFS= read -r o; do
      [ -n "$o" ] || continue
      # A HARNESS DELIVERABLE IS PER-FORK, SO IT IS NEVER A COLLISION. Each fork
      # is its own CLONE with its own worktree, so two workers each writing
      # `FINAL_REPORT.md` write two different files on disk — the premise of this
      # check ("two workers dispatched onto one file") is simply false for these
      # three names. Without this skip the check fires on every well-formed pair
      # in every multi-fork wave, because this harness REQUIRES each brief to
      # name its own report; that is the "fires on good briefs" failure mode the
      # head of this file promises to avoid, and check 1 already excludes the
      # same three names for the same reason.
      if printf '%s\n' $HARNESS_DELIVERABLES | grep -qxF -- "$o"; then
        continue
      fi
      while IFS= read -r t; do
        [ -n "$t" ] || continue
        entries_overlap "$o" "$t" || continue
        finding sibling-owned-overlap \
          "this brief owns '$o' and sibling '$sib_brief' owns '$t'; two workers would be dispatched onto one file"
      done <<< "$theirs"
    done <<< "$mine"
  done
}

# ---------------------------------------------------------------------------
# CHECK 4 — negative existence claims, VERIFIED rather than merely accompanied.
#
# The brief spec asked for "a claim of the form 'X does not exist' with no
# verification command beside it". Detecting that in prose was measured to fire
# on a good brief (see REFUSED CHECKS). What ships instead is stronger than the
# request: a declared assertion is not checked for having a command next to it,
# it is EXECUTED against the base tree, so a worker can never build around an
# absence that is not real.
#
#   ASSERT-ABSENT: <path-or-glob>
#   ASSERT-NO-CALLERS: <symbol> [-- <pathspec>...]
# ---------------------------------------------------------------------------
check_declared_assertions() {
  local brief="$1" root="$2" line arg count=0 hits sym spec
  while IFS= read -r line; do
    case "$line" in
      *ASSERT-ABSENT:*)
        arg="$(normalise_token "$(printf '%s\n' "${line#*ASSERT-ABSENT:}" | awk '{$1=$1; print}')")"
        [ -n "$arg" ] || {
          finding assertion-malformed "ASSERT-ABSENT: was declared with no argument"
          continue
        }
        count=$((count + 1))
        hits="$(resolve_entry "$arg")"
        if [ -n "$hits" ]; then
          finding assert-absent-violated \
            "the brief asserts '$arg' is absent, but the base tree tracks it: $(printf '%s' "$hits" | head -n 3 | tr '\n' ' ')"
        elif exists_on_disk "$root" "$arg"; then
          finding assert-absent-violated \
            "the brief asserts '$arg' is absent, but it exists on disk in $root"
        fi
        ;;
      *ASSERT-NO-CALLERS:*)
        arg="$(printf '%s\n' "${line#*ASSERT-NO-CALLERS:}" | awk '{$1=$1; print}')"
        sym="${arg%% --*}"
        sym="$(normalise_token "$sym")"
        [ -n "$sym" ] || {
          finding assertion-malformed "ASSERT-NO-CALLERS: was declared with no symbol"
          continue
        }
        count=$((count + 1))
        spec=""
        case "$arg" in
          *' -- '*) spec="${arg#* -- }" ;;
        esac
        # shellcheck disable=SC2086
        hits="$(git_at "$root" grep -n --fixed-strings -- "$sym" $spec 2>/dev/null || true)"
        if [ -n "$hits" ]; then
          finding assert-no-callers-violated \
            "the brief asserts nothing references '$sym', but the base tree has $(printf '%s\n' "$hits" | wc -l) hit(s), e.g. $(printf '%s\n' "$hits" | head -n 1)"
        fi
        ;;
    esac
  done < "$brief"
  if [ "$count" -eq 0 ]; then
    unjudged "negative-existence claims NOT judged: the brief declared no ASSERT-ABSENT / ASSERT-NO-CALLERS directive (a claim made only in prose is not checkable)"
  else
    printf '[brief-check] ok — %s declared assertion(s) verified against the base tree\n' "$count"
  fi
}

# ---------------------------------------------------------------------------
# CHECK 5 — battery permission granted alongside a fence the battery WRITES to.
#
# The write set is a TABLE, because deriving it in general means interpreting
# make plus the Clojure it shells out to. A stale table is the hazard, so each
# row RE-VERIFIES its premise against the tree and this check ERRORS rather than
# passing when the premise stops holding. A table that cannot go quietly stale
# is a different object from a hardcoded constant.
#
#   MAY-RUN: <command>
# ---------------------------------------------------------------------------
# Returns 0 with the write set on stdout, 1 when the command has no row, and 3
# when a row's premise has stopped holding.
#
# `die` is NOT usable here. This function is called inside a command
# substitution, where an exit terminates only the SUBSHELL — the caller then
# sees a plain non-zero status and downgrades a STALE TABLE to "no row, not
# judged", which is an internal error reading as a clean brief. The canary suite
# caught exactly that; the distinct return code is the fix.
writes_of_command() {
  local root="$1" cmd="$2"
  case "$cmd" in
    *check-renderer*)
      if ! git_at "$root" grep -qE '^check-renderer:.*[[:space:]]standard-brief-generate([[:space:]]|$)' -- renderer.mk; then
        printf '[brief-check] ERROR — the check-5 write table is STALE: check-renderer no longer lists standard-brief-generate in renderer.mk\n' >&2
        return 3
      fi
      if ! git_at "$root" grep -q 'ui-standard-review/STANDARD.md' -- tools/devcards/src/devcards/standard_brief.clj; then
        printf '[brief-check] ERROR — the check-5 write table is STALE: devcards.standard-brief no longer names ui-standard-review/STANDARD.md\n' >&2
        return 3
      fi
      printf '%s\n' '.claude/skills/ui-standard-review/STANDARD.md'
      ;;
    *)
      return 1
      ;;
  esac
}

check_permitted_commands() {
  local brief="$1" root="$2" line cmd forbid w f count=0
  forbid="$(forbid_entries "$brief")"
  while IFS= read -r line; do
    case "$line" in
      *MAY-RUN:*) ;;
      *) continue ;;
    esac
    cmd="$(printf '%s\n' "${line#*MAY-RUN:}" | sed 's/([^)]*)//g' | tr -d '`' | awk '{$1=$1; print}')"
    [ -n "$cmd" ] || {
      finding permission-malformed "MAY-RUN: was declared with no command"
      continue
    }
    count=$((count + 1))
    local w_status=0
    w="$(writes_of_command "$root" "$cmd")" || w_status=$?
    case "$w_status" in
      0) ;;
      1)
        unjudged "MAY-RUN command '$cmd' has no write-set row; its writes were NOT judged"
        continue
        ;;
      *)
        # The reason is already on stderr; a stale table must never be
        # indistinguishable from a command this gate simply does not know.
        exit 3
        ;;
    esac
    if [ -z "$forbid" ]; then
      unjudged "MAY-RUN command '$cmd' resolves to writes, but the brief declares no FORBIDDEN list; NOT judged"
      continue
    fi
    while IFS= read -r f; do
      [ -n "$f" ] || continue
      entry_matches "$w" "$f" || continue
      finding permitted-command-writes-into-fence \
        "the brief permits '$cmd', which writes '$w', and the same brief fences '$f' — the fork is told to violate its own fence"
    done <<< "$forbid"
  done < "$brief"
  [ "$count" -gt 0 ] ||
    unjudged "command permissions NOT judged: the brief declared no MAY-RUN directive"
}

# ---------------------------------------------------------------------------
# CHECK 6 — a base sha whose tree lacks a file the brief says the fork owns.
#
# The literal form of this request fires on every good brief, because every
# brief owns files the worker is about to create. `(NEW)` is what separates the
# two, and it is already the convention in use. An owned path that is absent and
# unmarked is the real defect: a brief that opened by saying its script "exists
# and works" against a base that did not contain it.
# ---------------------------------------------------------------------------
check_owned_present_at_base() {
  local brief="$1" root="$2" flag entry judged=0
  while IFS=$'\t' read -r flag entry; do
    [ -n "$entry" ] || continue
    judged=$((judged + 1))
    [ "$flag" = "OLD" ] || continue
    printf '%s\n' $HARNESS_DELIVERABLES | grep -qxF -- "$entry" && continue
    [ -z "$(resolve_entry "$entry")" ] || continue
    if exists_on_disk "$root" "$entry"; then
      unjudged "owned path '$entry' is untracked but present in $root; it will be shipped, not inherited from the base sha"
      continue
    fi
    finding owned-path-absent-at-base \
      "the brief says the fork owns '$entry' and does not mark it (NEW), but the base sha does not contain it"
  done < <(parse_entries "$brief" "$OWNED_RE")
  [ "$judged" -gt 0 ] ||
    unjudged "owned-at-base NOT judged: the brief declares no OWNED list"
}

# ---------------------------------------------------------------------------
cmd_check() {
  local brief="" root="$DEFAULT_ROOT"
  local -a siblings=()
  [ "$#" -ge 1 ] || usage
  brief="$1"
  shift
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --root)
        [ "$#" -ge 2 ] || usage
        root="$2"
        shift 2
        ;;
      --sibling)
        [ "$#" -ge 2 ] || usage
        siblings+=("$2")
        shift 2
        ;;
      *) usage ;;
    esac
  done
  [ -f "$brief" ] || die "brief is not a regular file: $brief"
  [ -d "$root" ] || die "root is not a directory: $root"
  root="$(cd -- "$root" && pwd -P)"

  load_tracked "$root"
  load_top_level "$root"

  check_cited_paths "$brief" "$root"
  check_owned_vs_forbidden "$brief"
  check_sibling_overlap "$brief" ${siblings+"${siblings[@]}"}
  check_declared_assertions "$brief" "$root"
  check_permitted_commands "$brief" "$root"
  check_owned_present_at_base "$brief" "$root"

  if [ "${#UNJUDGED_LINES[@]}" -gt 0 ]; then
    printf '[brief-check] UNJUDGED — what this gate could NOT see:\n' >&2
    printf '  - %s\n' "${UNJUDGED_LINES[@]}" >&2
  fi
  if [ "$FINDINGS" -gt 0 ]; then
    printf '[brief-check] REFUSED — %s finding(s); the fork was NOT created\n' "$FINDINGS" >&2
    exit 1
  fi
  printf '[brief-check] OK — %s (%s unjudged note(s))\n' "$brief" "${#UNJUDGED_LINES[@]}"
}

cmd_ship_list() {
  local brief="" root="$DEFAULT_ROOT" entry
  [ "$#" -ge 1 ] || usage
  brief="$1"
  shift
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --root)
        [ "$#" -ge 2 ] || usage
        root="$2"
        shift 2
        ;;
      *) usage ;;
    esac
  done
  [ -f "$brief" ] || die "brief is not a regular file: $brief"
  [ -d "$root" ] || die "root is not a directory: $root"
  root="$(cd -- "$root" && pwd -P)"
  load_tracked "$root"
  load_top_level "$root"
  while IFS= read -r entry; do
    [ -n "$entry" ] || continue
    in_scope "$entry" || continue
    has_glob "$entry" && continue
    [ -z "$(resolve_entry "$entry")" ] || continue
    [ -f "$root/$entry" ] || continue
    printf '%s\n' "$entry"
  done < <(cited_tokens "$brief")
}

cmd_cite_paths() {
  local brief="" root="$DEFAULT_ROOT" entry
  [ "$#" -ge 1 ] || usage
  brief="$1"
  shift
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --root)
        [ "$#" -ge 2 ] || usage
        root="$2"
        shift 2
        ;;
      *) usage ;;
    esac
  done
  [ -f "$brief" ] || die "brief is not a regular file: $brief"
  root="$(cd -- "$root" && pwd -P)"
  load_tracked "$root"
  load_top_level "$root"
  while IFS= read -r entry; do
    [ -n "$entry" ] || continue
    in_scope "$entry" && printf '%s\n' "$entry"
  done < <(cited_tokens "$brief")
}

case "${1:-}" in
  check)
    shift
    cmd_check "$@"
    ;;
  ship-list)
    shift
    cmd_ship_list "$@"
    ;;
  cite-paths)
    shift
    cmd_cite_paths "$@"
    ;;
  *) usage ;;
esac

# ---------------------------------------------------------------------------
# REFUSED CHECKS — measured false positives, left out on purpose.
#
# (a) PROSE SNIFFING FOR NEGATIVE EXISTENCE CLAIMS. The obvious implementation
#     greps the brief for "does not exist" / "nothing calls" and demands a
#     verification command nearby. Measured against the very brief that
#     commissioned this file, that pattern matches the SPECIFICATION SENTENCE
#     itself, which carries no command and needs none. A gate that refuses the
#     brief asking for the gate is a gate that gets disabled in a week. The
#     declared ASSERT-ABSENT / ASSERT-NO-CALLERS directives replace it and are
#     strictly stronger, because they are executed rather than counted.
#
# (b) PROSE SNIFFING FOR BATTERY PERMISSION. Detecting "this fork may run the
#     battery" from English fails in both directions: a brief that DENIES the
#     battery still contains the word, and "do NOT run `check-renderer`" still
#     contains the token. Measured on the commissioning brief, whose text
#     "The battery at your base is RED ... Ignore it" is a denial that a keyword
#     sniffer reads as a grant. MAY-RUN replaces it with a declaration.
#
# Both refusals share one shape: a DECLARATION can be judged, an INTENTION
# cannot. Where a declaration is absent, this file says so in the UNJUDGED
# block rather than reporting a brief it never read as clean.
# ---------------------------------------------------------------------------
