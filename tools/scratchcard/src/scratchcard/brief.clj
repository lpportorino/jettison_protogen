(ns scratchcard.brief
  "GENERATES `.claude/rules/scratch-devcard-api.md` from the live code.

  WHY GENERATED RATHER THAN WRITTEN. A hand-maintained API page drifts the
  moment a threshold moves or an op gains an argument, and it drifts SILENTLY:
  nothing in this repo can tell a stale page from a fresh one by reading it.
  Everything below is read from the running namespaces, so the page cannot
  describe a tool that no longer exists.

  This is `devcards.standard-brief`'s pattern, deliberately: that generator
  emits the VLM briefing from the live contract and `renderer.mk` then gates
  its freshness with a regenerate-and-diff. Same shape, same gate.

  EVERY SECTION HAS A NON-VACUITY FLOOR. A generator whose source var was
  renamed would otherwise emit a confident EMPTY section, and an empty section
  reads as \"this tool has no error codes\" rather than as \"the generator
  broke\". `emit!` refuses to write when any floor is unmet."
  (:require
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [malli.core :as m]
   [scratchcard.lanes :as lanes]
   [scratchcard.manifest :as manifest]
   [scratchcard.matrix :as matrix]
   [scratchcard.protocol :as protocol]
   [scratchcard.runs :as runs]))

(def output-path
  "Repo-relative. TRACKED, because `git diff` says nothing about an untracked
  path and the freshness gate would then pass green over a page nothing has
  ever received."
  ".claude/rules/scratch-devcard-api.md")

(defn- code-block [lang s] (str "```" lang "\n" (str/trimr s) "\n```"))

(defn- pp-str [x] (with-out-str (pp/pprint x)))

(defn- md-table
  [headers rows]
  (str/join "\n"
            (concat [(str "| " (str/join " | " headers) " |")
                     (str "|" (str/join "|" (repeat (count headers) "---")) "|")]
                    (for [r rows]
                      (str "| " (str/join " | " (map #(str/replace (str %) "|" "\\|") r)) " |")))))

;; ── sections, each returning [text floor-count] ────────────────────────────

(defn- section-ops
  []
  (let [rows (for [[op args] (sort-by key protocol/ops)]
               [(str "`" op "`")
                (if (seq args)
                  (str/join ", " (map #(str "`" (name %) "`") (sort args)))
                  "_none_")])]
    [(str "## Ops\n\nEvery op the daemon answers. Arg sets are CLOSED: an "
          "argument not listed here is REFUSED with `UNKNOWN_ARGUMENT` and the "
          "accepted set named, never silently dropped.\n\n"
          (md-table ["op" "accepted args"] rows))
     (count rows)]))

(defn- section-errors
  []
  (let [groups [["input (the screen file)" protocol/input-errors]
                ["render" protocol/render-errors]
                ["protocol (the request itself)" protocol/protocol-errors]
                ["client-side (never sent by the daemon)" protocol/client-errors]]
        rows (for [[label codes] groups
                   c (sort codes)]
               [label (str "`" c "`")])]
    [(str "## Error codes\n\nTyped, and CATEGORISED — a client-side code that "
          "collided with a daemon-side one would make a transport failure "
          "indistinguishable from a render failure.\n\n"
          (md-table ["category" "code"] rows))
     (count rows)]))

(defn- section-manifest
  []
  [(str "## The run manifest — full schema\n\nWritten to `manifest.edn` in every "
        "run directory, VALIDATED on write and on read. The map is CLOSED: an "
        "unknown key is refused with the accepted set named.\n\n"
        ;; `m/form`, not the raw vector: a bare pprint renders every predicate
        ;; as `#object[clojure.core$pos_int_QMARK_ 0x…]`, which is both
        ;; unreadable AND unstable — the hex identity changes per JVM, so the
        ;; freshness gate would red on every regeneration for no reason.
        (code-block "clojure" (pp-str (m/form manifest/schema))))
   (count manifest/schema)])

(defn- section-lanes
  []
  (let [d (lanes/descriptor)]
    [(str "## What judges a render\n\nRead from `devcards.lanes` — this tool does "
          "NOT define its own quality rules.\n\n"
          "- armed producers: "
          (str/join ", " (map #(str "`" % "`") (:producers d))) "\n"
          "- thresholds: `" (pr-str (:thresholds d)) "`\n"
          "- caps: `" (pr-str (:caps d)) "`\n"
          "- DECLINED (named so silence is never mistaken for coverage): "
          (str/join ", " (map #(str "`" % "`") (:declined d))) "\n")
     (count (:producers d))]))

(defn- section-matrix
  []
  (let [rows (for [{:keys [w h note]} matrix/default-resolutions]
               [(str w "x" h) (matrix/disp-tier w h) (or note "")])]
    [(str "## The default matrix\n\n"
          (count matrix/families) " theme families x " (count matrix/modes)
          " modes x the canvases below = **" (matrix/expected-count {})
          " cells**. The caller may override any axis.\n\n"
          (md-table ["canvas" "LVGL display tier" "note"] rows)
          "\n\nThe tier is a SILENT CLIFF: LVGL picks radius and padding from "
          "the greater axis (`<= " matrix/disp-small-max "` SMALL, `< "
          matrix/disp-large-min "` MEDIUM, else LARGE), so crossing a threshold "
          "changes geometry with no error. It is recorded per cell for that "
          "reason.")
     (count rows)]))

(defn- section-layout
  []
  [(str "## A run directory\n\n"
        (code-block "text"
                    (str ".protogen/scratch/<card>/\n"
                         "  " runs/roster-name "        append-only roster, one EDN map per line\n"
                         "  latest -> runs/NNNN-…\n"
                         "  runs/NNNN-<utc>/\n"
                         "    " manifest/manifest-name "     the machine-readable index — read this to diff\n"
                         "    report.md        the digest: verdict, grouped findings, what was NOT judged\n"
                         "    findings.edn     the FULL findings vector\n"
                         "    input/screen.edn a verbatim copy of what was rendered\n"
                         "    input/screen.pb  the bytes the renderer consumed\n"
                         "    renders/<cell>.png and <cell>.dump.json\n")))
   1])

(defn page
  "The generated page, or nil when any section is vacuous."
  []
  (let [sections [(section-ops) (section-errors) (section-manifest)
                  (section-lanes) (section-matrix) (section-layout)]]
    (when (every? (fn [[_ n]] (pos? (long n))) sections)
      (str "---\n"
           "description: The scratch-devcard tool's live API — ops, typed error codes, the full run-manifest schema, the armed quality lanes and the default render matrix. GENERATED from the code, so it cannot drift. Loads when editing tools/scratchcard or a scratch screen.\n"
           "paths:\n"
           "  - \"tools/scratchcard/**\"\n"
           "---\n"
           "<!-- LOAD-TEST: scratch-devcard-api -->\n"
           "<!-- GENERATED by scratchcard.brief — DO NOT EDIT. Run\n"
           "     `make -f renderer.mk scratchcard-brief-generate` and commit. -->\n\n"
           "# scratch-devcard — the live API\n\n"
           "Everything here is READ FROM THE CODE at generation time. If this page\n"
           "and the code disagree, the page is stale and the freshness gate\n"
           "(`make -f renderer.mk scratchcard-brief`) is red.\n\n"
           "The operational rule is `.claude/rules/scratch-devcard.md`; the task\n"
           "playbook is the `scratch-devcard` skill. This page is the reference.\n\n"
           "**A LIMIT WORTH KNOWING.** This rule is scoped to `tools/scratchcard/**`,\n"
           "which covers the tool and the example an author copies. It does NOT\n"
           "auto-load on a screen kept in gitignored scratch space — a `paths:` glob\n"
           "matching zero TRACKED files can never fire, and the markdown gate refuses\n"
           "one rather than let it look like coverage. Keep a screen you are iterating\n"
           "on under `tools/scratchcard/example/`, or invoke the `scratch-devcard`\n"
           "skill explicitly.\n\n"
           (str/join "\n\n" (map first sections))
           "\n"))))

(defn emit!
  "Write the page under `repo-root`. Throws rather than emitting a vacuous one."
  [^String repo-root]
  (if-let [p (page)]
    (let [f (io/file repo-root output-path)]
      (io/make-parents f)
      (spit f p)
      (println (str "scratchcard-brief: wrote " output-path
                    " (" (count p) " bytes)"))
      f)
    (throw (ex-info (str "refusing to write a vacuous " output-path
                         " — a section came back empty, which means a source var"
                         " moved and the generator is reading nothing")
                    {:error "VACUOUS_BRIEF"}))))

(defn -main
  [& [root]]
  (emit! (or root (System/getProperty "user.dir")))
  (shutdown-agents))
