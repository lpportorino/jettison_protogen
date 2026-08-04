(ns renderer-gen.ui-ast-constraints-json
  "Emit `ui-ast-constraints.json` — the account of what happens to every
   `buf.validate` constraint `proto/ui/ui_ast.proto` declares once
   `scripts/proto_cleanup.awk` has deleted it.

   THE GAP THIS EXISTS FOR. That script strips every bracketed field option
   before the nanopb leg runs, so NO `buf.validate` annotation reaches the
   generated C. Nothing told an author that. A reader of the proto sees
   `lte: 16` beside `max_items: 32` on the same message and cannot tell that one
   is upheld by a renderer constant while the other is upheld by nothing — and
   the C carries no trace of either, so reading the C does not answer it either.

   WHAT SURVIVES THE STRIP IS EXACTLY ONE THING: `proto/ui/ui_ast.options`, which
   is copied through unmodified, so nanopb `max_size` / `max_count` still size
   the generated structs and the decoder refuses an over-long string or an
   over-full static array on its own. `lte`, `gte`, `min_len`, `min_items` and
   `enum defined_only` have no nanopb expression at all; for those a hand-written
   guard in `renderer/src/renderer.c` is the ONLY enforcement, or there is none.

   WHY THIS IS NOT `ui-ast-bounds.json`. That manifest publishes the nanopb side
   — the surviving half — from the options file, and its own docstring names
   tying the two sides together as a wire-consistency gate owed elsewhere. This
   is that gate. It reads the PROTO, joins each constraint to the options file
   and to a disposition the author must declare, and refuses rather than
   publishing a constraint nobody has ruled on.

   GENERATION IS THE COMPLETENESS CHECK, the posture its two sibling emitters
   take. Totality runs BOTH ways: every constraint the proto declares must carry
   a disposition, and every disposition must name a constraint that is still
   declared. So a new `lte` fails the emit until someone decides about it, and a
   constraint that is deleted takes its disposition with it — the registry can
   only ratchet, never accumulate excuses for findings that are gone.

   THE THREE DISPOSITIONS, and what the emit verifies about each:

     `nanopb-size`   the strip removes no enforcement, because `ui_ast.options`
                     carries the matching bound. VERIFIED, not trusted: the
                     emit computes `max_size == max_len + 1` (the NUL) and
                     `max_count == max_items`, and refuses on either side of
                     exact. A LOOSER nanopb bound admits input the wire forbids;
                     a STRICTER one refuses input the wire permits. Both are
                     wire bugs and neither is visible from either file alone.

     `renderer-guard` a hand-written C guard refuses the illegal value. Requires
                     `:guard`, a token that must occur in `renderer/src/renderer.c`,
                     and `:test`, a test that must occur in
                     `renderer/wasm_harness/tests/wire_constraints.rs`. Neither
                     anchor proves the guard FIRES — only the test can, and the
                     test is what runs. What the anchors prove is that neither
                     has been deleted while this manifest went on claiming them,
                     which is the failure mode a published claim actually has.

     `enforced-elsewhere` no guard names this constraint, but a clause somewhere
                     else rejects the same inputs — in another file, at another
                     phase, or while asking a different question. Requires `:by`
                     naming that clause and `:harm` naming what it does NOT
                     cover, which for a differently-phased clause is usually
                     WHEN it refuses rather than whether. Deliberately a
                     SEPARATE verdict from
                     `renderer-guard`, because nothing here verifies it and the
                     coincidence can end without anyone noticing. Collapsing it
                     into either neighbour is what kept this whole surface
                     invisible: folded upward, an incidental rejection reads as
                     enforcement; folded downward, a covered input reads as a
                     gap and invites a second guard nobody needs.

     `unenforced`    nothing upholds it and nothing else rejects the input
                     either. Requires `:harm` — what an out-of-range value does
                     instead — because an entry that only says \"not enforced\"
                     is indistinguishable from one nobody thought about.

   Every entry carries a `:rationale` whatever its disposition. For a partial
   guard that is where the partiality is stated; the disposition vocabulary is
   deliberately small, so the scope of a guard lives in prose rather than in an
   ever-growing set of keywords.

   THE ENUM RANGE CHECK IS WHY DENSITY IS ASSERTED. The renderer's `defined_only`
   guards are range tests against nanopb's own `_MIN`/`_MAX` macros, which the
   generator derives from the proto — so they track the vocabulary with no second
   list. That is exact only while the enum is DENSE: an enumerator leaving a HOLE
   would let the hole's value through a guard that still looks correct. So an
   entry declaring `:range-over` names its enum, and the emit refuses if that
   enum has a gap.

   Shape (CLOSED), mirroring `ui-ast-bounds.json` so `source` stays a single
   string in both:
     {\"source\": \"proto/ui/ui_ast.proto\",
      \"stripped_by\": \"scripts/proto_cleanup.awk\",
      \"constraints\": {<fully.qualified.field>
                        {<constraint> {\"value\" .. \"survives_strip\" .. ..}}}}

   Run (from tools/renderer-gen/):
     clojure -M -m renderer-gen.ui-ast-constraints-json \\
       --proto ../../proto/ui/ui_ast.proto \\
       --options ../../proto/ui/ui_ast.options \\
       --dispositions edn/ui-ast-constraint-dispositions.edn \\
       --renderer ../../renderer/src/renderer.c \\
       --tests ../../renderer/wasm_harness/tests/wire_constraints.rs \\
       --output ../../output/manifests/ui-ast-constraints.json"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [renderer-gen.ui-ast-bounds-json :as bounds])
  (:gen-class))

(set! *warn-on-reflection* true)

(def manifest-source
  "The proto the constraints are parsed from, as protogen consumers see it."
  "proto/ui/ui_ast.proto")

(def stripper
  "The script that deletes every one of these annotations before the C leg."
  "scripts/proto_cleanup.awk")

(def size-constraints
  "The constraints nanopb CAN express, and the option each maps onto. These are
   the only ones a `nanopb-size` disposition may name."
  {"max_len" "max_size" "max_items" "max_count"})

(def value-constraints
  "The constraints nanopb cannot express in any form. Declared and then deleted,
   so a renderer guard is the only thing that can uphold one."
  #{"min_len" "min_items" "lte" "gte" "lt" "gt" "const" "defined_only"})

(def known-constraints
  "The closed constraint vocabulary. A `buf.validate` key outside it fails the
   emit rather than being dropped, so a newly-used constraint forces a
   publish-or-decline decision instead of silently shrinking what is covered."
  (into (set (keys size-constraints)) value-constraints))

(def ^:private containers
  "Structural keys inside a constraint body that introduce a nested rule rather
   than being one — `repeated { items: { string: {max_len: 31} } }`."
  #{"items" "string" "uint32" "int32" "repeated" "enum" "bool" "bytes"})

(def dispositions
  "The closed disposition vocabulary. See the namespace docstring for what the
   emit verifies about each."
  #{"nanopb-size" "renderer-guard" "enforced-elsewhere" "unenforced"})

(defn- fail
  "Throw a refusal naming its own CLAUSE, so a canary can require the clause
   under test and require its neighbours to stay silent — a red that cannot be
   attributed proves nothing about the clause it was meant to drive."
  [clause message data]
  (throw (ex-info (str "ui_ast constraints: " message)
                  (assoc data :clause clause))))

(defn- annotation-bodies
  "Every `(buf.validate.field)` annotation in `proto`, as
   `[message field enum-type body]`. Annotations span lines, so each is
   accumulated to its closing `];` before being flattened."
  [proto]
  (let [lines (vec (str/split-lines proto))]
    (loop [i 0, msg nil, out []]
      (if (>= i (count lines))
        out
        (let [line (nth lines i)
              msg (or (second (re-find #"^message (\w+)" line)) msg)]
          (if-not (str/includes? line "buf.validate")
            (recur (inc i) msg out)
            (let [[j buf] (loop [j i, acc line]
                            (if (or (str/includes? acc "];")
                                    (>= (inc j) (count lines)))
                              [j acc]
                              (recur (inc j) (str acc " " (nth lines (inc j))))))
                  flat (str/replace (str/trim buf) #"\s+" " ")
                  decl (re-find #"^(?:repeated\s+)?([\w.]+) (\w+) = \d+" flat)]
              (when-not decl
                (fail :unparseable-field
                      (str "cannot read a `<type> <name> = <n>` declaration "
                           "from: " (pr-str flat))
                      {:message msg :line flat}))
              (recur (inc (long j)) msg
                     (conj out [msg (nth decl 2) (nth decl 1) flat])))))))))

(defn- constraints-in
  "The `{constraint value}` map a single annotation body declares. Every
   `key:` in the body must be a known constraint or a structural container;
   anything else refuses, so the vocabulary cannot widen unnoticed."
  [field body]
  (reduce
   (fn [acc [_ k v]]
     (cond
       (containers k) acc
       (not (known-constraints k))
       (fail :unknown-constraint
             (str "field " field " declares the unknown buf.validate "
                  "constraint " (pr-str k))
             {:field field :constraint k})
       :else
       (let [n (parse-long (str v))]
         (cond
           (= "true" v) (assoc acc k true)
           (= "false" v)
           (fail :bad-constraint-value
                 (str "field " field " declares " k " false, which asserts "
                      "nothing and would publish as a live constraint")
                 {:field field :constraint k})
           (nil? n)
           (fail :bad-constraint-value
                 (str "field " field " declares " k " " (pr-str v)
                      ", which is neither an integer nor `true`")
                 {:field field :constraint k :value v})
           :else (assoc acc k n)))))
   (sorted-map)
   ;; A value is an integer, `true`/`false`, or an opening brace (a container).
   (re-seq #"(\w+)\s*:\s*(\d+|true|false|\{)" body)))

(defn parse-constraints
  "Every constraint the proto declares: `{field {constraint value}}`, with the
   field's declared type carried alongside for the enum checks. Floors at one
   entry — an empty result is what a wrong path and a truncated read both
   produce, and publishing it would assert a wire surface with no constraints."
  [proto]
  (let [m (reduce (fn [acc [msg field ftype body]]
                    (let [k (str "ui." msg "." field)
                          cs (constraints-in k body)]
                      (when (empty? cs)
                        (fail :no-constraints
                              (str "field " k " carries a buf.validate "
                                   "annotation declaring nothing")
                              {:field k}))
                      (-> acc
                          (update-in [k :constraints] (fnil into (sorted-map)) cs)
                          (assoc-in [k :type] ftype))))
                  (sorted-map)
                  (annotation-bodies proto))]
    (when (empty? m)
      (fail :empty
            "no buf.validate constraints found — wrong proto source?"
            {:source manifest-source}))
    m))

(defn enum-values
  "Every `enum Name { … }` the proto declares, as `{name #{values}}`. Read for
   the density assertion the range guards depend on."
  [proto]
  (into (sorted-map)
        (for [[_ enum-name body] (re-seq #"(?s)\benum (\w+)\s*\{(.*?)\}" proto)]
          [enum-name (set (map (comp parse-long second)
                               (re-seq #"\w+\s*=\s*(-?\d+)\s*;" body)))])))

(defn dense?
  "True when `values` is exactly `0..max` with no gap — the condition a `_MIN`
   .. `_MAX` range guard needs to be an exact membership test."
  [values]
  (and (seq values)
       (= values (set (range (apply min values) (inc (apply max values)))))))

(defn- check-nanopb-size
  "Verify a `nanopb-size` claim against `ui_ast.options`. The bound must be
   EXACT: looser admits what the wire forbids, stricter refuses what it permits."
  [field constraint value nanopb]
  (let [option (size-constraints constraint)
        _ (when-not option
            (fail :size-disposition-on-value-constraint
                  (str field " " constraint " is not a size constraint, so "
                       "nanopb cannot carry it; `nanopb-size` is not available")
                  {:field field :constraint constraint}))
        declared (get-in nanopb [field option])
        want (if (= "max_len" constraint) (inc value) value)]
    (when-not declared
      (fail :size-bound-unbacked
            (str field " claims nanopb-size for " constraint " but "
                 "ui_ast.options declares no " option " for it"
                 (when-some [t (get-in nanopb [field "type"])]
                   (str " (it is " t ", which has no static width)")))
            {:field field :constraint constraint :option option}))
    (when-not (= want declared)
      (fail :size-bound-mismatch
            (str field ": " constraint " " value " wants " option " " want
                 ", ui_ast.options declares " declared
                 (if (> declared want)
                   " — LOOSER, so the C admits input the wire forbids"
                   " — STRICTER, so the C refuses input the wire permits"))
            {:field field :constraint constraint
             :want want :declared declared}))
    declared))

(defn- check-guard
  "Verify a `renderer-guard` claim: the guard token is present in the renderer
   source and the named test is present in the wire-constraint suite. Neither
   proves the guard fires — the TEST does that, when it runs — but a claim whose
   subject has been deleted must not keep publishing."
  [field constraint {:keys [guard range-over] test-name :test} renderer tests
   enums]
  (when-not (str/includes? renderer (str guard))
    (fail :missing-guard
          (str field " " constraint " names the guard " (pr-str guard)
               ", which does not occur in the renderer source")
          {:field field :constraint constraint :guard guard}))
  (when-not (str/includes? tests (str test-name))
    (fail :missing-test
          (str field " " constraint " names the test " (pr-str test-name)
               ", which does not occur in the wire-constraint suite")
          {:field field :constraint constraint :test test-name}))
  (when range-over
    (let [values (get enums range-over)]
      (when (empty? values)
        (fail :unknown-enum
              (str field " " constraint " ranges over " (pr-str range-over)
                   ", which the proto does not declare")
              {:field field :constraint constraint :enum range-over}))
      (when-not (dense? values)
        (fail :sparse-enum
              (str field " " constraint " is guarded by a _MIN.._MAX range over "
                   range-over ", which has a GAP — a value in the gap would "
                   "pass a guard that still looks correct")
              {:field field :constraint constraint :enum range-over
               :values (vec (sort values))})))))

(defn- entry
  "The published record for one constraint, after its disposition is verified."
  [field constraint value registry nanopb renderer tests enums]
  (let [d (get-in registry [field constraint])]
    (when-not d
      (fail :undisposed
            (str field " declares " constraint " " value " and no disposition "
                 "says what this leg does about it; add one to the registry")
            {:field field :constraint constraint}))
    (let [{:keys [disposition rationale harm by]} d
          ;; The registry writes keywords, which is what EDN is for; the
          ;; manifest publishes strings, which is what JSON is for. `name`
          ;; spans both so neither file has to hold the other's spelling.
          verdict (when (or (keyword? disposition) (string? disposition))
                    (name disposition))]
      (when-not (dispositions verdict)
        (fail :unknown-disposition
              (str field " " constraint " declares the disposition "
                   (pr-str disposition) "; " (pr-str dispositions) " are known")
              {:field field :constraint constraint :disposition disposition}))
      (when (str/blank? (str rationale))
        (fail :missing-rationale
              (str field " " constraint " carries no :rationale")
              {:field field :constraint constraint}))
      (when (and (#{"unenforced" "enforced-elsewhere"} verdict)
                 (str/blank? (str harm)))
        (fail :missing-harm
              (str field " " constraint " is not upheld by this constraint and "
                   "does not say what an illegal value DOES instead; an entry "
                   "that only says \"not enforced\" cannot be told from one "
                   "nobody considered")
              {:field field :constraint constraint}))
      (when (and (= "enforced-elsewhere" verdict) (str/blank? (str by)))
        (fail :missing-by
              (str field " " constraint " claims a neighbouring clause rejects "
                   "the same input and does not name it; unnamed, the claim "
                   "cannot be re-checked when that clause moves")
              {:field field :constraint constraint}))
      (case verdict
        "nanopb-size"
        {"value" value "survives_strip" true "disposition" verdict
         "nanopb" (check-nanopb-size field constraint value nanopb)
         "rationale" rationale}

        "renderer-guard"
        (do (check-guard field constraint d renderer tests enums)
            (cond-> {"value" value "survives_strip" false "disposition" verdict
                     "guard" (str (:guard d)) "test" (str (:test d))
                     "rationale" rationale}
              (:range-over d) (assoc "range_over" (str (:range-over d)))))

        "enforced-elsewhere"
        {"value" value "survives_strip" false "disposition" verdict
         "by" by "harm" harm "rationale" rationale}

        {"value" value "survives_strip" false "disposition" verdict
         "harm" harm "rationale" rationale}))))

(defn- check-registry-live
  "Refuse a registry entry naming a constraint the proto no longer declares.
   Without this an entry outlives its subject and the registry stops ratcheting:
   a constraint could be deleted while its excuse stayed behind."
  [registry parsed]
  (doseq [[field cs] registry
          [constraint _] cs]
    (when-not (get-in parsed [field :constraints constraint])
      (fail :stale-disposition
            (str "the registry dispositions " field " " constraint
                 ", which proto/ui/ui_ast.proto no longer declares")
            {:field field :constraint constraint}))))

(defn manifest
  "The full manifest map (JSON-ready, deterministically ordered), after every
   disposition has been verified in both directions."
  [{:keys [proto options registry renderer tests]}]
  (let [parsed (parse-constraints proto)
        nanopb (bounds/parse-bounds options)
        enums (enum-values proto)]
    (check-registry-live registry parsed)
    {"source" manifest-source
     "stripped_by" stripper
     "constraints"
     (into (sorted-map)
           (for [[field {:keys [constraints]}] parsed]
             [field (into (sorted-map)
                          (for [[c v] constraints]
                            [c (entry field c v registry nanopb renderer
                                      tests enums)]))]))}))

(defn- parse-args
  "Closed flag pairs; unknown flags fail loud."
  [args]
  (reduce (fn [acc [flag value]]
            (case flag
              "--proto" (assoc acc :proto value)
              "--options" (assoc acc :options value)
              "--dispositions" (assoc acc :dispositions value)
              "--renderer" (assoc acc :renderer value)
              "--tests" (assoc acc :tests value)
              "--output" (assoc acc :output value)
              (throw (ex-info (str "unknown flag: " flag) {:flag flag}))))
          {:proto "../../proto/ui/ui_ast.proto"
           :options "../../proto/ui/ui_ast.options"
           :dispositions "edn/ui-ast-constraint-dispositions.edn"
           :renderer "../../renderer/src/renderer.c"
           :tests "../../renderer/wasm_harness/tests/wire_constraints.rs"}
          (partition 2 args)))

(defn- summarise
  "Per-disposition counts, printed so a run reports what it judged rather than
   only that it exited zero."
  [m]
  (->> (vals (get m "constraints"))
       (mapcat vals)
       (map #(get % "disposition"))
       frequencies
       (into (sorted-map))))

(defn -main
  "Parse the proto's constraints, verify every disposition, emit the manifest,
   and print the per-disposition counts. `--output` is mandatory — this tool
   never guesses a destination."
  [& args]
  (let [{:keys [proto options renderer tests output]
         dispositions-path :dispositions}
        (parse-args args)]
    (when-not output (throw (ex-info "--output is required" {:args (vec args)})))
    (let [m (manifest {:proto (slurp proto)
                       :options (slurp options)
                       :registry (edn/read-string (slurp dispositions-path))
                       :renderer (slurp renderer)
                       :tests (slurp tests)})]
      (io/make-parents output)
      (with-open [w (io/writer output)]
        (json/write m w :indent true :escape-slash false)
        (.write w "\n"))
      (println (str "ui-ast-constraints.json: "
                    (reduce + (map count (vals (get m "constraints"))))
                    " constraints over " (count (get m "constraints"))
                    " fields " (pr-str (summarise m)) " -> " output)))))
