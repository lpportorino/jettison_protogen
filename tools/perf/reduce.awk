# Reduce complete observations only. This is descriptive, not an inference test.
BEGIN {
  if (rounds!~/^[1-9][0-9]*$/ || variants!~/^[1-9][0-9]*$/) invalid++
}
function median(kind, idx,    j,k,v) {
  for (j=1; j<=rounds; j++) sorted[j]=values[kind,idx,j]
  for (j=2; j<=rounds; j++) {
    v=sorted[j]; k=j-1
    while (k>0 && sorted[k]>v) { sorted[k+1]=sorted[k]; k-- }
    sorted[k+1]=v
  }
  return rounds%2 ? sorted[(rounds+1)/2] : (sorted[rounds/2]+sorted[rounds/2+1])/2
}
# Elapsed nanoseconds are positive decimal strings without leading zeros, so the
# millisecond columns are computed exactly, digit by digit, at any length the
# TSV carries: no exponent, no rounding. Length first, then digit order within
# one length.
function dec_less(a, b) {
  return length(a) < length(b) || (length(a) == length(b) && (a "") < (b ""))
}
function dec_add(a, b,    i, j, carry, sum, d) {
  i = length(a); j = length(b); carry = 0; sum = ""
  while (i > 0 || j > 0 || carry) {
    d = carry + (i > 0 ? substr(a, i, 1) : 0) + (j > 0 ? substr(b, j, 1) : 0)
    sum = (d % 10) sum; carry = int(d / 10); i--; j--
  }
  return sum
}
# Milliseconds from TWICE a nanosecond count: halving it is what lets an even
# median keep its half nanosecond. Trailing fractional zeros are dropped.
function ms_text(twice,    i, d, rem, ns, n, whole, frac) {
  ns = ""; rem = 0
  for (i = 1; i <= length(twice); i++) {
    d = rem * 10 + substr(twice, i, 1); ns = ns int(d / 2); rem = d % 2
  }
  sub(/^0+/, "", ns)
  n = length(ns)
  whole = n > 6 ? substr(ns, 1, n - 6) : "0"
  frac = substr("000000" ns, n + 1) (rem ? "5" : "")
  sub(/0+$/, "", frac)
  return frac == "" ? whole : whole "." frac
}
function median_ms(idx,    j, k, v, sorted_ns) {
  for (j=1; j<=rounds; j++) sorted_ns[j]=ns_text[idx,j]
  for (j=2; j<=rounds; j++) {
    v=sorted_ns[j]; k=j-1
    while (k>0 && dec_less(v, sorted_ns[k])) { sorted_ns[k+1]=sorted_ns[k]; k-- }
    sorted_ns[k+1]=v
  }
  j = rounds%2 ? (rounds+1)/2 : rounds/2
  return ms_text(dec_add(sorted_ns[j], sorted_ns[rounds%2 ? j : j+1]))
}
/^#/ || /^round\t/ { next }
{
  r=$1; idx=$2; ms=$4; rc=$5
  if (NF!=6 || r!~/^[1-9][0-9]*$/ || idx!~/^(0|[1-9][0-9]*)$/ ||
      $6!~/^-?(0|[1-9][0-9]*)$/ || rc!~/^[0-9]+$/ || r>rounds || idx>=variants) {
    invalid++; next
  }
  # Names compare as text: "10" and "1e1" are different commands.
  if (seen[r,idx]++ || ((idx in name) && (name[idx] "")!=($3 ""))) invalid++
  name[idx]=$3
  # Integer milliseconds are checked by decimal truncation, not an IEEE-754
  # division that can round a large integer across a millisecond boundary. Any
  # positive interval is kept: every one carries the shell's own startup.
  expected_ms=length($6)>6 ? substr($6,1,length($6)-6) : "0"
  if (rc!=0 || $6<=0 || ("x" expected_ms)!=("x" ms)) invalid++
  values["ns",idx,r]=$6+0
  ns_text[idx,r]=$6 ""
  total[r]+=$6
  count++
}
END {
  printf "\n## Interleaved observations\n\n"
  printf "cpus=%s; loadavg %s -> %s; rounds=%s\n\n",cpus,lb,la,rounds
  if (invalid || count!=rounds*variants) {
    printf "INVALID: failed command, nonpositive interval, malformed or incomplete observations.\n"
    printf "No comparative reduction. Inspect raw TSV and per-command logs.\n"
    exit 1
  }
  for (i=0; i<variants; i++) {
    for (r=1; r<=rounds; r++) {
      ns=ns_text[i,r]
      values["share",i,r]=100*values["ns",i,r]/total[r]
      values["ratio",i,r]=values["ns",i,r]/values["ns",0,r]
      if (r==1 || dec_less(ns, lo[i])) lo[i]=ns
      if (r==1 || dec_less(hi[i], ns)) hi[i]=ns
    }
  }
  printf "| variant_idx | median_share_pct | median_ratio_to_v0 | median_ms | min_ms | max_ms | runs |\n"
  printf "|---|---|---|---|---|---|---|\n"
  for (i=0; i<variants; i++)
    printf "| %d | %.3f | %.3f | %s | %s | %s | %d |\n",i,median("share",i),median("ratio",i),median_ms(i),
           ms_text(dec_add(lo[i],lo[i])),ms_text(dec_add(hi[i],hi[i])),rounds
  printf "\nCommands (variant indices match raw TSV):\n\n"
  for (i=0; i<variants; i++) printf "    v%d: %s\n",i,name[i]
  printf "\nShares and ratios use raw nanoseconds within each round, then are independently median-reduced.\n"
  printf "Median shares need not sum to 100%%. Ratios above 1 mean slower than v0.\n"
  printf "Fixed order and contention remain confounders; this does not subtract noise.\n"
  printf "Observed min..max ranges and medians are descriptive, not confidence intervals.\n"
  printf "No statistical significance or stable ranking is established by this report.\n"
}
