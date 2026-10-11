(ns press.doc
  "Paged documents from data — check, build, figures, print, runbook.

   A document is a directory inside the configured root: `doc.edn` (front matter
   and the house skeleton as data, `press.doc.spec`), `figs/<id>.edn`
   (figure descriptions, judged exactly as `tools/press`'s `desc.py`
   judges them), and what the browser half writes back beside them
   (`figs/<id>.pick.json`, `figs/<id>.svg`, the PDF). This namespace OWNS
   the data and the refusal; the browser half (the `press` launcher, run
   from a HOST shell — it starts a container, which this library never
   does) owns geometry and printing, so every verb that needs it RETURNS
   the host command.

     check       — every structural rule, the figures' freshness, the
                   rendered page's backstop; content violations are reported
     build!      — ONE self-contained, stamped HTML file, or `:doc/refused`
                   with nothing left behind (a previous page of that name
                   is removed)
     fig-json!   — a description's canonical JSON + its problem hash
     fig-solve!  — writes the canonical JSON, returns the layout command
                   (`press fig solve` for a diagram, `press chart` for a chart)
     fig-pick!   — writes the canonical JSON, returns `press fig pick`
     fig-status  — per figure: :current :stale :unpicked :missing-svg :invalid
     pdf         — `press pdf` for the built page — refused unless the page's
                   stamp matches the files it was built from
     pdf-report  — the print's report read STRICTLY into a verdict
     runbook!    — `<doc>/RUNBOOK.md`: this document's commands, in order,
                   with root-relative paths only
     example     — a small complete document as data

   Paths: every path argument is absolute or relative to the configured
   ROOT (`press.config` — by default the repository root, never the JVM's
   working directory). Host commands come back with root-relative paths,
   to run from the root, and start with the configured launcher; build
   output goes under `<build-dir>/<doc-name>/` (by default
   `<root>/.press/doc-build/<doc-name>/`). A consumer with another layout
   wraps each call in `press.config/with-config`.
   `press fig` exit codes: 0 ok · 1 no candidate passed / checker defects ·
   2 description changed since the solve or pick · 3 pick record does not
   match · 4 unsatisfiable (`explain.json`) · 5 over the page budget
   (`budget.json`) · 6 invalid input · 7 a defect in `press` ITSELF — a tool
   failure to report, never a property of the description."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [press.config :as config]
            [press.doc.desc :as desc]
            [press.doc.figure :as fig]
            [press.doc.image :as image]
            [press.doc.io :as dio]
            [press.doc.render :as render]
            [press.doc.report :as report]
            [press.doc.rules :as rules]
            [press.doc.spec :as spec]
            [press.doc.svg :as svg])
  (:import [java.io File]
           [java.nio.file Files]))

(set! *warn-on-reflection* true)

(def ^:private file? dio/file?)

;; ------------------------------------------------------------------ check

(defn- malformed
  "One violation per place the document breaks `doc-spec`."
  [doc]
  (let [errs (:errors (m/explain spec/doc-spec doc))]
    (vec (for [[in es] (group-by :in errs)
               :let [e (first es)]]
           {:rule :doc/malformed :at (pr-str in)
            :message (str "at " (pr-str in) ": " (me/error-message e)
                          " — got " (let [v (pr-str (:value e))] (if (> (count v) 90) (str (subs v 0 90) "…") v)))}))))

(m/=> malformed [:=> [:cat spec/anything] [:vector spec/violation]])

(defn- svg-problem
  "Why figure `id`'s SVG cannot go on the page, or nil."
  [^File d id hs]
  (try (svg/inline (slurp (fig/fig-file (str d) id ".svg")) "f0-" hs) nil
       (catch clojure.lang.ExceptionInfo e (ex-message e))))

(m/=> svg-problem [:=> [:cat file? spec/ne-string desc/house-spec] [:maybe spec/ne-string]])

(defn- figure-pass
  "For every placed figure: a violation unless it is current and its SVG
   passes the allowlist, and its SVG text when it does."
  [^File d doc hs]
  (let [refs (rules/fig-refs doc)
        verdict (fn [id] (let [{:keys [state reason]} (fig/status (str d) id hs)]
                           (cond (not= :current state) [:doc/fig-not-current (str (name state) ": " reason)]
                                 :else (some->> (svg-problem d id hs) (vector :doc/bad-svg)))))
        vs (into {} (for [id (distinct (map second refs))] [id (verdict id)]))]
    {:figs (into {} (for [[id v] vs] [id (when-not v (slurp (fig/fig-file (str d) id ".svg")))]))
     :violations (vec (for [[at id] refs :let [[rule why] (vs id)] :when rule]
                        {:rule rule :at at :message (str "figure " id " — " why)}))}))

(m/=> figure-pass [:=> [:cat file? spec/edn-map desc/house-spec]
                   [:map [:figs render/figs-arg] [:violations [:vector spec/violation]]]])

(def check-result
  "`check`'s verdict. READ `:ok?` FIRST — true only when `:violations` is
   empty, which is exactly when `build!` would write. `:figures` is every
   description in `figs/` with its state, placed or not."
  [:map {:closed true}
   [:ok? :boolean]
   [:violations [:vector spec/violation]]
   [:figures [:vector spec/fig-status-entry]]])

(defn- evaluate
  "The full verdict on the document in `d`, plus the stamped page when the
   document is schema-valid (rendered even with violations, so the page
   backstop runs too; `build!` never writes it then)."
  [^File d]
  (let [hs (fig/house)
        figures (mapv #(fig/status (str d) % hs) (fig/description-ids (str d)))
        {:keys [doc violation]} (dio/read-doc d)
        bad (when-not violation (malformed doc))]
    (if (or violation (seq bad))
      {:ok? false :violations (if violation [violation] bad) :figures figures}
      (let [{:keys [figs] fv :violations} (figure-pass d doc hs)
            {images :images iv :violations} (image/inspect d doc)
            html (render/render doc figs images hs (dio/stamp d (map second (rules/fig-refs doc))))
            vs (vec (concat (rules/data-violations doc) fv iv (rules/html-violations html)))]
        {:ok? (empty? vs) :violations vs :figures figures :html html :doc doc}))))

(m/=> evaluate [:=> [:cat file?]
                [:map [:ok? :boolean] [:violations [:vector spec/violation]]
                 [:figures [:vector spec/fig-status-entry]]
                 [:html {:optional true} spec/ne-string] [:doc {:optional true} spec/edn-map]]])

(defn check
  "Every reason the document in `doc-dir` cannot be built — read `:ok?`.

   Runs the schema (`:doc/malformed` — an unknown block, key or mark is
   named with its path), the structural rules (`press.doc.rules`), the
   figures (`:doc/fig-not-current` for one that is stale, unpicked,
   missing its SVG or invalid; `:doc/bad-svg` for an SVG outside the
   renderer's allowlist) and the page backstop (`:doc/external`,
   `:doc/placeholder`). Writes nothing. `doc-dir` is absolute or
   root-relative; a missing directory refuses `:doc/no-dir`. A missing or
   unreadable `doc.edn` is a violation. Missing/unreadable house constants or
   packaged stylesheet/font are environment failures and may throw; a content
   verdict cannot establish those preconditions. Raster inputs add
   `:doc/no-image`, `:doc/nonlocal-image` and `:doc/bad-image` violations."
  [doc-dir]
  (select-keys (evaluate (dio/dir-of doc-dir)) [:ok? :violations :figures]))

(m/=> check [:=> [:cat spec/ne-string] check-result])

;; ------------------------------------------------------------------ build

(def build-report
  "What `build!` returns for a document it wrote."
  [:map {:closed true}
   [:path spec/ne-string]
   [:bytes :int]
   [:stamp fig/sha256-hex]
   [:sections :int]
   [:figures :int]
   [:glossary :int]])

(defn build!
  "Write the document as ONE self-contained, stamped HTML file, or refuse

   The document is the one in `doc-dir`.

   The file is `<out-dir>/<doc-name>.html` (doc-name = the directory's
   name; both paths absolute or root-relative; out-dir is created).
   Refuses `:doc/refused` — carrying every violation `check` reports — and
   then REMOVES any previous `<doc-name>.html` in out-dir, so a stale page
   can never be printed; a build that THROWS (an unreadable file, missing
   house constants) removes it too and rethrows. The page's
   `press-doc-stamp` meta is what `pdf` checks."
  [doc-dir out-dir]
  (let [d (dio/dir-of doc-dir)
        f (io/file (dio/resolve-path out-dir) (str (.getName d) ".html"))
        {:keys [ok? violations html doc]} (try (evaluate d)
                                               (catch Exception e
                                                 (Files/deleteIfExists (.toPath f))
                                                 (throw e)))]
    (when-not ok?
      (Files/deleteIfExists (.toPath f))
      (throw (ex-info (str "document refused: " (count violations) " violation(s), first: "
                           (:message (first violations)))
                      {:doc/refusal :doc/refused :violations violations})))
    {:path (str f)
     :bytes (dio/write-atomically! f html)
     :stamp (dio/page-stamp html)
     :sections (count (render/numbered-sections doc))
     :figures (count (rules/fig-refs doc))
     :glossary (count (:glossary doc))}))

(m/=> build! [:=> [:cat spec/ne-string spec/ne-string] build-report])

;; ---------------------------------------------------------------- figures

(defn fig-status
  "Each figure description in `<doc-dir>/figs/` with its state.

   `:current` only when the pick record's `problem_hash` equals the sha256
   of the description's canonical JSON AND its `svg_sha256` equals the
   sha256 of `<id>.svg` — a chart's record (written by `press chart`) is read
   the same way. `:stale` names which hash differs; `:unpicked` (no pick
   record), `:missing-svg`, `:invalid` (a description `desc.py` would
   refuse, an id that is not its file stem, or an unreadable pick record)
   carry the file in `:reason`."
  [doc-dir]
  (let [d (str (dio/dir-of doc-dir)) hs (fig/house)]
    (mapv #(fig/status d % hs) (fig/description-ids d))))

(m/=> fig-status [:=> [:cat spec/ne-string] [:vector spec/fig-status-entry]])

(def fig-json-result
  "Where a canonical JSON landed and its problem hash."
  [:map {:closed true} [:path spec/ne-string] [:problem-hash fig/sha256-hex] [:bytes :int]
   [:kind [:enum :diagram :chart]]])

(defn fig-json!
  "Write figure `fig-id`'s canonical JSON to `<out-dir>/<fig-id>.fig.json`.

   Canonical exactly as `press` writes it (keys sorted by code point, no
   whitespace, UTF-8 unescaped, one trailing newline, integers only) — `press`
   refuses any other bytes, and `:problem-hash` (their sha256) is what a
   pick record must carry to count as current. Refuses `:doc/bad-figure`
   when the description is missing or would be refused by `desc.py`."
  [doc-dir fig-id out-dir]
  (let [d (dio/dir-of doc-dir)
        {:keys [ok? json reason]} (if (m/validate fig/fig-id fig-id)
                                    (fig/read-description (str d) fig-id (fig/house))
                                    {:ok? false :reason (str "figure id " (pr-str fig-id) " is not [A-Za-z0-9][A-Za-z0-9_-]*")})]
    (when-not ok?
      (throw (ex-info (str "figure " fig-id " cannot be used: " reason) {:doc/refusal :doc/bad-figure :id fig-id})))
    (let [f (io/file (dio/resolve-path out-dir) (str fig-id ".fig.json"))]
      {:path (str f) :problem-hash (fig/sha256 json) :bytes (dio/write-atomically! f json)
       :kind (if (str/includes? json "\"kind\":\"chart\"") :chart :diagram)})))

(m/=> fig-json! [:=> [:cat spec/ne-string spec/ne-string spec/ne-string] fig-json-result])

(def host-command
  "A HOST command to run from the configured root, with the files it reads
   and writes (root-relative)."
  [:map {:closed true}
   [:argv [:vector spec/ne-string]]
   [:shell spec/ne-string]
   [:fig-json {:optional true} spec/ne-string]
   [:problem-hash {:optional true} fig/sha256-hex]
   [:out spec/ne-string]
   [:look-at {:optional true} spec/ne-string]
   [:writes {:optional true} [:vector spec/ne-string]]
   [:steps {:optional true} [:vector [:vector spec/ne-string]]]])

(defn- fig-paths
  "Where figure `id` of the document in `d` is worked on (pure)."
  [^File d id]
  (let [figs (io/file (dio/build-dir d) "figs")]
    {:figs figs :out (io/file figs id) :fig-json (io/file figs (str id ".fig.json"))
     :pick (fig/fig-file (str d) id ".pick.json") :svg (fig/fig-file (str d) id ".svg")}))

(m/=> fig-paths [:=> [:cat file? spec/ne-string] [:map-of :keyword file?]])

(defn- solve-argv
  "The host argv that lays out figure `id` of `kind` (pure)."
  [^File d id kind]
  (let [{:keys [out fig-json pick svg]} (fig-paths d id)]
    (if (= kind :chart)
      [(config/launcher) "chart" (dio/rel fig-json) (dio/rel pick) (dio/rel svg)]
      [(config/launcher) "fig" "solve" (dio/rel fig-json) (dio/rel out)])))

(m/=> solve-argv [:=> [:cat file? spec/ne-string [:enum :diagram :chart]] [:vector spec/ne-string]])

(defn- fig-work!
  "Write figure `id`'s canonical JSON into the build dir; its kind, hash
   and the work paths."
  [doc-dir id]
  (let [d (dio/dir-of doc-dir)
        {:keys [figs] :as paths} (fig-paths d id)
        {:keys [problem-hash kind]} (fig-json! (str d) id (str figs))]
    (assoc paths :d d :kind kind :problem-hash problem-hash)))

(m/=> fig-work! [:=> [:cat spec/ne-string spec/ne-string]
                 [:map [:d file?] [:out file?] [:fig-json file?] [:pick file?] [:svg file?]
                  [:problem-hash fig/sha256-hex] [:kind [:enum :diagram :chart]]]])

(defn fig-solve!
  "Write a figure's canonical JSON; return the command that lays it out

   The figure is `fig-id` of the document in `doc-dir`.

   A diagram gets `press fig solve` (up to 8 candidates and the numbered
   `sheet.png` to look at, `:look-at`); exit 4 = constraints in conflict
   (`explain.json`), 5 = over the page budget (`budget.json` proposes a
   split the author adopts), 6 = invalid input, 7 = a defect in `press`
   itself (report it; the description is not at fault). A chart gets `press chart
   <chart.json> <pick.json> <out.svg>`, which writes the SVG and its pick
   record straight into `figs/` — no candidates, no pick step."
  [doc-dir fig-id]
  (let [{:keys [d out fig-json problem-hash kind]} (fig-work! doc-dir fig-id)
        argv (solve-argv d fig-id kind)]
    (cond-> {:argv argv :shell (dio/shell-line argv) :fig-json (dio/rel fig-json) :problem-hash problem-hash
             :out (dio/rel out)}
      (= kind :diagram) (assoc :look-at (str (dio/rel out) "/sheet.png"))
      (= kind :chart) (assoc :writes [(nth argv 3) (nth argv 4)]))))

(m/=> fig-solve! [:=> [:cat spec/ne-string spec/ne-string] host-command])

(defn fig-pick!
  "Write a figure's canonical JSON; return the command that fixes a tile

   The figure is `fig-id`; the tile is `tile`.

   `tile` is the number printed on the solve sheet (1..8). The command
   writes `figs/<id>.pick.json` and `figs/<id>.svg` (`:writes`), which are
   committed; afterwards `fig-status` reads `:current`. Exit 2 = the
   description changed since the solve. Refuses `:doc/not-a-diagram` for a
   chart — a chart has no candidates."
  [doc-dir fig-id tile]
  (let [{:keys [out fig-json pick svg problem-hash kind]} (fig-work! doc-dir fig-id)
        argv [(config/launcher) "fig" "pick" (dio/rel fig-json) (dio/rel out) (str tile) (dio/rel pick) (dio/rel svg)]]
    (when (= kind :chart)
      (throw (ex-info (str "figure " fig-id " is a chart: it has no candidates to pick — run fig-solve!")
                      {:doc/refusal :doc/not-a-diagram :id fig-id})))
    {:argv argv :shell (dio/shell-line argv) :fig-json (dio/rel fig-json) :problem-hash problem-hash
     :out (dio/rel out) :writes [(dio/rel pick) (dio/rel svg)]}))

(m/=> fig-pick! [:=> [:cat spec/ne-string spec/ne-string [:int {:min 1 :max 8}]] host-command])

;; ------------------------------------------------------------------ print

(defn- current-doc
  "The document in `d` as it reads now; refuses `:doc/stale-page` when
   `doc.edn` no longer reads or passes its schema."
  [^File d]
  (let [{:keys [doc violation]} (dio/read-doc d)]
    (when (or violation (not (m/validate spec/doc-spec doc)))
      (throw (ex-info (str "doc.edn in " d " no longer reads as a document — rebuild before printing")
                      {:doc/refusal :doc/stale-page})))
    doc))

(m/=> current-doc [:=> [:cat file?] spec/edn-map])

(defn- placed-ids
  "The figures `doc` places, in reading order, each once."
  [doc]
  (vec (distinct (map second (rules/fig-refs doc)))))

(m/=> placed-ids [:=> [:cat spec/edn-map] [:vector spec/ne-string]])

(defn- print-plan
  "The ONE source of the print commands (pure): one `press fig check` per
   placed figure, then `press pdf` on the built page. `pdf` returns it and
   RUNBOOK.md prints it, so the two can never disagree."
  [^File d ids]
  (let [nm (.getName d)
        b (dio/build-dir d)
        page (io/file b (str nm ".html"))
        out (io/file b "pdf")
        argv [(config/launcher) "pdf" (dio/rel page) (dio/rel out) "--name" nm]
        steps (conj (mapv #(vector (config/launcher) "fig" "check" (dio/rel (fig/fig-file (str d) % ".svg"))) ids)
                    argv)]
    {:page page :out out :argv argv :steps steps}))

(m/=> print-plan [:=> [:cat file? [:sequential spec/ne-string]]
                  [:map [:page file?] [:out file?] [:argv [:vector spec/ne-string]] [:steps [:vector [:vector spec/ne-string]]]]])

(defn pdf
  "Host commands that check every placed figure, then print the page to A4

   `:steps` is the sequence to run in order (`:shell` joins it with `&&`):
   one `press fig check <figs/<id>.svg>` per PLACED figure (a draft in `figs/`
   that the document does not place is neither checked nor printed) —
   `press fig rebuild` proves a record matches its description but does not
   re-run the figure checker, so a box moved inside the canvas is caught
   only here — and then `press pdf` (`:argv`), which reads
   `<build>/<doc-name>.html` (written by `build!`) and writes
   `<doc-name>.pdf`, one `page-NN.png` per page and `pdf_report.json` into
   `<build>/pdf/` — read it with `pdf-report`. Refuses `:doc/no-page` when
   there is no built page and `:doc/stale-page` when its stamp differs from
   the files it would be built from now: a page is printed only as it
   stands in the tree. The PDF is copied into the document directory after
   the page review; `:writes` names both places."
  [doc-dir]
  (let [d (dio/dir-of doc-dir)
        nm (.getName d)
        page (io/file (dio/build-dir d) (str nm ".html"))]
    (when-not (.isFile page)
      (throw (ex-info (str "no built page " page " — run build! into the build directory first")
                      {:doc/refusal :doc/no-page :path (str page)})))
    (let [ids (placed-ids (current-doc d))]
      (when (not= (dio/stamp d ids) (dio/page-stamp (slurp page)))
        (throw (ex-info (str page " was built from files that have changed since — run build! again")
                        {:doc/refusal :doc/stale-page :path (str page)})))
      (let [{:keys [out argv steps]} (print-plan d ids)]
        {:argv argv :steps steps :shell (str/join " && " (map dio/shell-line steps)) :out (dio/rel out)
         :look-at (dio/rel (io/file out "pdf_report.json"))
         :writes [(dio/rel (io/file out (str nm ".pdf"))) (str (dio/rel d) "/" nm ".pdf")]}))))

(m/=> pdf [:=> [:cat spec/ne-string] host-command])

(defn pdf-report
  "Read the print report strictly, only if it judged the current page

   The report is that of the document in `doc-dir`.

   Reads `<build>/pdf/pdf_report.json` and the built page
   `<build>/<doc-name>.html`. Returns `press.doc.report/verdict` — READ
   `:ok?` FIRST (false on any `blocking` finding not allowed); `:allowed`
   lists the findings `--allow-layout` waved through. Refuses
   `:doc/no-page` without a built page, `:doc/no-report` without a report,
   `:doc/stale-report` when the report's `html_sha256` is missing or is not
   the hash of the current page (it printed another one), and
   `:doc/bad-report` naming the field when anything the verdict rests on
   is missing or mistyped. `:pdf` is the PDF's path as this JVM sees it.
   The page PNGs beside it are what a page review reads; a green verdict
   is not a page review."
  [doc-dir]
  (let [d (dio/dir-of doc-dir)
        b (dio/build-dir d)
        page (io/file b (str (.getName d) ".html"))]
    (when-not (.isFile page)
      (throw (ex-info (str "no built page " page " — nothing to judge a report against")
                      {:doc/refusal :doc/no-page :path (str page)})))
    (report/read-verdict (io/file b "pdf") page)))

(m/=> pdf-report [:=> [:cat spec/ne-string] report/verdict])

;; ---------------------------------------------------------------- runbook

(defn- figure-lines
  "The runbook lines for one figure."
  [^File d id hs q]
  (let [{:keys [ok? json reason]} (fig/read-description (str d) id hs)
        chart? (and ok? (str/includes? json "\"kind\":\"chart\""))
        solve (if ok? (dio/shell-line (solve-argv d id (if chart? :chart :diagram))) (str "# not runnable yet — " reason))]
    (concat [(str "### Figure `" id "`") ""
             (str "    (doc/fig-solve! " (q (dio/rel d)) " " (q id) ")")
             (str "    " solve)]
            (if chart?
              ["" "A chart is drawn in one step: the command writes its SVG and pick record."]
              [(str "    (doc/fig-pick! " (q (dio/rel d)) " " (q id) " TILE)   ; TILE = the number on the sheet")])
            [""])))

(m/=> figure-lines [:=> [:cat file? spec/ne-string desc/house-spec ifn?] [:sequential spec/any-string]])

(defn- runbook-text
  "The RUNBOOK.md text for the document in `d` — root-relative paths
   only, so it is the same wherever it is generated. The launcher, the
   image command and the two how-to-run sentences are the configured ones
   (`press.config`)."
  [^File d title figs placed]
  (let [r (dio/rel d)
        b (dio/rel (dio/build-dir d))
        nm (.getName d)
        q #(str "\"" % "\"")
        {:keys [steps]} (print-plan d (or placed []))
        drafts (remove (set placed) figs)]
    (str/join
     "\n"
     (concat
      [(str "# How to rebuild \"" title "\"") ""
       "This file is generated by `press.doc/runbook!` — regenerate it rather than editing it."
       (str "It lists, in order, every command that turns this directory (`" r "`) into its printed PDF.")
       "Every path is relative to the root directory the document tree lives in." ""
       "## Where things are" ""
       (str "- `" r "/doc.edn` — the document itself: text, tables and the order of everything.")
       (str "- `" r "/figs/` — one `.edn` description per figure, and the `.pick.json` + `.svg` the layout tool writes back.")
       (str "- `" b "/` — build output: the built page, candidate layouts, page images. Never committed.")
       (str "- `" r "/" nm ".pdf` — the printed document, committed after the page review.") ""
       "## Two kinds of command" ""
       (str "- **Forms** are the parenthesised lines such as `(doc/check …)`. " (config/forms-hint))
       "  A relative path in a form is read from the root directory."
       "- **Host commands** run in a normal shell from the root directory. They start the layout and print tool,"
       (str "  `" (config/launcher) "`, in a container. Build its image once with `" (config/image-hint) "`.") ""
       "## 1. Check the document" ""
       (str "    (doc/check " (q r) ")") ""
       "Read `:ok?` first. Each entry in `:violations` names a rule, the place, and what to change." ""
       "## 2. Lay out each figure" ""
       "For each figure: (a) evaluate the form, which writes the figure's description as JSON and returns a host command;"
       "(b) run that host command; (c) open the numbered sheet it names and choose the best tile;"
       "(d) run the pick form with that tile number, then the host command it returns."
       "The layout tool's exit codes: 0 done; 1 no candidate passed its checks; 2 the description changed since the"
       "solve or the pick — solve again; 3 the pick record does not match; 4 the rules cannot all hold (read"
       "`explain.json`); 5 the figure is taller than a page (`budget.json` proposes a split); 6 invalid input;"
       "7 a fault in the layout tool itself — report it, do not change the figure to get past it."
       "A solve stops itself after 60 seconds (`--total-s`), and the same description always gives the same sheet." ""]
      (if (empty? figs)
        ["This document has no figures." ""]
        (mapcat #(figure-lines d % (fig/house) q) figs))
      ["Then confirm every figure reads `:current`:" ""
       (str "    (doc/fig-status " (q r) ")") ""
       "## 3. Build the page" ""
       (str "    (doc/build! " (q r) " " (q b) ")") ""
       "It writes one HTML file with everything inside it, or refuses, writes nothing and removes an older page." ""
       "## 4. Check every figure, then print" ""
       (str "    (doc/pdf " (q r) ")") ""
       "It returns the host commands below, and refuses if the page is older than the files it was built from."
       "Run them in order. Each figure check must exit 0: the figure checker is the only control for a figure"
       "whose drawing was edited after its pick." ""]
      (map #(str "    " (dio/shell-line %)) steps)
      [""]
      (if (nil? placed)
        ["doc.edn does not pass its schema yet, so which figures it places is not known: run step 1 first." ""]
        (map #(str "`" % "` is not placed in the document: it is neither checked nor printed.") drafts))
      (when (and placed (seq drafts)) [""])
      ["## 5. Read the print report" ""
       (str "    (doc/pdf-report " (q r) ")") ""
       "Read `:ok?` first. It refuses a report that judged an older page (print again), and names the field"
       "of a report that is missing or wrong; `:allowed` lists the layout findings that were waved through." ""
       "## 6. Review every page" ""
       (str "Every printed page is an image in `" b "/pdf` (`page-NN.png`). " (config/review-hint))
       "Fix what the review finds in `doc.edn` and go back to step 1." ""
       "## 7. Keep the result" ""
       (str "    cp " b "/pdf/" nm ".pdf " r "/" nm ".pdf") ""
       (str "Commit `doc.edn`, `figs/`, `" nm ".pdf` and this file together.") ""]))))

(m/=> runbook-text [:=> [:cat file? spec/ne-string [:vector spec/ne-string] [:maybe [:vector spec/ne-string]]] spec/ne-string])

(defn runbook!
  "Write `<doc-dir>/RUNBOOK.md`: this document's commands, in order.

   Every path is root-relative (the forms read relative paths from the
   configured root), so the file is identical wherever it is generated;
   every figure gets its own solve / look / pick lines. Plain language,
   for someone who has never seen the library. Refuses `:doc/no-doc`
   without a readable `doc.edn` carrying a `:title` (the document need not
   pass `check`) and `:doc/outside-repo` for a directory outside the
   root."
  [doc-dir]
  (let [d (dio/dir-of doc-dir)
        {:keys [doc violation]} (dio/read-doc d)
        title (:title doc)]
    (when (or violation (not (and (string? title) (seq title))))
      (throw (ex-info (str "no readable doc.edn with a :title in " d)
                      {:doc/refusal :doc/no-doc :path (str d)})))
    (let [f (io/file d "RUNBOOK.md")]
      {:path (str f)
       :bytes (dio/write-atomically! f (runbook-text d title (fig/description-ids (str d))
                                                     (when (m/validate spec/doc-spec doc) (placed-ids doc))))})))

(m/=> runbook! [:=> [:cat spec/ne-string] [:map {:closed true} [:path spec/ne-string] [:bytes :int]]])

;; ---------------------------------------------------------------- example

(defn example
  "A small complete document as data covering common block kinds.

   It passes `check` once written as `<dir>/doc.edn` inside the root
   (figures and rasters need asset files, so it has neither). Read it for the shape of
   each block; `spit` it with `pr-str` to start a new document."
  []
  {:title "How Press builds one portable document"
   :kicker "Press · document inputs · example"
   :what-this-is ["This is a short example of the house document shape. It follows each "
                  [:doc/term "input"] " into a self-contained page."]
   :audience "developers learning the Press document library"
   :snapshot {:commit "40feb9f965" :date "2026-10-11"}
   :cover [[:doc/note :verdict [:doc/p [:b "The one idea."] " The printed page carries its content with it."]]]
   :problem-title "The problem: portable pages"
   :problem [[:doc/lead [:b "A document's assets can drift away from its text."] " A portable page embeds the assets it needs."]
             [:doc/item "A source image may change" [:doc/p "Its bytes belong in the freshness stamp."]]
             [:doc/item "A network link may disappear" [:doc/p "The built page must not need a remote resource."]]]
   :status-title "Where this stands: built"
   :status [[:doc/lead "The page builder is " [:doc/pill :built] "; an individual page review may still be " [:doc/pill :open] "."]
            [:doc/note :note [:doc/p [:b "What is left."] " Read every page after printing; a content check does not perform that review."]]
            [:doc/small "This example describes the public Press library; its illustrative paths are not extra files to create."]]
   :how-to-read [[:doc/p "Read " [:doc/ref "inputs"] " first."]]
   :body [{:title "From inputs to a page"
           :summary "What the builder reads and what it writes."
           :sections [{:id "inputs" :title "Inputs"
                       :blocks [[:doc/lead "The builder reads document data, generated figures and local rasters."]
                                [:doc/h3 {:id "coverage"} "What each input contributes"]
                                [:doc/p "Three illustrative input paths are listed here."]
                                [:doc/table {:cols [20 50 30] :align [:l :l :r] :head ["kind" "example path" "characters"]
                                             :rows [["document" "doc.edn" "7"]
                                                    ["figure" "figs/reference.svg" "18"]
                                                    ["raster" ["images/sample.png; see " [:doc/code "tools/press/src/press/doc.clj"]] "17"]]
                                             :caption "Three input kinds; the counts cover the illustrative path only."}]
                                [:doc/ul "each asset is read locally" ["the page remains " [:em "self-contained"]]]
                                [:doc/small "Character counts illustrate a right-aligned numeric column."]]}
                      {:id "build" :title "How it builds"
                       :blocks [[:doc/lead "The builder checks inputs, embeds content and writes a freshness stamp."]
                                [:doc/p "The entry point is " [:doc/code "tools/press/src/press/doc.clj"] "; the inputs are in "
                                 [:doc/ref "inputs"] ", the contribution of each in " [:doc/ref "coverage"] "."]
                                [:doc/keep
                                 [:doc/p "Each build follows three steps:"]
                                 [:doc/steps "check document and assets" "inline the content" "write the stamped page"]]
                                [:doc/ol "check before printing" "review after printing"]
                                [:doc/note :warn [:doc/p "Rebuild after changing any input."]]
                                [:doc/note :verdict [:doc/p "The page carries its pictures, stylesheet and font."]]]}]}]
   :open-questions [[:doc/lead "Does every page remain readable after printing?"]]
   :glossary [{:term "input" :def "A document or local asset whose bytes contribute to the built page."}]
   :sources [[:doc/lead "The implementation is public in this repository."]
             [:doc/table {:cols [40 60] :head ["behavior" "file"]
                          :rows [["checking and building" [:doc/code "tools/press/src/press/doc.clj"]]
                                 ["input hashing" [:doc/code "tools/press/src/press/doc/io.clj"]]]}]]})

(m/=> example [:=> [:cat] spec/doc-spec])
