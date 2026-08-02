(ns scratchcard.input
  "Turns a scratch screen file into `ui.Screen` protobuf bytes.

  THE SCREEN IS DATA, NOT CODE. It is read with `clojure.edn/read`, so no
  form in it is ever evaluated. That is what makes this daemon safe to leave
  running for hundreds of iterations: an `eval` path would intern vars and
  accumulate classloader garbage in a process designed never to restart, and
  would hand arbitrary code execution to anything that could write the file.

  IT SPEAKS THE renderer-gen DIALECT — `{:tag :lv_slider :class \"w-120 h-12\"}`
  — the same vocabulary `renderer/edn/screens/*.edn` is authored in, NOT the
  devcards node shape (`{:type :WIDGET_SLIDER}`). Two vocabularies exist for
  the same widgets; this is the one with a validating pipeline behind it.

  WHY THAT DIALECT WAS CHOSEN: `lvgl-codegen.core/process-screen` validates
  at every stage before a byte is emitted — schema, component resolution,
  semantic validation, asset-path existence, token expansion, font-reference
  resolution, renderer-capacity headroom, and proto IR validation. The font
  stage alone catches a class that would otherwise fall back silently to a
  default face and be visible only as 'the text looks wrong'. The devcards
  builder has closed-key asserts and nothing else.

  The stage list is NOT re-counted here: `process-screen` is the one home and
  a number in this docstring would drift the moment a stage is added — which
  is exactly what happened once, when a headroom check was missing from
  `stage-classification` and its refusals landed on the fallback code.

  THE PIPELINE IS CALLED, NOT REIMPLEMENTED. Running its stages individually
  here would give exact error attribution and would be a second copy of a
  sequence whose whole value is that there is one. Instead `process-screen` is
  called whole and its ex-info is CLASSIFIED — the message and ex-data are
  always carried through verbatim, so an unclassified failure still reaches
  the author in full, merely under a generic code. The classification is a
  convenience; the message is the truth.

  PATHS ARE RESOLVED ABSOLUTELY. renderer-gen's `:codegen` alias documents
  that its CWD must be `tools/renderer-gen`, because it reads `edn/` and
  `assets/` relative to the process. A long-lived daemon cannot lean on
  per-request process CWD, so every input path is passed explicitly."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [lvgl-codegen.component :as component]
   [lvgl-codegen.core :as codegen]
   [malli.error :as me]
   [scratchcard.digest :as digest])
  (:import
   (java.io File PushbackReader)))

(def stage-classification
  "Maps a pipeline failure to a typed code, most specific first.

  DATA, not a `cond`, so a message change is a one-line edit and the fallback
  is visible. Each entry is [pattern code stage-description]. The patterns
  match `lvgl-codegen`'s own throw messages; if one drifts, the failure still
  reaches the caller under `fallback-code` with its message intact — degraded
  attribution, never a swallowed error."
  [[#"(?i)screen validation failed" "INPUT_SCHEMA_INVALID" "Malli schema"]
   [#"(?i)screen semantic validation failed" "INPUT_SEMANTICS_INVALID" "semantic validation"]
   [#"(?i)asset reference" "INPUT_ASSET_MISSING" "asset-path existence"]
   [#"(?i)font reference" "INPUT_FONT_UNRESOLVED" "font-reference resolution"]
   [#"(?i)proto ir failed" "INPUT_IR_INVALID" "proto IR validation"]
   [#"(?i)class-defs macro" "INPUT_EXPAND_FAILED" "token/class expansion"]
   [#"(?i)component" "INPUT_COMPONENT_UNRESOLVED" "component resolution"]
   ;; `renderer-caps/check-headroom!` — a CAPACITY refusal, not a schema one.
   ;; Without this row it landed on `fallback-code` and was reported as
   ;; INPUT_SCHEMA_INVALID, sending an author to look at their schema for a
   ;; screen that is simply too big.
   [#"(?i)headroom|renderer-caps" "INPUT_CAPACITY_EXCEEDED" "renderer-capacity headroom"]])

(def fallback-code
  "Used when no pattern matches. A generic code with the real message beats a
  confident wrong one."
  "INPUT_SCHEMA_INVALID")

(defn classify
  "The typed code and stage description for a pipeline exception."
  [^Throwable e]
  (let [msg (str (ex-message e))]
    (or (some (fn [[pattern code stage]]
                (when (re-find pattern msg) {:code code :stage stage}))
              stage-classification)
        {:code fallback-code :stage "unattributed"})))

(def max-error-chars
  "Ceiling on the rendered explanation carried back to a caller."
  4000)

(defn readable-errors
  "A compact, readable form of a pipeline `:errors` value.

  MEASURED, AND THIS IS WHY IT EXISTS: `lvgl-codegen.schema/validate-screen`
  returns a raw `malli.core/explain`, whose `:schema` entry is the ENTIRE
  screen schema. Printed verbatim for one missing `:type :screen` key it came
  to 427 KB — every predicate, every nested exemption rationale, every
  function object. For a human that is unreadable; for the model this tool is
  built to serve it is worse than unreadable, because it would evict the rest
  of the context that explains what the screen was trying to do.

  `malli.error/humanize` reduces it to the shape of the input with a message
  at each bad path, which is the part an author can act on. The result is then
  hard-capped: an explanation that needs more than `max-error-chars` is
  telling you to look at the file, not to keep reading."
  [errors]
  (when errors
    ;; `me/humanize` returns nil for anything that is not an explain map, and
    ;; NIL WOULD DISCARD THE ERROR ENTIRELY — the one outcome worse than a
    ;; verbose one. Fall back to the raw value, then truncate it.
    (let [humanized (or (try (me/humanize errors) (catch Exception _ nil))
                        errors)
          s (pr-str humanized)]
      (if (> (count s) max-error-chars)
        (str (subs s 0 max-error-chars)
             " …TRUNCATED at " max-error-chars " chars; "
             "the full explanation is larger than it is useful")
        humanized))))

(defn- fail!
  [code message extra]
  (throw (ex-info message (assoc extra :error code))))

(defn read-screen
  "Parse the EDN screen at `path`.

  `clojure.edn/read` — never `read-string`, and never `load-file`. Screens are
  data; `#=()` eval forms are structurally unreadable to this reader."
  [path]
  (let [f (io/file path)]
    (when-not (.isFile ^File f)
      ;; NAME THE LIKELY CAUSE, not just the symptom. The daemon sees the
      ;; repository and its runtime dir and nothing else, so a path outside the
      ;; checkout is invisible to it EVEN THOUGH IT EXISTS on the host that
      ;; sent the request. "no screen file at /tmp/x.edn" is true and useless
      ;; when the caller can see /tmp/x.edn perfectly well.
      (fail! "INPUT_MISSING"
             (str "no screen file at " path
                  (when-not (re-find #"^/(home|workspace|srv|opt)?" (str path))
                    "")
                  " — the daemon can only see files INSIDE the checkout it was"
                  " started for; a path elsewhere on the host is not mounted"
                  " into it. Put the screen under the repo (anywhere, including"
                  " gitignored .protogen/) and pass that path.")
             {:path (str path)}))
    (try
      (with-open [r (PushbackReader. (io/reader f))]
        (edn/read r))
      (catch Exception e
        (fail! "INPUT_READ_FAILED"
               (str "screen at " path " is not readable EDN: " (ex-message e))
               {:path (str path)})))))

(defn defs-paths
  "Absolute paths to the authoring inputs, derived from the repo root.

  Named here rather than inherited from CWD — see the namespace docstring."
  [^String repo-root]
  {:tokens (str repo-root "/output/manifests/design-tokens.json")
   :components (str repo-root "/tools/renderer-gen/edn/components.edn")})

(defonce ^:private defs-cache
  (atom nil))

(defn load-defs
  "The token + component definitions the pipeline consumes.

  CONTENT-KEYED CACHE, the same shape `devcards.host` uses for the wasm: the
  key is the digest of both input files, so editing `components.edn` or
  regenerating `design-tokens.json` invalidates it automatically. A cache
  keyed on the PATH would serve a warm daemon stale definitions forever, and
  the symptom — a screen that renders with yesterday's tokens — looks like a
  renderer bug rather than a stale cache."
  [^String repo-root]
  (let [{:keys [tokens components]} (defs-paths repo-root)
        ;; `cache-key`, not `key` — binding `key` would shadow
        ;; `clojure.core/key`, the rename hazard this repo has been bitten by
        ;; twice: the linter stays green and a missed reference dies at runtime.
        cache-key [(digest/of-file tokens) (digest/of-file components)]]
    (or (when-let [{ck :key v :value} @defs-cache]
          (when (= ck cache-key) v))
        (let [defs (codegen/load-ui-defs tokens components nil)
              _ (codegen/validate-class-defs! defs)
              value {:tokens defs
                     :components (component/load-components (:components defs))}]
          (reset! defs-cache {:key cache-key :value value})
          value))))

(defn build!
  "Build `screen-path` into `.pb` bytes, writing the artifact to `out-pb`.

  Returns `{:bytes n :sha256 s :pb-path p :screen-sha256 s}`. Throws ex-info
  carrying `:error` (a typed code), `:stage`, and the pipeline's own message.

  The `.pb` is WRITTEN as well as returned because `process-screen` writes it
  — and keeping it in the run directory is wanted anyway: it is the exact
  input the renderer consumed, which makes a run reproducible without
  re-running the authoring pipeline."
  [{:keys [repo-root screen-path out-pb]}]
  (let [screen (read-screen screen-path)
        {:keys [tokens components]} (load-defs repo-root)]
    (io/make-parents (io/file out-pb))
    (try
      (let [n (codegen/process-screen tokens components screen (str out-pb))]
        {:bytes n
         :pb-path (str out-pb)
         :sha256 (digest/of-file out-pb)
         :screen-sha256 (digest/of-file screen-path)})
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [code stage]} (classify e)]
          (throw (ex-info (str "screen rejected at the " stage " stage: " (ex-message e))
                          (merge {:error code
                                  :stage stage
                                  :screen-path (str screen-path)
                                  :pipeline-message (ex-message e)}
                                 (when-let [errs (:errors (ex-data e))]
                                   {:errors (readable-errors errs)})))))))))
