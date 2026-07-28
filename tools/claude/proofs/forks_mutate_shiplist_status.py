import sys
p = sys.argv[1]
s = open(p).read()
guard = ('  list="$("$BRIEF_CHECK" ship-list "$brief" --root "$ROOT")" || list_status=$?\n'
         '  [ "$list_status" -eq 0 ] ||\n'
         '    error "ship-list-failed: brief-check ship-list FAILED (exit $list_status) for $brief; '
         'that is an ERROR, not an empty ship list — the fork is retained and must not be dispatched: $fork"\n\n')
assert guard in s, "guard block not found verbatim"
s = s.replace(guard, '')
old_feed, new_feed = '  done <<< "$list"\n', '  done < <("$BRIEF_CHECK" ship-list "$brief" --root "$ROOT")\n'
assert old_feed in s, "feed line not found"
s = s.replace(old_feed, new_feed)
open(p, 'w').write(s)
print("  mutation applied: guard removed, process substitution restored")
