(ns renderer-gen.ui-ast-bounds-json
  "Emit `ui-ast-bounds.json` — the ui_ast WIRE-BOUND manifest: every nanopb
   field option declared in `proto/ui/ui_ast.options`, published so a consumer's
   codegen READS the bound it must not exceed instead of re-typing the literal
   with a hand-written fail-fast message beside it.

   WHY THIS IS NOT `renderer-caps.json`. That manifest publishes the reference
   interpreter's static POOL capacities from `renderer/src/renderer.c`, and every
   entry in its `caps` map feeds ONE consumer loop: a per-SCREEN count checked
   against 80% of the cap, the remaining fifth being patch-time growth reserve.
   A nanopb bound is a different fact in three ways, and each of them alone rules
   out `caps` as its home:
     - it is per-FIELD, not a screen aggregate, so it has no count to compare
       against and would sit in `caps` inert — a published bound nothing checks,
       whose silence is indistinguishable from a working one;
     - it has no growth reserve to hold back. A 128-byte template that fits is
       correct at 128; refusing it at 103 because a headroom rule reached a field
       bound would be a codegen error over a legal screen;
     - its source is not `renderer.c`, and that manifest's completeness proof is
       anchored on one file's `MAX_*` grammar.
   `non-headroom-caps` is not the home either, and reading its NAME rather than
   its CONTENT is the trap: it maps a `#define` to a RATIONALE STRING and carries
   no value at all, so a bound parked there publishes no number to read.

   Shape (CLOSED), deliberately mirroring `renderer-caps.json`'s so `source`
   stays a single string in both and no existing reader's contract shifts:
     {\"source\": \"proto/ui/ui_ast.options\",
      \"bounds\": {<fully.qualified.field> {<nanopb option> <value>}}}

   The option keys are nanopb's own spellings (`max_size`, `max_count`, `type`),
   never a re-cased local vocabulary — an entry must be joinable straight back to
   the line it came from.

   Generation IS the completeness check, the same posture
   `renderer-gen.renderer-caps-json` takes. The manifest is a TOTAL publication
   of the file: every non-blank, non-comment line must parse into exactly one
   entry, so there is no allowlist, no skip, and no way for a declared bound to
   be absent from the manifest while the emit still succeeds. Any drift throws.

   WHAT THIS DELIBERATELY DOES NOT CHECK, so a reader does not credit it with
   coverage it lacks. `proto/ui/ui_ast.proto` carries a SECOND bound for many of
   these fields — `buf.validate` `max_len` / `max_items`, which binds a producer
   where `max_size` / `max_count` binds nanopb's struct — and the two are related
   by convention (`max_size = max_len + 1` for the NUL; `max_count = max_items`)
   with nothing enforcing it. Several fields carry one side and not the other,
   on purpose, and `ui_ast.options` documents each such asymmetry in prose. Tying
   the two is a wire-consistency gate in its own right and is NOT attempted here;
   this manifest publishes the nanopb side only, and says so in the artifact by
   naming its single source.

   Run (from tools/renderer-gen/):
     clojure -M -m renderer-gen.ui-ast-bounds-json \\
       --options ../../proto/ui/ui_ast.options \\
       --output ../../output/manifests/ui-ast-bounds.json"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:gen-class))

(set! *warn-on-reflection* true)

(def manifest-source
  "The nanopb options file the bounds are parsed from, as protogen consumers
   see it."
  "proto/ui/ui_ast.options")

(def known-options
  "The nanopb field options this manifest publishes. CLOSED: a line carrying
   anything else fails the emit rather than being dropped, so a newly-used
   nanopb option forces a publish-or-decline decision instead of silently
   shrinking what the manifest covers."
  #{"type" "max_size" "max_count"})

(def ^:private count-options
  "The options whose value is a positive integer; `type` is the remaining one
   and carries a symbolic nanopb field type instead."
  #{"max_size" "max_count"})

(defn- fail
  "Throw a parse refusal naming its own CLAUSE, so a canary can require the
   clause under test and require its neighbours to stay silent — a red that
   cannot be attributed proves nothing about the clause it was meant to drive."
  [clause message data]
  (throw (ex-info (str "ui_ast.options: " message) (assoc data :clause clause))))

(defn- parse-option
  "One `key:value` token of a field line, validated against the closed option
   set and the value shape that key admits."
  [field token]
  (let [[k v] (str/split token #":" 2)]
    (when (or (str/blank? (str k)) (str/blank? (str v)))
      (fail :unparseable-option
            (str "field " field " carries an option token that is not "
                 "`key:value`: " (pr-str token))
            {:field field :token token}))
    (when-not (known-options k)
      (fail :unknown-option
            (str "field " field " declares the unknown nanopb option "
                 (pr-str k) "; " (pr-str known-options) " are published")
            {:field field :option k}))
    (cond
      (count-options k)
      (let [n (parse-long v)]
        (when-not (and n (pos? n))
          (fail :bad-option-value
                (str "field " field " declares " k " " (pr-str v)
                     ", which is not a positive integer")
                {:field field :option k :value v}))
        [k n])

      :else
      (do (when-not (re-matches #"FT_[A-Z_]+" v)
            ;; The FT_* SET is deliberately left open while its SHAPE is
            ;; closed: nanopb owns that vocabulary, so refusing an unlisted
            ;; member would be this repo overruling the generator it feeds,
            ;; while a value that is not an FT_ token at all is a typo the
            ;; generator would refuse anyway — later, and further away.
            (fail :bad-option-value
                  (str "field " field " declares type " (pr-str v)
                       ", which is not an FT_* nanopb field type")
                  {:field field :option k :value v}))
          [k v]))))

(defn- parse-line
  "One declaration line — a fully-qualified field followed by at least one
   option — as a `[field {option value}]` pair."
  [line]
  (let [[field & tokens] (str/split (str/trim line) #"\s+")]
    (when (str/includes? field "*")
      ;; A wildcard applies to every field matching it, and resolving that
      ;; needs the descriptor this emitter deliberately does not load. Left
      ;; unresolved it would publish a key no consumer can join on, so the
      ;; manifest would UNDER-report the bound on a real field while still
      ;; looking complete — a lie with a green emit behind it.
      (fail :wildcard-field
            (str "wildcard declaration " (pr-str field)
                 " cannot be published per-field; write the fields out")
            {:field field}))
    (when (empty? tokens)
      (fail :no-options
            (str "field " field " declares no options")
            {:field field}))
    [field (into (sorted-map) (map #(parse-option field %)) tokens)]))

(defn- strip-comment
  "A line with its comments removed — WHOLE-LINE and TRAILING alike, in ALL
   THREE forms nanopb accepts.

   THE FORM SET IS READ FROM THE GENERATOR, NOT INFERRED FROM THE FORMAT, and
   the difference is why an earlier version of this fn was wrong. Reasoning
   from \"options values are protobuf text format, which tolerates a trailing
   comment\" yields `#` and stops, because text format has no `//` or `/* */`.
   nanopb's own `read_options_file` strips all three, in this order, BEFORE it
   hands the text to `text_format.Merge`:

     /* ... */   (MULTILINE)
     // ...      (to end of line)
     #  ...      (to end of line)

   So `ui.Foo.bar max_size:64 // note` is a LEGAL options file, and a parser
   that stripped only `#` refused it — with a message blaming the token `//`
   rather than itself, which is exactly the failure this fn exists to prevent.

   Safe within the closed option set: every option published here takes an
   integer or an FT_ token, none takes a quoted string, so no comment marker
   can be part of a value. `#` is additionally safe unconditionally — nanopb
   destroys one inside any value itself, so no legal file can carry a
   significant one."
  [line]
  (-> line
      (str/replace #"/\*.*?\*/" "")
      (str/split #"//" 2)
      first
      (str/split #"#" 2)
      first
      str/trim))

(defn parse-bounds
  "Every nanopb field option the source declares: {field {option value}}.
   TOTAL over the file — comments and blank lines are the only lines that do
   not produce an entry, and a duplicate field is refused rather than letting a
   later line silently overwrite the bound this manifest went on to publish."
  [source]
  (reduce (fn [acc line]
            (let [[field opts] (parse-line line)]
              (when (contains? acc field)
                (fail :duplicate-field
                      (str "field " field " is declared twice; the manifest "
                           "can publish only one bound for it")
                      {:field field}))
              (assoc acc field opts)))
          (sorted-map)
          (remove str/blank?
                  (map strip-comment (str/split-lines source)))))

(defn manifest
  "The full manifest map (JSON-ready, deterministically ordered). Floors the
   parse at one entry: an empty result is what a wrong path, a truncated read
   or a comment-only file all produce, and publishing it would assert a wire
   surface with no bounds at all."
  [source]
  (let [bounds (parse-bounds source)]
    (when (empty? bounds)
      (fail :empty
            "no field declarations found — wrong options source?"
            {:source manifest-source}))
    {"source" manifest-source
     "bounds" bounds}))

(defn- parse-args
  "Closed --options/--output flag pairs; unknown flags fail loud."
  [args]
  (reduce (fn [acc [flag value]]
            (case flag
              "--options" (assoc acc :options value)
              "--output" (assoc acc :output value)
              (throw (ex-info (str "unknown flag: " flag)
                              {:flag flag :expected #{"--options" "--output"}}))))
          {:options "../../proto/ui/ui_ast.options"}
          (partition 2 args)))

(defn -main
  "Parse ui_ast.options, emit ui-ast-bounds.json, print the bound count.
   `--output` is mandatory — this tool never guesses a destination."
  [& args]
  (let [{:keys [options output]} (parse-args args)]
    (when-not output (throw (ex-info "--output is required" {:args (vec args)})))
    (let [m (manifest (slurp options))]
      (io/make-parents output)
      (with-open [w (io/writer output)]
        (json/write m w :indent true :escape-slash false)
        (.write w "\n"))
      (println (str "ui-ast-bounds.json: " (count (get m "bounds"))
                    " bounded ui_ast fields -> " output)))))
