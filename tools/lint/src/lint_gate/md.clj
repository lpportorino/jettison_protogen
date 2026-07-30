(ns lint-gate.md
  "Deterministic quality gates over this repo's HAND-AUTHORED markdown.

  WHY THIS FILE EXISTS. `.claude/rules/claude-md-policy.md` is auto-loaded when
  editing ANY `.md` and declares a pile of mechanically checkable rules — the
  `paths:` frontmatter shape and its glob liveness, the `<!-- LOAD-TEST: … -->`
  sentinel, `model:` belonging to agents and never to skills, kebab-case naming, a
  pointer carrying a coordinate that resolves. Nothing executed any of them.
  `.claude/rules/review-discipline.md` records a MEASURED markdown defect — an
  inline code span folded mid-identifier, which renders a broken symbol and loses
  the grep — that no lane could see either. This closes the mechanical subset of
  both.

  WHY CLOJURE, AND WHY IT REPLACED A PYTHON GATE. Nothing in this repo lints
  python: `bash -n` plus a payload-apostrophe check cover shell, cljfmt and
  clj-kondo cover Clojure, clang-format and clang-tidy cover C, actionlint covers
  workflows. A `.py` gate was therefore the LEAST gated code in the tree — the
  thing enforcing the quality bar sat outside every lane that enforces it. Under
  `LINT_CLJ_PATHS` this file is judged by cljfmt, by clj-kondo at the zero-warning
  floor in `.clj-kondo/config.edn`, and by its own namespace-size ceiling. It
  dogfoods, and a regression in it reddens the same lanes it enforces.

  IT COSTS NO DEPENDENCY. The work is a YAML-subset parse, CommonMark backtick-run
  matching, glob translation and path resolution — `clojure.string`,
  `clojure.edn`, `java.util.regex`, `java.time.LocalDate` and `ProcessBuilder`,
  all standard library. The `:lint-gate` alias in the root `deps.edn` declares no
  `:extra-deps` and this file adds none.

  FAIL IS NOT ERROR — the exit codes separate them.

    0  clean: every check ran and found nothing.
    1  FAIL: this gate's own diagnosis. Findings are listed with file:line.
    2  ERROR: the harness broke — discovery went dark, a subject class vanished,
       an extractor matched nothing, an exemption carries no proof. A red here
       says NOTHING about the markdown and must never be read as one.

  That split exists because a non-zero exit from a broken harness wears the same
  colour as a caught defect, and a canary that cannot tell them apart proves
  nothing about the clause it names. THE CANNOT-RUN CODE IS 2 HERE AND 3 IN
  `lint-gate.core` — deliberately, not by drift: `tools/lint/md/test/md_gate_test.sh`
  asserts the exact code on every floor and every exemption clause, and renumbering
  them would mean editing the oracle to match the port, which is how a port stops
  being a port. IT IS STILL A SEAM, and naming it is the requirement rather than
  having closed it: the two gates now disagree about what number a broken harness
  exits with, so whichever one moves owes the other suite's assertions a re-mint in
  the same change.

  AN EMPTY INPUT SET IS A GREEN TICK OVER ZERO COVERAGE. Every discovery sink and
  every extractor declares a floor and EXITS 2 when it comes up empty. This repo
  tracks hand-authored markdown, rules, skills, an agent, commands, path citations
  and code spans; a zero in any of those means discovery broke, not that there is
  nothing to check. Same class of guard as `lint-sh`, `lint-ci` and `fmt-c` in
  `lint.mk`, for the same reason.

  AN UNJUDGED FILE IS A FINDING, NEVER A SKIP. Two ways this gate can fail to
  look, and both are reported out loud rather than counted as clean: a file that
  will not decode as UTF-8 -> `unreadable-file`, and a frontmatter line the YAML
  SUBSET below cannot parse -> `fm-unparsed`.

  ONE OUTPUT-SHAPE COUPLING, because it is invisible from here. The canary suite
  extracts the clause id of every finding with `sed -n 's/^  \\([a-z][a-z-]*\\) .*/\\1/p'`
  and fails a case when a SECOND clause appears. So no diagnostic continuation
  line may begin with two spaces, a lowercase word and a space — every one below
  starts with a capital or with four spaces. A wrapped lowercase continuation
  would be read as a phantom clause and turn attributed reds into unattributed
  ones.

  WHAT THIS DOES NOT COVER is printed on every run, pass or fail — see
  `not-covered` at the bottom."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io File IOException)
   (java.lang ProcessBuilder$Redirect)
   (java.nio ByteBuffer)
   (java.nio.charset CodingErrorAction StandardCharsets)
   (java.nio.file Files)
   (java.time LocalDate)
   (java.time.format DateTimeParseException)
   (java.util.regex Pattern)))

(def ^:private red "\033[31m")
(def ^:private green "\033[32m")
(def ^:private yellow "\033[33m")
(def ^:private off "\033[0m")

(def ^:private exit-ok 0)
(def ^:private exit-findings 1)
(def ^:private exit-error 2)

;; GENERATED and VENDORED markdown is excluded by PROVENANCE, and each rule here
;; is asserted to match at least one tracked file — a dead exclusion rule is rot
;; that silently narrows nothing while looking like scope.
;;
;; `.claude/skills/ui-standard-review/STANDARD.md` is embedded verbatim from
;; `docs/UI-QUALITY-CONTRACTS.md` by `make -f renderer.mk standard-brief`; scanning
;; it would double-report every finding in its source and point the fix at a file
;; that gets overwritten. Same argument for `docs/index.md` (rendered from
;; `index.md.selmer`) and the per-widget gallery pages.
(def ^:private exclude-prefixes
  [["docs/proto/" "generated per-message/per-enum pages (protodoc)"]
   ["renderer/lvgl/" "vendored upstream LVGL, byte-exact per .gitattributes"]
   ["tools/devcards/docs/" "generated gallery pages + contact sheets"]])

(def ^:private exclude-files
  [["docs/index.md" "rendered from index.md.selmer by protodoc"]
   [".claude/skills/ui-standard-review/STANDARD.md"
    "embedded verbatim from docs/UI-QUALITY-CONTRACTS.md by standard-brief"]])

(def ^:private rules-glob ".claude/rules/*.md")
(def ^:private skills-glob ".claude/skills/*/SKILL.md")
(def ^:private agents-glob ".claude/agents/*.md")
(def ^:private commands-glob ".claude/commands/*.md")

(def ^:private exemptions-path "tools/lint/md/exemptions.edn")

;; The proof an exemption owes, independent of what it matches. All four mandatory
;; and non-blank. The set and the argument for it are `exemption-proof-keys` in
;; tools/devcards/src/devcards/invariants.clj — read it there; this is the same
;; contract, in EDN because that is this repo's config format and `clojure.edn`
;; reads it with no dependency.
(def ^:private proof-keys [:rationale :retires-when :owner :expires])

;; The OUTER BOUND on an exemption's life. Horizon and expiry are SEPARATE hard
;; failures so neither masks the other: expiry catches an entry that outlived its
;; decision, the horizon catches one written never to lapse.
(def ^:private horizon-days 90)

;; `--others --exclude-standard` widens the index to UNTRACKED-but-not-ignored
;; files, and it is not optional. `lint.mk` states the reason at its own shell
;; discovery: the index alone gives a worker who has written a new file and not
;; staged it "a GREEN THAT NEVER READ IT". MEASURED, on this gate's own report:
;; with plain `git ls-files` the run exited 0 while `tools/lint/md/PORT_REPORT.md`
;; carried four dead citations, because an uncommitted file is not in the index.
;; Ignored paths (scratch, preserved forks) stay out, which is what keeps the
;; widening free.
(def ^:private discovery-args
  ["ls-files" "--cached" "--others" "--exclude-standard"])

(defn- env
  "An environment variable, or nil when unset OR EMPTY.

  Empty-is-nil matches the `os.environ.get(k) or default` idiom the python gate
  used at every seam below, so `MD_GATE_ROOT=` keeps meaning `unset` rather than
  silently rooting the gate at the empty path."
  [k]
  (let [v (System/getenv k)]
    (when-not (= "" (or v "")) v)))

;; REPO defaults to the PROCESS's working directory, which is where `lint-md.mk`
;; invokes this from and the same assumption `lint-gate.core` makes for
;; `tools/lint/ceilings.edn`. Deliberately NOT derived from this file's own
;; location: a tool that computes its root from `dirname $0` silently retargets
;; when copied, which is the trap `.claude/rules/fork-isolation.md` names. The
;; canary suite copies this namespace in order to mutate it and passes MD_GATE_ROOT
;; explicitly either way.
(def ^:private repo (or (env "MD_GATE_ROOT") (System/getProperty "user.dir")))

;; THE HERMETIC TEST SEAM. When set, this supplies the tracked-file universe in
;; place of `git ls-files`, so the canary suite can plant a known-bad fixture tree
;; without running a single git mutating command — no `git init`, no `git add`, and
;; therefore no way for a fixture harness to reach a real index.
;;
;; WHAT IT DOES NOT EXERCISE, said out loud: in this mode nothing is git-ignored,
;; so the `gitignored` resolution arm and the `git ls-files` failure diagnosis are
;; covered by the LIVE run over this repo and not by the canaries.
(def ^:private tracked-from (env "MD_GATE_TRACKED_FROM"))

;; THE SECOND TEST SEAM, and it ANNOUNCES ITSELF. The 90-day horizon and the
;; expiry check are the only clock-dependent clauses here; a canary that computed
;; its fixture dates from the real clock would drift with it, and one that could
;; not pin `today` could not go red on purpose at all. So `today` is a PARAMETER of
;; the pure validator and this env var is the only way to move it — and when it is
;; set the run says so on stderr, because a hidden seam that relaxes an expiry gate
;; is exactly the silent bypass this gate exists to refuse.
(def ^:private today-override (env "MD_GATE_TODAY"))

(defn- exit!
  "Flush BOTH streams, then exit.

  `*out*` is a buffered writer and `System/exit` does not flush it, so an
  unflushed summary is a gate whose verdict never printed. Every exit below routes
  through here for that reason."
  [code]
  (flush)
  (binding [*out* *err*] (flush))
  (System/exit code))

(defn- error!
  "Print a HARNESS failure and exit 2, never 1.

  One shape for every caller, so the canary suite can match on a stable substring
  of the headline rather than on a whole message."
  [msg]
  (binding [*out* *err*]
    (println (str red "[md-gate] ERROR" off " — " msg))
    (println (str "  This is a HARNESS failure (exit 2), not a markdown finding. "
                  "It says nothing about the docs.")))
  (exit! exit-error))

;; ── git ─────────────────────────────────────────────────────────────────────

(def ^:private git-absent-msg
  (str "no `git` on PATH, and this gate's entire input set is `git ls-files`. "
       "Install git (Debian/Ubuntu: apt-get install -y git), or run inside the "
       "toolchain container: tools/uber.sh 'make -f lint-md.mk lint-md'."))

(defn- run-git
  "Run git in REPO and return `{:code :out :err}`.

  AN ABSENT git IS A PRECONDITION FAILURE, exit 2, with an install hint. Left
  uncaught, the IOException propagates and the JVM exits 1 — the code this gate
  reserves for a FINDING, so a checkout with no git would report a markdown defect
  it never looked for. `.claude/rules/gate-enforcement.md` §4: classify at the SEAM
  where the tool is resolved, and this function is that seam.

  stderr is redirected to a TEMP FILE rather than read from a second pipe. Reading
  two pipes in sequence from one thread deadlocks whenever the un-read one fills,
  and git's stderr is not bounded by anything this gate controls — a tree that
  makes git warn per path would hang the gate instead of failing it."
  [args]
  (let [errf (doto (File/createTempFile "md-gate-git" ".err") (.deleteOnExit))
        pb (doto (ProcessBuilder. ^java.util.List (into ["git"] args))
             (.directory (io/file repo))
             (.redirectError (ProcessBuilder$Redirect/to errf)))
        proc (try
               (.start pb)
               (catch IOException _ (error! git-absent-msg)))
        out (slurp (.getInputStream proc) :encoding "UTF-8")
        code (.waitFor proc)
        err (str/trim (slurp errf :encoding "UTF-8"))]
    (.delete errf)
    {:code code :out out :err err}))

(defn- git
  "Run git and return stdout, or refuse carrying git's OWN stderr — which IS the
  whole diagnosis when discovery goes dark. Discarding it is what lets a broken
  discovery read as an empty-but-fine file list."
  [args]
  (let [{:keys [code out err]} (run-git args)]
    (when-not (zero? code)
      (error! (format "`git %s` failed (exit %d). git said: %s"
                      (str/join " " args) code
                      (if (= "" err) "<nothing>" err))))
    out))

(defn- tracked-paths
  "The tracked-file universe. ONE mechanism, then filtered by `glob-re` below —
  deliberately not a second `git ls-files <pathspec>` per subject class. git's
  pathspec `*` crosses `/` while this file's globs do not, and two discovery
  mechanisms free to disagree is how a subject class goes quietly unscanned."
  []
  (if tracked-from
    (let [f (io/file tracked-from)]
      (when-not (.isFile f)
        (error! (format "MD_GATE_TRACKED_FROM=%s unreadable: not a readable file"
                        tracked-from)))
      (into [] (comp (map str/trim) (remove #(= "" %)))
            (str/split-lines (slurp f :encoding "UTF-8"))))
    (into [] (remove #(= "" %)) (str/split (git discovery-args) #"\n" -1))))

;; ── glob translation ────────────────────────────────────────────────────────

(defn- glob-re
  "Translate a glob to a Pattern: `**` crosses directory boundaries, `*` and `?`
  do not.

  A shell-style matcher cannot be used: its `*` matches `/`, so `*.md` would match
  every nested page and a dead glob would resolve for the wrong reason. Literal
  runs go through `Pattern/quote`, so a `.` or `+` in a path never becomes a
  metacharacter."
  ^Pattern [^String pattern]
  (let [n (count pattern)]
    (loop [i 0 out (StringBuilder.)]
      (if (>= i n)
        (Pattern/compile (str out))
        (cond
          (str/starts-with? (subs pattern i) "**/")
          (recur (+ i 3) (.append out "(?:.*/)?"))

          (str/starts-with? (subs pattern i) "**")
          (recur (+ i 2) (.append out ".*"))

          (= \* (.charAt pattern i))
          (recur (inc i) (.append out "[^/]*"))

          (= \? (.charAt pattern i))
          (recur (inc i) (.append out "[^/]"))

          :else
          (recur (inc i) (.append out (Pattern/quote (str (.charAt pattern i))))))))))

(defn- paths-matching
  "Every tracked path the glob matches, sorted."
  [tracked glob]
  (let [rx (glob-re glob)]
    (sort (filter #(re-matches rx %) tracked))))

(defn- partition-md
  "Split tracked markdown into `[hand-authored excluded]`, where `excluded` is a
  seq of `[path reason]`.

  Asserts every exclusion rule is LIVE. A rule matching nothing is dead weight
  that reads as scope, and the next reader trusts it."
  [all-md]
  (let [classify (fn [path]
                   (or (first (for [[prefix reason] exclude-prefixes
                                    :when (str/starts-with? path prefix)]
                                [(str "prefix:" prefix) reason]))
                       (first (for [[nm reason] exclude-files
                                    :when (= path nm)]
                                [(str "file:" nm) reason]))))
        tagged (map (juxt identity classify) all-md)
        hand (for [[path why] tagged :when (nil? why)] path)
        excluded (for [[path why] tagged :when (some? why)] [path (second why)])
        hits (frequencies (keep (comp first second) tagged))
        declared (concat (map #(str "prefix:" (first %)) exclude-prefixes)
                         (map #(str "file:" (first %)) exclude-files))
        dead (remove hits declared)]
    (when (seq dead)
      (error! (format (str "exclusion rule(s) match ZERO tracked markdown: %s. A "
                           "dead exclusion reads as scope and narrows nothing — "
                           "delete it or fix the path.")
                      (str/join ", " dead))))
    [(sort hand) excluded]))

;; ── findings ────────────────────────────────────────────────────────────────

(defn- finding
  "One finding, in the shape every check in this gate emits.

  ONE CONSTRUCTOR rather than inline maps at each call site, because `:check` is
  what the exemption store matches on and what `apply-exemptions` groups by — a
  hand-built map missing that key would be unexemptable and would sort oddly, and
  neither failure is visible at the call site."
  [check path line detail]
  {:check check :path path :line line :detail detail})

(defn- finding-line
  "Render one finding as a fixed-width line.

  The columns are padded so a multi-finding report reads as a table — the check name
  and the location line up, which is what makes a 50-finding run scannable rather
  than a wall. `line` is optional: a whole-file finding prints the bare path rather
  than a misleading `path:0`."
  [{:keys [check path line detail]}]
  (format "%-28s %-52s %s" check (if line (str path ":" line) path) detail))

(defn- clip
  "`subs` that tolerates a short string, the way a python slice does."
  [^String s n]
  (subs s 0 (min (long n) (count s))))

(defn- q
  "Quote a value into a finding detail. Clojure's own reader syntax rather than
  python's `%r`, so the quote character differs — nothing matches on it: the
  exemption `match` field is a plain substring test against the whole detail."
  [s]
  (pr-str s))

(defn- dirname
  "The directory part of a repo-relative path, or `\"\"` for a bare filename.

  Not `java.io.File`: these are repo-relative POSIX paths out of `git ls-files`,
  never filesystem paths, and File would apply platform separator rules to strings
  that are always `/`-separated."
  [^String p]
  (if-let [i (str/last-index-of p "/")] (subs p 0 i) ""))

(defn- basename
  "The final segment of a repo-relative path, or the whole string if it has no `/`.

  Same reasoning as `dirname`: string surgery on a `/`-separated git path, not a
  filesystem operation."
  [^String p]
  (if-let [i (str/last-index-of p "/")] (subs p (inc i)) p))

(defn- split-lines*
  "Split on `\\n` KEEPING trailing empties.

  `clojure.string/split-lines` drops them and also splits on `\\r`, either of
  which would shift every reported line number. The limit of -1 is what preserves
  the trailing empty field, matching the python `str.split(\"\\n\")` the reported
  coordinates were derived from."
  [^String text]
  (str/split text #"\n" -1))

(defn- nl-before
  "Newlines in `s` before index `end` — the offset of a match from a block start."
  [^String s end]
  (count (filter #(= \newline %) (subs s 0 end))))

;; ── YAML SUBSET ─────────────────────────────────────────────────────────────
;; Deliberately a subset, and the subset is the contract: `key: value`, `key:`
;; followed by `  - item` lines, blank lines, `#` comments. Anything else yields
;; `fm-unparsed`, so an unsupported shape becomes "I could not look" rather than
;; an accepted unknown. A full YAML parser would make this gate the only thing
;; here with a runtime dependency.

(def ^:private fm-key #"^([A-Za-z_][A-Za-z0-9_-]*):[ \t]*(.*)$")
(def ^:private fm-item #"^[ \t]+-[ \t]+(.*)$")
(def ^:private fm-comment #"^[ \t]*#")

(defn- unquote-scalar
  "Strip ONE matching pair of surrounding quotes from a YAML scalar.

  MATCHING is the operative word: the first and last character must be the same
  quote character, so `\"a\" and \"b\"` is left alone rather than being mangled into
  `a\" and \"b`. Only one pair comes off, because a doubly-quoted value is a
  different authoring intent and silently unwrapping both would hide it."
  [^String value]
  (let [v (str/trim value)]
    (if (and (>= (count v) 2)
             (= (.charAt v 0) (.charAt v (dec (count v))))
             (contains? #{\" \'} (.charAt v 0)))
      (subs v 1 (dec (count v)))
      v)))

(defn- parse-frontmatter
  "Return `{:status :data :body-line :unparsed}`.

  `:status` is `\"none\"`, `\"unterminated\"` or `\"ok\"`; `:body-line` is the
  1-based line the body starts on; `:data` maps a string key to a String or a
  vector of Strings; `:unparsed` is a seq of `[line raw]`."
  [text]
  (let [lines (split-lines* text)]
    (if-not (= "---" (str/trim (nth lines 0 "")))
      {:status "none" :data {} :body-line 1 :unparsed []}
      (if-let [close (first (for [i (range 1 (count lines))
                                  :when (= "---" (str/trim (nth lines i)))]
                              i))]
        (let [st (reduce
                  (fn [st i]
                    (let [raw (nth lines i)
                          k (:key st)]
                      (cond
                        (or (= "" (str/trim raw)) (re-find fm-comment raw))
                        st

                        (re-find fm-item raw)
                        (let [item (unquote-scalar (second (re-find fm-item raw)))]
                          (if (nil? k)
                            (update st :unparsed conj [(inc i) raw])
                            (update-in st [:data k]
                                       (fn [cur]
                                         (conj (if (vector? cur) cur []) item)))))

                        :else
                        (if-let [kv (re-matches fm-key raw)]
                          (let [key' (nth kv 1)
                                value (str/trim (nth kv 2))]
                            (-> st
                                (assoc :key key')
                                (assoc-in [:data key']
                                          (if (= "" value) [] (unquote-scalar value)))))
                          (update st :unparsed conj [(inc i) raw])))))
                  {:data {} :unparsed [] :key nil}
                  (range 1 close))]
          {:status "ok" :data (:data st) :body-line (+ close 2)
           :unparsed (:unparsed st)})
        {:status "unterminated" :data {} :body-line 1 :unparsed []}))))

;; ── CommonMark code-span delimiter matching ─────────────────────────────────
;; A code span opens with a run of N backticks and closes with the next run of
;; EXACTLY N. Anything in between is content — which is why a naive backtick
;; COUNT is wrong in this repo: double-delimiter spans quoting a literal backtick
;; are used in .claude/rules/review-discipline.md itself, and a parity check
;; reports them as unbalanced.

(def ^:private fence-re #"^ {0,3}(`{3,}|~{3,})")

(defn- blocks
  "`[[start-line block-text] …]` for blank-line-separated blocks, with fenced code
  removed. A code span cannot cross a blank line, so the block is the correct unit
  for delimiter matching."
  [text]
  (let [st (reduce
            (fn [st [i line]]
              (let [m (re-find fence-re line)
                    fence (:fence st)]
                (cond
                  (some? m)
                  (let [token (.charAt ^String (second m) 0)]
                    (cond
                      (nil? fence) (cond-> (assoc st :fence token)
                                     (seq (:cur st))
                                     (-> (update :out conj
                                                 [(:start st) (str/join "\n" (:cur st))])
                                         (assoc :cur [])))
                      (= fence token) (assoc st :fence nil)
                      :else st))

                  (some? fence) st

                  (= "" (str/trim line))
                  (if (seq (:cur st))
                    (-> st
                        (update :out conj [(:start st) (str/join "\n" (:cur st))])
                        (assoc :cur []))
                    st)

                  :else
                  (-> (if (seq (:cur st)) st (assoc st :start i))
                      (update :cur conj line)))))
            {:out [] :cur [] :start 1 :fence nil}
            (map-indexed (fn [i line] [(inc i) line]) (split-lines* text)))]
    (cond-> (:out st)
      (seq (:cur st)) (conj [(:start st) (str/join "\n" (:cur st))]))))

(defn- backtick-runs
  "`[[offset width] …]` for every maximal run of backticks."
  [^String s]
  (let [m (re-matcher #"`+" s)]
    (loop [acc []]
      (if (.find m)
        (recur (conj acc [(.start m) (- (.end m) (.start m))]))
        acc))))

(defn- scan-spans
  "Return `[spans unmatched]`. A span is `[content-start content-end width]`; an
  unmatched run is `[offset width]`."
  [^String text]
  (let [runs (backtick-runs text)
        n (count runs)]
    (loop [i 0 spans [] unmatched []]
      (if (>= i n)
        [spans unmatched]
        (let [[pos width] (nth runs i)
              j (loop [j (inc i)]
                  (if (or (>= j n) (= width (second (nth runs j)))) j (recur (inc j))))]
          (if (< j n)
            (recur (inc j) (conj spans [(+ pos width) (first (nth runs j)) width]) unmatched)
            (recur (inc i) spans (conj unmatched [pos width]))))))))

;; ── CHECK: tier / frontmatter shape ─────────────────────────────────────────

(def ^:private load-test-re #"(?U)^<!--\s*LOAD-TEST:\s*(\S+)\s*-->\s*$")
(def ^:private scope-prose-re #"(?U)^\s*\*\*Scope:\*\*")
(def ^:private kebab-re #"^[a-z0-9]+(?:-[a-z0-9]+)*$")
(def ^:private match-all-globs #{"**" "**/*" "*" "**/**"})

(defn- paths-findings
  "`paths:` shape and liveness."
  [path data alive?]
  (let [value (get data "paths")]
    (cond
      (and (string? value) (not= "" value))
      [(finding "paths-not-list" path 1
                (str "`paths:` must be a YAML list of globs; got the bare scalar "
                     (q value)))]

      (and (vector? value) (empty? value))
      [(finding "paths-not-list" path 1
                (str "`paths:` is present but declares no globs — an empty scope "
                     "loads nowhere"))]

      (vector? value)
      (vec (mapcat
            (fn [pattern]
              (cond
                (str/blank? pattern)
                [(finding "paths-not-list" path 1 "`paths:` entry is blank")]

                (contains? match-all-globs pattern)
                [(finding "paths-match-all" path 1
                          (format (str "`paths: %s` matches everything — that IS an "
                                       "unscoped rule; omit `paths:` instead "
                                       "(claude-md-policy.md, Discouraged)")
                                  pattern))]

                (not (alive? pattern))
                [(finding "paths-glob-dead" path 1
                          (str "glob " (q pattern) " matches ZERO tracked files — "
                               "the rule can never load"))]

                :else []))
            value))

      :else [])))

(defn- sentinel-findings
  "The `<!-- LOAD-TEST: … -->` sentinel: present on a path-scoped rule, and naming
  THIS file.

  The name check is ANCHORED, not a `search` over the raw line. The sentinel is its
  own line; one quoted INSIDE a code span is prose ABOUT the sentinel, not one.
  claude-md-policy.md does exactly that when it states the rule, and an unanchored
  scan reports it as a mismatched sentinel — measured. A genuinely copied sentinel
  is still an anchored line, so nothing real escapes."
  [kind path body body-line scoped expected]
  (concat
   (when (and (= "rule" kind) scoped)
     (let [first' (first (for [idx (range (dec body-line) (count body))
                               :when (not= "" (str/trim (nth body idx)))]
                           [(inc idx) (nth body idx)]))]
       (when (nil? (some->> first' second (re-matches load-test-re)))
         [(finding "load-test-missing" path (if first' (first first') 1)
                   (format (str "path-scoped rule must embed `<!-- LOAD-TEST: %s "
                                "-->` immediately after the frontmatter, so loading "
                                "is smoke-testable; found %s")
                           expected
                           (q (if first' (clip (second first') 50) "<eof>"))))])))
   (for [[idx line] (map-indexed (fn [i line] [(inc i) line]) body)
         :let [m (re-matches load-test-re (str/trim line))]
         :when (and m (not= (second m) expected))]
     (finding "load-test-name-mismatch" path idx
              (str "sentinel names " (q (second m)) " but this file's rule name is "
                   (q expected) " — a copied sentinel answers the smoke test for "
                   "the wrong rule")))))

(defn- tier-key-findings
  "Per-tier frontmatter keys: a skill has no `model:` and must carry a
  `description:`; an agent or command names a stable model ALIAS."
  [kind path status data]
  (concat
   (when (and (= "skill" kind) (= "ok" status))
     (concat
      (when (contains? data "model")
        [(finding "skill-model-key" path 1
                  (str "this repo pins the model TIER in the agent, not the skill, so "
                       "the two cannot disagree — move it to the agent. `model:` IS a "
                       "documented skill key; this is a declared policy, not a "
                       "capability claim"))])
      (let [desc (get data "description")]
        (when-not (and (string? desc) (not (str/blank? desc)))
          [(finding "skill-missing-description" path 1
                    (str "a skill's `description:` is the only part that always "
                         "loads; without it the skill is invisible in the listing"))]))))
   (when (and (contains? #{"agent" "command"} kind) (= "ok" status))
     (let [model (get data "model")]
       (when (and (string? model) (re-find #"(?U)\d" model))
         [(finding "model-pinned-version" path 1
                   (format (str "`model: %s` looks like a pinned version; use the "
                                "stable ALIAS (sonnet, opus, …) — a version is the "
                                "drift-prone form")
                           model))])))))

(defn- one-subject-findings
  "Every tier clause for one `[kind path]`. Returns `[findings parsed?]`."
  [kind path text alive?]
  (let [{:keys [status data body-line unparsed]} (parse-frontmatter text)]
    (if (= "unterminated" status)
      [[(finding "fm-unterminated" path 1
                 (str "frontmatter opens with `---` and never closes; the harness "
                      "reads the whole file as YAML and every other clause here is "
                      "blind to it"))]
       false]
      (let [body (split-lines* text)
            scoped (boolean (and (= "ok" status)
                                 (vector? (get data "paths"))
                                 (seq (get data "paths"))))
            expected (if (= "skill" kind)
                       (basename (dirname path))
                       (let [b (basename path)] (subs b 0 (- (count b) 3))))]
        [(concat
          (for [[lineno raw] unparsed]
            (finding "fm-unparsed" path lineno
                     (str "frontmatter line outside the supported subset (key: "
                          "value / key: + `  - item` / blank / #comment): "
                          (q (clip raw 60)) " — UNJUDGED, not accepted")))
          (when (and (= "ok" status) (contains? data "paths"))
            (paths-findings path data alive?))
          (sentinel-findings kind path body body-line scoped expected)
          (when scoped
            (for [[idx line] (map-indexed (fn [i line] [(inc i) line]) body)
                  :when (re-find scope-prose-re line)]
              (finding "scope-prose-with-paths" path idx
                       (str "a prose `**Scope:**` block AND `paths:` frontmatter — "
                            "redundant; the frontmatter IS the scope"))))
          (tier-key-findings kind path status data)
          (when-not (re-matches kebab-re expected)
            [(finding "non-kebab-name" path 1
                      (format "%s name %s is not kebab-case `<concept>`"
                              kind (q expected)))]))
         (= "ok" status)]))))

(defn- check-tier
  "Every frontmatter/naming clause over rules, skills, agents and commands.

  Returns `[findings frontmatter-blocks-parsed]`. Glob liveness is memoized: the
  same `paths:` pattern recurs across files and each probe is a scan of the whole
  tracked universe."
  [subjects texts tracked]
  (let [alive? (memoize (fn [pattern]
                          (let [rx (glob-re pattern)]
                            (boolean (some #(re-matches rx %) tracked)))))]
    (reduce
     (fn [[fs parsed] [kind path]]
       (if-let [text (get texts path)]
         (let [[more ok?] (one-subject-findings kind path text alive?)]
           [(into fs more) (cond-> parsed ok? inc)])
         [fs parsed]))
     [[] 0]
     (for [kind ["agent" "command" "rule" "skill"]
           path (get subjects kind)]
       [kind path]))))

;; ── CHECK: text integrity ───────────────────────────────────────────────────
;; NO HOST-PATH CLAUSE LIVES HERE, and that is a decision rather than a gap.
;; `lint.mk`'s `lint-no-host-paths` (tools/lint/no_host_paths.sh) already owns the
;; operator-home leak ban, over the WHOLE TREE rather than markdown alone, with its
;; own canary suite and its own dispositions. A second implementation of one rule
;; is two sources free to disagree about the same file — the defect
;; `.claude/rules/claude-md-policy.md` names when it says a literal beside a live
;; source "is not a cache of it, it is a second silently divergent source". Point
;; at the owner; do not copy it.
;;
;; The fold is a defect when markdown's newline-to-space conversion inserts a space
;; INSIDE a token. The tell is a joiner (`.` `/` `_`) on either side of the fold
;; with no whitespace. A fold between two bare word characters is undecidable from
;; the text alone and is a stated residual, not a silent pass.
;;
;; `(?U)` is load-bearing: Java's `\s` is ASCII-only by default while the python
;; `re` this was ported from is unicode-aware, so without it a NO-BREAK SPACE
;; beside a fold would count as a non-space character and manufacture a finding.
(def ^:private fold-joiner-re #"(?U)([^\s\\])\n([^\s\\])")

(def ^:private joiner-chars #{\. \_ \/})

(defn- joiner?
  "True when `s` starts with `.`, `_` or `/` — a character that JOINS an identifier
  rather than separating words.

  Used to decide whether a line fold landed mid-identifier. A wrap after `devcards`
  and before `.overlap` renders as a broken symbol and loses the grep, which is the
  defect this feeds; a wrap between two ordinary words is fine."
  [^String s]
  (contains? joiner-chars (.charAt s 0)))

(defn- unmatched-findings
  "A backtick run with no matching close in its block."
  [path start block unmatched]
  (let [lines (split-lines* block)]
    (for [[pos width] unmatched
          :let [offset (nl-before block pos)]]
      (finding "backtick-unmatched" path (+ start offset)
               (format (str "run of %d backtick(s) with no matching close in this "
                            "block — renders as a literal backtick, and any span it "
                            "was meant to open is lost: %s")
                       width (q (clip (str/trim (nth lines offset)) 70)))))))

(defn- fold-findings
  "A code span folded across a line break beside a `.` `_` or `/` joiner."
  [path start block spans]
  (for [[begin end _] spans
        :let [content (subs block begin end)
              m (when (str/includes? content "\n")
                  (re-find fold-joiner-re content))]
        :when (and m (or (joiner? (nth m 1)) (joiner? (nth m 2))))]
    (finding "code-span-folded-at-joiner" path (+ start (nl-before block begin))
             (format (str "code span folded across a line break next to a %s/%s "
                          "joiner: markdown turns the newline into a SPACE, so this "
                          "renders a broken symbol and loses the grep — %s")
                     (q (nth m 1)) (q (nth m 2))
                     (q (clip (str/replace content "\n" "\\n") 70))))))

(defn- check-text
  "Backtick-run matching and mid-identifier folds. Returns `[findings spans-seen]`."
  [paths texts]
  (reduce
   (fn [acc path]
     (if-let [text (get texts path)]
       (reduce
        (fn [[fs seen] [start block]]
          (let [[spans unmatched] (scan-spans block)]
            [(-> fs
                 (into (unmatched-findings path start block unmatched))
                 (into (fold-findings path start block spans)))
             (+ seen (count spans))]))
        acc
        (blocks text))
       acc))
   [[] 0]
   paths))

;; ── CHECK: path citations ───────────────────────────────────────────────────

(def ^:private cite-span-re
  "A backticked path citation. The internal `/` before the final segment is
  MANDATORY, and that is a measured precision decision rather than an oversight.

  WHAT IT DECLINES TO SEE, said out loud because it is a large set: a SLASH-FREE
  filename citation is never offered to the resolver at all — `` `lint.mk` ``
  (19 occurrences), `` `CLAUDE.md` `` (24), `` `wasm.mk` `` (16) and a long tail
  besides. So if one of those were renamed or deleted, this check would report the
  corpus clean. That is a real blind spot and it is accepted knowingly.

  WHY WIDENING WAS REFUSED, with the count. Making the directory prefix optional was
  tried and measured: 57 findings, and EVERY ONE is a false positive or correct
  prose. Four distinct classes, none fixable by a narrower pattern:

    - a NAMESPACE that looks like a file — `clojure.edn` is `clojure.edn` the
      reader namespace, cited constantly and tracked nowhere;
    - an EXTENSION FRAGMENT — `.pb.h`, `.pb.c`, `.summary.md` in prose about a
      file TYPE, where the optional prefix lets the leading dot pass as a name;
    - an ILLUSTRATIVE name in a naming-convention example — `code-quality.md` and
      `code-quality-c.md` in `.claude/rules/claude-md-policy.md` name no file and
      are not meant to;
    - a BARE BUILD ARTIFACT — `controls.wasm` (28) and `reference.wasm` (8). These
      exist only at gitignored paths, so the resolver's `gitignored` clause cannot
      reach them from a bare name, and the prose is correct as written.

  And the sharpest case against widening: `` `fb_hash_probe.rs` `` in
  renderer/wasm_harness/README.md is cited BY a sentence recording that the file is
  gone — \"WAS LISTED HERE and is gone\". A citation whose whole point is that the
  path is absent is one this check must never flag.

  So the honest disposition is DECLINE with the measurement recorded, not a
  baseline of 57 waivers. `.claude/rules/gate-enforcement.md` §1 permits exactly
  three outcomes when a check cannot pass — fix the tree, narrow the declared
  scope, or decline and say why — and this is the third.

  RETIRES WHEN: a bare-filename citation can be told from a namespace, a type
  fragment and an illustrative name. That needs authoring support the corpus does
  not have today, not a better regex."
  #"(?U)`(\.{0,2}/?[A-Za-z0-9_.][A-Za-z0-9_./+-]*/[A-Za-z0-9_.+-]+\.(?:clj|cljc|cljs|edn|py|sh|awk|mk|c|h|json|proto|md|yml|yaml|rs|toml|bb|wasm|pb|ttf|jpg|jpeg|png|txt))(?:[:@][^`\s]*)?`")

(def ^:private cite-dirglob-re #"`(\.{0,2}/?[A-Za-z0-9_][A-Za-z0-9_./+-]*)/\*\*?`")

(def ^:private cite-link-re #"(?U)\[[^\]]*\]\(([^)#\s]+?)(?:#[^)]*)?\)")

(def ^:private link-skip ["http://" "https://" "mailto:" "#" "<"])

(defn- normpath
  "Collapse `.` and `..` the way `posixpath.normpath` does, for a path with no
  leading slash.

  Without it `./README.md` never equals the tracked `README.md` and every relative
  link in the repo reads as dead — measured, six false findings."
  [^String p]
  (if (= "" p)
    "."
    (let [out (reduce (fn [acc c]
                        (cond
                          (or (= c "") (= c ".")) acc
                          (not= c "..") (conj acc c)
                          (or (empty? acc) (= ".." (peek acc))) (conj acc c)
                          :else (pop acc)))
                      []
                      (str/split p #"/" -1))]
      (if (empty? out) "." (str/join "/" out)))))

(defn- strip-slashes
  "Trim every leading and trailing `/` so two spellings of one path compare equal.

  A citation may be written `/renderer/src/` or `renderer/src`; both name the same
  tracked path, and comparing them raw would make the resolver's answer depend on
  the author's punctuation."
  [^String s]
  (-> s (str/replace #"/+$" "") (str/replace #"^/+" "")))

(defn- make-resolver
  "Four ways a citation resolves, in order. The FOURTH is the interesting one.

    root          the literal path is tracked.
    doc-relative  it resolves against the citing document's own directory.
    suffix        some tracked path ENDS with it — the shape a doc uses when it
                  cites relative to a tool root it has already named
                  (`corpus/spec.edn` inside a rule scoped to tools/devcards).
                  Weaker on purpose, and it does not blunt the detection this
                  check exists for: after a rename NO tracked path ends with the
                  old suffix.
    gitignored    the repo ITSELF declares the path a build output. Accepting it is
                  not a suppression invented here — `.gitignore` is the answer.
                  BLINDNESS THIS BUYS, stated rather than hidden: a typo under an
                  ignored PREFIX (`renderer/output/typo.pb`) resolves too. The
                  count and every such citation are printed on every run so the set
                  stays auditable."
  [tracked]
  (let [universe (into (set tracked)
                       (mapcat (fn [path]
                                 (let [parts (str/split path #"/" -1)]
                                   (for [i (range 1 (count parts))]
                                     (str/join "/" (take i parts)))))
                               tracked))
        cache (atom {})
        ignored? (fn [path]
                   (if tracked-from
                     false
                     (if (contains? @cache path)
                       (get @cache path)
                       (let [{:keys [code err]} (run-git ["check-ignore" "-q"
                                                          "--no-index" path])]
                         (when-not (contains? #{0 1} code)
                           (error! (format "`git check-ignore %s` exited %d: %s"
                                           path code err)))
                         (let [v (zero? code)]
                           (swap! cache assoc path v)
                           v)))))]
    (fn [cite docdir]
      (let [bare (normpath (strip-slashes cite))
            rel (if (= "" docdir) bare (normpath (str docdir "/" bare)))
            suffix (str "/" bare)]
        (cond
          (contains? universe bare) "root"
          (contains? universe rel) "doc-relative"
          (some #(str/ends-with? % suffix) universe) "suffix"
          (or (ignored? bare) (and (not= rel bare) (ignored? rel))) "gitignored"
          :else nil)))))

(defn- line-candidates
  "Every citation a line offers, as `[cite clause]`, in the order the python gate
  collected them — backticked paths, backticked directory globs, then links."
  [line]
  (concat
   (for [m (re-seq cite-span-re line)] [(second m) "dead-path-citation"])
   (for [m (re-seq cite-dirglob-re line)] [(second m) "dead-path-citation"])
   (for [m (re-seq cite-link-re line)
         :let [target (second m)]
         :when (not (some #(str/starts-with? target %) link-skip))]
     [target "dead-markdown-link"])))

(defn- check-citations
  "Every backticked path, directory glob and markdown link.

  Returns `[findings seen gitignored]`, where `gitignored` is the auditable set of
  citations that resolved ONLY because this repo declares them build outputs."
  [paths texts resolve']
  (reduce
   (fn [acc path]
     (if-let [text (get texts path)]
       (let [docdir (dirname path)]
         (reduce
          (fn [[fs seen ignored] [idx line]]
            (reduce
             (fn [[fs seen ignored] [cite check]]
               (case (resolve' cite docdir)
                 nil [(conj fs (finding check path idx
                                        (str (q cite) " resolves nowhere: not tracked "
                                             "at the repo root, not relative to this "
                                             "document, no tracked path ends with it, "
                                             "and this repo does not declare it a "
                                             "build output")))
                      (inc seen) ignored]
                 "gitignored" [fs (inc seen) (conj ignored [path idx cite])]
                 [fs (inc seen) ignored]))
             [fs seen ignored]
             (line-candidates line)))
          acc
          (map-indexed (fn [i line] [(inc i) line]) (split-lines* text))))
       acc))
   [[] 0 []]
   paths))

;; ── Exemptions ──────────────────────────────────────────────────────────────

(defn- read-exemptions
  "Read the EDN allowlist, or refuse. An absent allowlist is indistinguishable
  from an empty one only until the first entry is needed."
  []
  (let [full (or (env "MD_GATE_EXEMPTIONS") (str (io/file repo exemptions-path)))
        f (io/file full)]
    (when-not (.exists f)
      (error! (format (str "%s is missing. An absent allowlist is indistinguishable "
                           "from an empty one only until the first entry is needed; "
                           "this gate refuses to guess.")
                      exemptions-path)))
    (let [doc (try
                (edn/read-string (slurp f :encoding "UTF-8"))
                (catch Exception e
                  (error! (format "%s is unreadable: %s" exemptions-path
                                  (.getMessage e)))))
          entries (:exemptions doc)]
      (when-not (sequential? entries)
        (error! (format "%s: `:exemptions` must be a list" exemptions-path)))
      (vec entries))))

(defn- validate-exemptions!
  "Every entry carries its proof, and the proof is checked against `today`.

  `today` is a PARAMETER, never `LocalDate/now` read inside: a clause whose clock
  cannot be pinned is a clause whose canary cannot be made to go red on purpose."
  [entries ^LocalDate today]
  (let [horizon (.plusDays today horizon-days)]
    (doseq [[i entry] (map-indexed vector entries)]
      (let [where (format "%s[%d]" exemptions-path i)]
        (when-not (map? entry)
          (error! (format "%s: entry is not a map" where)))
        (doseq [k [:check :file]]
          (when-not (and (string? (get entry k)) (not (str/blank? (get entry k))))
            (error! (format "%s: `%s` must be a non-blank string" where k))))
        (doseq [k proof-keys]
          (when-not (and (string? (get entry k)) (not (str/blank? (get entry k))))
            (error! (format (str "%s: `%s` must be a non-blank string. An exemption "
                                 "is a WAIVER, not a disabled rule: all of %s are "
                                 "mandatory.")
                            where k (str/join ", " proof-keys)))))
        (let [expires (try
                        (LocalDate/parse (:expires entry))
                        (catch DateTimeParseException _
                          (error! (format (str "%s: `:expires` must be an ISO-8601 "
                                               "date (YYYY-MM-DD), got %s")
                                          where (q (:expires entry))))))]
          ;; Two separate clauses so neither masks the other.
          (when (.isBefore ^LocalDate expires today)
            (error! (format (str "%s: EXPIRED on %s — an exemption cannot outlive "
                                 "its own decision. Re-take the decision or fix the "
                                 "finding.")
                            where (str expires))))
          (when (.isAfter ^LocalDate expires horizon)
            (error! (format (str "%s: `:expires` %s is beyond the %d-day horizon "
                                 "(%s) — an exemption written never to lapse is a "
                                 "disabled rule.")
                            where (str expires) horizon-days (str horizon)))))))
    entries))

(defn- excuses?
  "True when exemption `entry` waives finding `f`.

  ALL THREE CLAUSES MUST HOLD, and each narrows a different axis: the `:check` must
  be the same rule, the `:file` GLOB must match the finding's path, and `:match` — if
  given — must appear in the detail text. Dropping any one widens an entry into a
  broader skip than its author wrote: `:check` alone would waive a rule everywhere,
  `:file` alone would waive every rule in a file.

  `:match` is optional ON PURPOSE, so a file-and-rule waiver stays expressible; nil
  and `\"\"` are both treated as absent, since an empty string would otherwise match
  every detail and read as if it constrained something."
  [entry f]
  (and (= (:check entry) (:check f))
       (some? (re-matches (glob-re (:file entry)) (:path f)))
       (let [needle (:match entry)]
         (or (nil? needle) (= "" needle) (str/includes? (:detail f) needle)))))

(defn- apply-exemptions
  "Return `[kept stale]`. An entry matching no live finding is STALE and is itself
  a failure, so the allowlist can only shrink."
  [findings entries]
  (loop [fs (seq findings) kept [] used #{}]
    (if-let [f (first fs)]
      (if-let [i (first (keep-indexed (fn [i e] (when (excuses? e f) i)) entries))]
        (recur (next fs) kept (conj used i))
        (recur (next fs) (conj kept f) used))
      [kept (keep-indexed (fn [i e] (when-not (contains? used i) e)) entries)])))

;; ── Report ──────────────────────────────────────────────────────────────────

(def ^:private not-covered
  "NOT COVERED by this gate, stated on every run so a green is not read as more
than it is:
  * The \"previously / used to / renamed / supersedes\" half of
    claude-md-policy.md's no-historical-narrative ban. Its own DELETION TEST is a
    judgement, so a regex cannot apply it; measured findings and the argument are
    in tools/lint/md/PORT_REPORT.md.
  * Generated and vendored markdown (see the exclude-* tables above) — a finding
    there is fixed in the generator or the upstream, never in the page.
  * A code span folded between two bare word characters. Markdown turns the
    newline into a space, which is CORRECT whenever the intended content has a
    space there; the two cases are indistinguishable from the text alone.
  * `paths:` glob ANCHORING and one-rule-one-scope-theme. Both are judgements,
    and the policy's own `**/*.md` example fails a literal anchoring test.
  * That a `paths:` glob matches the RIGHT files. Liveness is a floor of one.
  * Whether an `owner` names a real person: the check is `non-blank string`.
  * Operator-home absolute paths. NOT unchecked — `lint.mk`'s
    `lint-no-host-paths` owns that ban over the whole tree, so a clause here
    would be a second source free to disagree with it.")

(defn- read-text
  "Decode one file STRICTLY as UTF-8, or THROW.

  `(String. bytes \"UTF-8\")` substitutes U+FFFD for malformed input and would
  report a mojibake file as clean, so the decoder is configured to REPORT — that
  refusal is what the `unreadable-file` clause turns into a finding rather than a
  skip. The local is `decoder` and not `dec`, which would shadow `clojure.core/dec`
  — a shadow clj-kondo blocks on, and the `.claude/rules/lint-gates.md` trap where
  a missed reference silently resolves to the core var instead."
  [^File f]
  (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (str (.decode decoder (ByteBuffer/wrap (Files/readAllBytes (.toPath f)))))))

(defn- load-texts
  "Read every subject file. Returns `[texts findings]` — an undecodable or missing
  file becomes an `unreadable-file` FINDING, never a silent omission."
  [paths]
  (reduce
   (fn [[texts fs] path]
     (try
       [(assoc texts path (read-text (io/file repo path))) fs]
       (catch Exception e
         [texts (conj fs (finding "unreadable-file" path 1
                                  (format (str "could not be read as UTF-8 (%s) — "
                                               "UNJUDGED by every check below, which "
                                               "is a finding and not a skip")
                                          (str e))))])))
   [{} []]
   paths))

(defn- assert-floor!
  "NON-VACUITY GUARD — refuse when discovery of `label` came back EMPTY.

  For every check here the clean output and the nothing-was-discovered output are
  the same: no findings. So an empty input set buys a green tick over zero coverage,
  which is the worst thing a gate can print. This repo tracks markdown, rules,
  skills and citations, so zero of any of them means DISCOVERY BROKE — a moved tree,
  a wrong glob, a checkout git cannot resolve."
  [label found]
  (when (empty? found)
    (error! (format (str "discovered ZERO %s. This repo tracks them, so an empty "
                         "set means DISCOVERY broke, not that there is nothing to "
                         "check.")
                    label))))

(defn- assert-extractor!
  "EXTRACTOR FLOORS. A regex that stops matching prints nothing and exits 0 —
  reproducing, one level up, the silence these checks exist to break."
  [n label files]
  (when (zero? (long n))
    (error! (format (str "extractor found ZERO %s across %d file(s). The pattern "
                         "stopped matching; nothing was asserted.")
                    label files))))

(defn- print-summary!
  "Print the TOTALITY line: what was discovered, and therefore what was judged.

  Reported on every run, clean or not. A gate that printed only its findings would
  let the unjudged remainder grow silently — a narrowing glob or a newly-excluded
  tree would show up as fewer findings, which reads exactly like an improving tree."
  [{:keys [hand all-md excluded subjects parsed spans cites ignored]}]
  (println (format (str "%s[md-gate]%s %d hand-authored .md (%d tracked, %d "
                        "generated/vendored excluded); %d rules, %d skills, %d "
                        "agents, %d commands; %d frontmatter blocks, %d code "
                        "spans, %d citations")
                   green off (count hand) (count all-md) (count excluded)
                   (count (get subjects "rule")) (count (get subjects "skill"))
                   (count (get subjects "agent")) (count (get subjects "command"))
                   parsed spans cites))
  (when (seq ignored)
    (println (format (str "%s[md-gate]%s %d citation(s) resolved ONLY because this "
                          "repo git-ignores them (build outputs). Not findings, and "
                          "not invisible either:")
                     yellow off (count ignored)))
    (doseq [[path line cite] ignored]
      (println (format "    %s:%d  %s" path line cite))))
  (println not-covered))

(defn- report-stale!
  "Report exemptions that matched no live finding, and say why that is a failure.

  This is the DOWN-ONLY half of the exemption ratchet. Without it an entry outlives
  the finding it excused, so the unchecked surface widens silently and an offender
  can be fixed while its excuse stays behind — which means deleting a waiver is part
  of fixing the thing it waived."
  [stale]
  (binding [*out* *err*]
    (println (format (str "%s[md-gate] ERROR%s — %d STALE exemption(s): each matches "
                          "no live finding, so it excuses nothing while still "
                          "widening the unchecked surface.")
                     red off (count stale)))
    (doseq [entry stale]
      (println (format "    check=%s file=%s match=%s"
                       (:check entry) (:file entry) (or (:match entry) "<any>"))))
    (println (format "  Delete the entry from %s." exemptions-path))))

(defn- report-findings!
  "Report the findings that survived exemption, and how to dispose of each.

  Sorted by check, then path, then line, so a run's output is stable and diffable
  between invocations — an unsorted report makes two identical runs look different.
  The trailing text names the exemption store and its mandatory fields, because the
  two permitted dispositions are FIX and a proof-carrying WAIVER, and a reader who
  cannot find the second will reach for a third."
  [kept]
  (binding [*out* *err*]
    (println (format "%s[md-gate] FAIL%s — %d finding(s):" red off (count kept)))
    (doseq [f (sort-by (juxt :check :path #(or (:line %) 0)) kept)]
      (println (str "  " (finding-line f))))
    (println (format (str "%n  Fix each at its source. To EXEMPT one, add an entry "
                          "to %s carrying all of %s — a waiver, never a disabled "
                          "rule.")
                     exemptions-path (str/join ", " proof-keys)))))

(defn- today*
  "The gate's `today`. Pinned by MD_GATE_TODAY only, and a pinned clock ANNOUNCES
  ITSELF — a seam that can silently move an expiry deadline is the bypass this
  gate refuses everywhere else."
  []
  (if today-override
    (let [d (try
              (LocalDate/parse today-override)
              (catch DateTimeParseException _
                (error! (format "MD_GATE_TODAY=%s is not an ISO-8601 date"
                                today-override))))]
      (binding [*out* *err*]
        (println (str yellow "[md-gate]" off " clock PINNED to " d
                      " by MD_GATE_TODAY — expiry and horizon are NOT judged "
                      "against the real date.")))
      d)
    (LocalDate/now)))

;; ── The check ───────────────────────────────────────────────────────────────

(defn- discover
  "Every input set this gate judges, with its floors already enforced."
  []
  (let [tracked (tracked-paths)]
    (when (empty? tracked)
      (error! (str "`git ls-files` returned NOTHING. This repo tracks thousands of "
                   "files, so an empty set means git cannot resolve this checkout — "
                   "not that there is nothing to check.")))
    (let [all-md (sort (set (concat (paths-matching tracked "**/*.md")
                                    (paths-matching tracked "*.md"))))
          [hand excluded] (partition-md all-md)
          subjects {"rule" (paths-matching tracked rules-glob)
                    "skill" (paths-matching tracked skills-glob)
                    "agent" (paths-matching tracked agents-glob)
                    "command" (paths-matching tracked commands-glob)}]
      ;; NON-VACUITY, per subject class rather than over the union: any populated
      ;; class satisfies a combined floor, so a union floor cannot see one class go
      ;; dark. Each of these exists in this repo and CLAUDE.md depends on it.
      (assert-floor! "hand-authored markdown" hand)
      (doseq [[kind glob] [["rule" rules-glob] ["skill" skills-glob]
                           ["agent" agents-glob] ["command" commands-glob]]]
        (assert-floor! (format "%s files (%s)" kind glob) (get subjects kind)))
      {:tracked tracked :all-md all-md :hand hand :excluded excluded
       :subjects subjects})))

(defn- run-md!
  "The markdown gate. Returns the exit code; never throws for a finding."
  [quiet?]
  (let [{:keys [tracked all-md hand excluded subjects]} (discover)
        [texts read-findings] (load-texts
                               (sort (into (set hand)
                                           (mapcat val subjects))))
        [tier-f parsed] (check-tier subjects texts tracked)
        [text-f spans] (check-text hand texts)
        [cite-f cites ignored] (check-citations hand texts (make-resolver tracked))
        findings (concat read-findings tier-f text-f cite-f)]
    (assert-extractor! parsed "frontmatter blocks parsed" (count hand))
    (assert-extractor! spans "code spans found" (count hand))
    (assert-extractor! cites "path citations/links found" (count hand))
    (let [entries (validate-exemptions! (read-exemptions) (today*))
          [kept stale] (apply-exemptions findings entries)]
      (when-not quiet?
        (print-summary! {:hand hand :all-md all-md :excluded excluded
                         :subjects subjects :parsed parsed :spans spans
                         :cites cites :ignored ignored}))
      (cond
        (seq stale) (do (report-stale! stale) exit-error)
        (seq kept) (do (report-findings! kept) exit-findings)
        :else (do (when-not quiet?
                    (println (format "%s[md-gate]%s clean over %d file(s), %d exemption(s) live"
                                     green off (count hand) (count entries))))
                  exit-ok)))))

;; ── Entry points ────────────────────────────────────────────────────────────

(def ^:private checks
  "Check name -> implementation. One entry today; the dispatch MIRRORS
  `lint-gate.core`'s so a second markdown check is a map entry rather than a
  rewrite of `-main`."
  {"md" run-md!})

(defn- exit-guarded!
  "Run `thunk` and exit with its code. A CRASH exits 2, never 1.

  The JVM exits 1 on an uncaught exception, and 1 is this gate's FINDINGS code —
  so without this trap a crash and a verdict about the markdown are
  indistinguishable from outside, and `the gate went red` would prove nothing.
  THIS IS NOT MASKING: the stack trace is printed in full and the exit code still
  BLOCKS; it is re-labelled from `verdict about the tree` to `this gate is
  broken`, which is the more accurate of the two."
  [thunk]
  (try
    (exit! (thunk))
    (catch Throwable t
      (.printStackTrace t)
      (binding [*out* *err*]
        (println (str red "[md-gate] ERROR" off
                      " — the gate itself crashed (stack trace above)."))
        (println (str "  Reported as exit 2, not 1 — an internal error is not a "
                      "finding about the markdown, and conflating the two makes "
                      "every red ambiguous.")))
      (exit! exit-error))))

(defn gate
  "`-X` entry point: `clojure -X:lint-gate lint-gate.md/gate [:quiet true]`.

  `-X` rather than `-M` because the `:lint-gate` alias pins `:main-opts` to
  `lint-gate.core` and the CLI CONCATENATES command-line main-opts onto the
  alias's rather than overriding them — measured: `clojure -M:lint-gate -m
  lint-gate.md` reaches `lint-gate.core/-main` with `[\"-m\" \"lint-gate.md\"]` and
  is refused as a usage error. `-X` ignores `:main-opts` entirely, so one alias —
  one home for the source path, which is what keeps this file inside
  `LINT_CLJ_PATHS` — dispatches both gates.

  FOLDING THIS INTO `lint-gate.core`'s `checks` MAP IS NOT A DROP-IN, and the three
  reasons are worth writing down rather than discovering: that dispatch refuses an
  EMPTY path list (discovery is its caller's job, and this gate's discovery is
  `git ls-files`, so it takes no paths); it exits 3 where this gate's oracle asserts
  2; and its exemptions are ONE file keyed by check name, where this gate's live
  entry and every canary fixture read a store of their own. Each is a decision, not
  an oversight — but a merge owes all three, plus a re-mint of whichever suite's
  exit-code assertions move."
  [opts]
  (exit-guarded! #(run-md! (boolean (:quiet opts)))))

(defn -main
  "`clojure.main` entry: `-m lint-gate.md --check md [--quiet]`.

  The canary suite drives THIS one, because it runs a mutated COPY of the source
  tree off an explicit `-cp` and so cannot go through the alias at all. An UNKNOWN
  check name is an ERROR rather than a silent no-op, because a typo'd check name
  that exits 0 is a gate someone believes is armed."
  [& args]
  (exit-guarded!
   (fn []
     (let [[flag nm & more] args]
       (when-not (= "--check" flag)
         (error! "usage: --check <name> [--quiet]"))
       (when-not (contains? checks nm)
         (error! (str "unknown check " (q nm) ". known: "
                      (str/join ", " (sort (keys checks))))))
       ((get checks nm) (boolean (some #{"--quiet"} more)))))))
