(ns protodoc.nodes
  "Generate the layered LVGL node codegen IR (nodes.json) from proto-db.edn.

   Each L3 control node is derived purely from the proto + its `:interaction`
   overlay — \"add a node = add metadata\". A command-leaf message whose
   message-level `:interaction` is `:ui-pattern :slider` and whose single field
   is a `:semantic-type :normalized` double becomes a SliderControl node:

     state_field_path  ← related-state leaf (strip \"JonGuiData\", snake) + \".\"
                          + the command field (strip \"Set\", snake)
     scale             ← field :semantic-type (:normalized → 1000 per-mille)
     min/max           ← field {:gte :lte} constraints × scale
     presets           ← field :interaction :presets × scale
     command_id        ← the message :id (deterministic, unambiguous)
     title             ← the message :name minus a Set/Shift verb, camel-split

   Mirrors manifest.clj: pure db-in / json-out, wired into core.clj's command
   `case` + a `docs-nodes` make target. Non-slider / non-normalized command
   leaves are skipped (logged) until their kind lands — never silently emitted
   as a degraded node."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.telemere :as t]))

;; ============================================================================
;; Name derivation (camelCase ↔ snake_case, leaf extraction)
;; ============================================================================

(defn leaf
  "Last dotted segment of a fully-qualified id (\"cmd.DayCamera.SetClaheLevel\"
   → \"SetClaheLevel\"; \"ser.JonGuiDataCameraDay\" → \"JonGuiDataCameraDay\")."
  [id]
  (last (str/split id #"\.")))

(defn camel-words
  "Split a CamelCase / PascalCase identifier into its word parts
   (\"SetClaheLevel\" → [\"Set\" \"Clahe\" \"Level\"])."
  [s]
  (->> (str/split s #"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
       (remove str/blank?)))

(defn camel->snake
  "Convert CamelCase to snake_case (\"CameraDay\" → \"camera_day\")."
  [s]
  (->> (camel-words s) (map str/lower-case) (str/join "_")))

(defn strip-prefix
  "Drop `prefix` from the front of `s` when present, else return `s`."
  [s prefix]
  (if (str/starts-with? s prefix) (subs s (count prefix)) s))

;; ============================================================================
;; Fixed-point scale (semantic-type → per-mille multiplier)
;; ============================================================================

;; A `:normalized` [0,1] double crosses the int-only WASM ABI at per-mille
;; (×1000). Other semantic-types (angle / temperature / voltage) need their own
;; entry; an unmapped one is a generator error (no silent ×1 that would
;; mis-scale), mirroring parse.clj's THROW-on-unknown-constraint posture.
(def ^:private scale-by-semantic-type
  {:normalized 1000})

(defn scale-for
  "Per-mille scale for a field's `:semantic-type`, or nil when unmapped."
  [semantic-type]
  (get scale-by-semantic-type semantic-type))

;; ============================================================================
;; Kind classification + node derivation
;; ============================================================================

(defn- single-field
  "The lone field of a single-field command message, or nil."
  [msg]
  (when (= 1 (count (:fields msg)))
    (first (:fields msg))))

(defn slider-node?
  "True when `msg` is a single-`:normalized`-double-field command leaf whose
   message-level `:interaction` is `:ui-pattern :slider`."
  [msg]
  (let [f (single-field msg)]
    (boolean
     (and (= :slider (get-in msg [:interaction :ui-pattern]))
          f
          (= :double (:type f))
          (= :normalized (get-in f [:interaction :semantic-type]))))))

(defn derive-slider-node
  "Derive the SliderControl node IR entry from a slider command-leaf `msg`.
   Throws ex-info when a load-bearing input is missing (fail-fast, never a
   degraded node)."
  [msg]
  (let [f (single-field msg)
        st (get-in f [:interaction :semantic-type])
        scale (scale-for st)
        related (first (get-in msg [:interaction :related-state]))
        cons (:constraints f)
        ;; accept inclusive (:gte/:lte) or exclusive (:gt/:lt) bounds
        min-bound (or (:gte cons) (:gt cons))
        max-bound (or (:lte cons) (:lt cons))]
    (when-not scale
      (throw (ex-info "unmapped :semantic-type for slider scale"
                      {:message (:id msg) :semantic-type st})))
    (when-not related
      (throw (ex-info "slider node has no :related-state"
                      {:message (:id msg)})))
    (when-not (and min-bound max-bound)
      (throw (ex-info "slider field lacks min/max (:gte/:gt + :lte/:lt) constraints"
                      {:message (:id msg) :constraints cons})))
    (let [;; "ser.JonGuiDataCameraDay" → "camera_day"
          container (-> related leaf (strip-prefix "JonGuiData") camel->snake)
          ;; "SetClaheLevel" → "clahe_level"
          field-name (-> (:name msg) (strip-prefix "Set") camel->snake)
          state-path (str container "." field-name)
          ;; "SetClaheLevel" → "Clahe Level"
          title (->> (camel-words (:name msg)) (remove #{"Set" "Shift"}) (str/join " "))
          presets (->> (get-in f [:interaction :presets])
                       (mapv #(long (Math/round (* (double %) (double scale))))))]
      {:id (:id msg)
       :kind :slider
       :title title
       :state-field-path state-path
       :subject state-path
       :command-id (:id msg)
       :scale scale
       :min-value (long (Math/round (* (double min-bound) (double scale))))
       :max-value (long (Math/round (* (double max-bound) (double scale))))
       :presets presets})))

;; ============================================================================
;; Typed command builders (command_id → cmd::Root) — Rust codegen
;; ============================================================================

;; The production command path stays TYPED (no runtime reflection): the slider
;; command-builder delegates to a generated match over every set-value command
;; leaf. This emits that match as Rust, include!'d into jettison_view's
;; `generated` module (where `cmd` + its 14 per-subsystem children are in scope).

(def ^:private rust-keywords
  #{"as" "break" "const" "continue" "crate" "dyn" "else" "enum" "extern" "false"
    "fn" "for" "if" "impl" "in" "let" "loop" "match" "mod" "move" "mut" "pub"
    "ref" "return" "self" "static" "struct" "super" "trait" "true" "type"
    "unsafe" "use" "where" "while" "async" "await" "box" "do" "final" "macro"
    "override" "priv" "typeof" "unsized" "virtual" "yield" "try"})

(defn- rust-field-ident
  "prost raw-identifies Rust-keyword field names (`r#type`); others pass through."
  [field-name]
  (if (rust-keywords field-name) (str "r#" field-name) field-name))

(defn- snake->pascal
  "snake_case → PascalCase, matching prost's oneof-variant naming
   (\"set_clahe_level\" → \"SetClaheLevel\", \"rotary\" → \"Rotary\")."
  [s]
  (->> (str/split s #"_") (remove str/blank?) (map str/capitalize) (str/join "")))

(defn set-value-command-arms
  "Traverse the cmd oneof graph and return one arm-spec per single-`:double`-field
   command LEAF reachable as `cmd.Root.payload → <Subsystem>.Root.cmd → <Leaf>`.

   prost variant names come from the ONEOF FIELD names (not the message/id), so
   we read them from the descriptor: the payload field (`rotary`→`Rotary`), the
   subsystem module (the Root's package segment, `cmd.RotaryPlatform`→`rotary_platform`),
   and the cmd field (`set_clahe_level`→`SetClaheLevel`). Non-leaf single-double
   messages (e.g. a shared `Offset` used inside `Zoom`) are excluded by
   construction — they are not referenced by any cmd-oneof field."
  [db]
  (let [msgs (:messages db)
        root (get msgs "cmd.Root")
        ;; payload oneof: subsystem-Root id → payload field name
        payload-field (into {}
                            (for [f (:fields root)
                                  :when (and (= :message (:type f))
                                             (str/ends-with? (str (:type-ref f)) ".Root"))]
                              [(:type-ref f) (:name f)]))]
    (->> (for [[root-id pay-fname] payload-field
               :let [subsystem-root (get msgs root-id)
                     ;; "cmd.RotaryPlatform.Root" → module "rotary_platform"
                     module (camel->snake (nth (str/split root-id #"\.") 1))]
               cf (:fields subsystem-root)
               :when (= :message (:type cf))
               :let [cmd-msg (get msgs (:type-ref cf))
                     vf (single-field cmd-msg)]
               :when (and cmd-msg vf (= :double (:type vf)))]
           {:command-id (:id cmd-msg)
            :payload-variant (snake->pascal pay-fname)
            :module module
            :cmd-variant (snake->pascal (:name cf))
            :struct (:name cmd-msg)
            :field (rust-field-ident (:name vf))})
         (sort-by :command-id)
         vec)))

(defn cmd-builders-rust
  "Emit the full cmd_builders.rs: a typed `build_set_value_command` match over
   every oneof-reachable single-`:double`-field cmd leaf."
  [db]
  (let [arms (set-value-command-arms db)
        rust-arms (->> arms
                       (map (fn [{:keys [command-id payload-variant module cmd-variant struct field]}]
                              (str "        \"" command-id "\" => cmd::root::Payload::"
                                   payload-variant "(cmd::" module "::Root {\n"
                                   "            cmd: Some(cmd::" module "::root::Cmd::"
                                   cmd-variant "(cmd::" module "::" struct
                                   " { " field ": value })),\n"
                                   "        }),")))
                       (str/join "\n"))]
    {:rust (str "// @generated by protodoc.nodes (jettison_protogen) — DO NOT EDIT.\n"
                "// Typed set-value command builders: command_id -> cmd::Root.\n"
                "// include!'d into jettison_view's `generated` module (cmd in scope).\n\n"
                "/// Build a typed `cmd::Root` for a set-value command (single double\n"
                "/// field). Returns `None` for an unknown command id.\n"
                "pub fn build_set_value_command(command_id: &str, value: f64)"
                " -> Option<cmd::Root> {\n"
                "    let payload = match command_id {\n"
                rust-arms "\n"
                "        _ => return None,\n"
                "    };\n"
                "    Some(cmd::Root {\n"
                "        payload: Some(payload),\n"
                "        ..Default::default()\n"
                "    })\n"
                "}\n")
     :count (count arms)}))

;; ============================================================================
;; Generation
;; ============================================================================

(defn derive-nodes
  "Pure transform: proto-db → sorted vector of node IR entries (slider kind
   only for now). A slider whose bindings can't be cleanly derived is
   SKIPPED-with-reason (loudly), never emitted as a degraded node and never
   aborting the rest of the run. Non-slider command leaves are counted only."
  [db]
  (let [msgs (vals (:messages db))
        sliders (filter slider-node? msgs)
        results (for [m sliders]
                  (try
                    {:node (derive-slider-node m)}
                    (catch clojure.lang.ExceptionInfo e
                      {:skipped {:id (:id m) :reason (.getMessage e)}})))]
    {:nodes (->> results (keep :node) (sort-by :id) vec)
     :skipped (->> results (keep :skipped) (sort-by :id) vec)
     :non-slider-count (- (count msgs) (count sliders))}))

(defn generate-nodes
  "Read proto-db.edn, derive the node IR, and write nodes.json.

   Options:
   - :db-path    — path to proto-db.edn
   - :output-dir — directory for nodes.json
   - :git-sha    — optional git commit SHA for metadata"
  [{:keys [db-path output-dir git-sha]
    :or {db-path "docs/.protodoc/proto-db.edn"
         output-dir "output/nodes"}}]
  (let [db (edn/read-string (slurp db-path))
        {:keys [nodes skipped non-slider-count]} (derive-nodes db)
        manifest {:version "1.0.0"
                  :generated-at (str (java.time.Instant/now))
                  :protogen-commit (or git-sha "unknown")
                  :nodes nodes
                  :skipped skipped}
        out-dir (io/file output-dir)]
    (.mkdirs out-dir)
    (let [f (io/file out-dir "nodes.json")]
      (spit f (json/write-str manifest
                              :key-fn #(if (keyword? %)
                                         (cond-> (name %)
                                           (namespace %) (->> (str (namespace %) "/")))
                                         (str %))
                              :escape-slash false))
      (t/log! :info ["Wrote" (.getPath f)]))
    (let [{:keys [rust count]} (cmd-builders-rust db)
          cf (io/file out-dir "cmd_builders.rs")]
      (spit cf rust)
      (t/log! :info ["Wrote" (.getPath cf) "with" count "set-value command builder(s)"]))
    (doseq [{:keys [id reason]} skipped]
      (t/log! :warn ["Skipped slider node" id "—" reason]))
    (t/log! :info ["Generated nodes:" (count nodes) "slider node(s),"
                   (count skipped) "slider(s) skipped,"
                   non-slider-count "non-slider message(s)"])
    manifest))
