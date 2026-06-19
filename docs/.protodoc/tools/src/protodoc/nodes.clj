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
  "snake_case → PascalCase, matching prost's oneof-variant naming from a FIELD
   name (\"set_clahe_level\" → \"SetClaheLevel\", \"rotary\" → \"Rotary\")."
  [s]
  (->> (str/split s #"_") (remove str/blank?) (map str/capitalize) (str/join "")))

(defn- to-upper-camel
  "heck `to_upper_camel_case` of a (Pascal/acronym) MESSAGE name — prost's struct
   naming. camel-words splits on case + acronym-run boundaries (\"DisableDDE\" →
   [Disable DDE], \"StartALl\" → [Start A Ll], \"ShowLRFMeasureScreen\" → [Show
   LRF Measure Screen]); capitalize-first/lower-rest each yields \"DisableDde\" /
   \"StartALl\" / \"ShowLrfMeasureScreen\" — exactly what prost emits."
  [s]
  (->> (camel-words s) (map str/capitalize) (str/join "")))

(defn- oneof-command-leaves
  "Traverse the cmd oneof graph and return one arm-spec per command LEAF reachable
   as `cmd.Root.payload → <Subsystem>.Root.cmd → <Leaf>` whose message satisfies
   `leaf-pred`.

   prost variant names come from the ONEOF FIELD names (not the message/id): the
   payload field (`rotary`→`Rotary`), the subsystem module (the Root's package
   segment, `cmd.RotaryPlatform`→`rotary_platform`), and the cmd field
   (`set_clahe_level`→`SetClaheLevel`). Non-leaf messages (e.g. a shared `Offset`
   used inside `Zoom`) are excluded by construction — nothing in a cmd oneof
   references them."
  [db leaf-pred]
  (let [msgs (:messages db)
        root (get msgs "cmd.Root")
        payload-field (into {}
                            (for [f (:fields root)
                                  :when (and (= :message (:type f))
                                             (str/ends-with? (str (:type-ref f)) ".Root"))]
                              [(:type-ref f) (:name f)]))]
    (->> (for [[root-id pay-fname] payload-field
               :let [subsystem-root (get msgs root-id)
                     module (camel->snake (nth (str/split root-id #"\.") 1))]
               cf (:fields subsystem-root)
               :when (= :message (:type cf))
               :let [cmd-msg (get msgs (:type-ref cf))]
               :when (and cmd-msg (leaf-pred cmd-msg))]
           {:command-id (:id cmd-msg)
            :payload-variant (snake->pascal pay-fname)
            :module module
            :cmd-variant (snake->pascal (:name cf))
            ;; The oneof VARIANT is the field name PascalCased (above); the
            ;; message STRUCT is heck to_upper_camel of the MESSAGE name — and the
            ;; two DIFFER when the field name isn't the snake of the message name
            ;; (field `geodesic_mode_enable` → message `EnableGeodesicMode`; field
            ;; `start_calibrate_long` → message `CalibrateStartLong`).
            :struct (to-upper-camel (:name cmd-msg))
            :field (when-let [vf (single-field cmd-msg)] (rust-field-ident (:name vf)))})
         (sort-by :command-id)
         vec)))

(defn set-value-command-arms
  "Arm-specs for single-`:double`-field command leaves (the slider kind's set-value
   commands)."
  [db]
  (oneof-command-leaves db (fn [m] (let [f (single-field m)]
                                     (and f (= :double (:type f)))))))

(defn action-command-arms
  "Arm-specs for parameterless (0-field) command leaves (the action-button kind's
   commands — Start / Stop / Photo / …)."
  [db]
  (oneof-command-leaves db (fn [m] (empty? (:fields m)))))

(defn set-enum-command-arms
  "Arm-specs for single-`:enum`-field command leaves (the enum-picker kind's
   set-enum commands — SetFxMode / SetScanMode / …). The enum field is an `i32`
   in prost."
  [db]
  (oneof-command-leaves db (fn [m] (let [f (single-field m)]
                                     (and f (= :enum (:type f)))))))

(defn set-int-command-arms
  "Arm-specs for single-`:int32`-field command leaves (the shift-stepper kind's
   commands — sent with a ±step `i32` value)."
  [db]
  (oneof-command-leaves db (fn [m] (let [f (single-field m)]
                                     (and f (= :int32 (:type f)))))))

(defn set-bool-command-arms
  "Arm-specs for single-`:bool`-field command leaves (the bool-toggle kind's
   set commands — sent a `bool` value)."
  [db]
  (oneof-command-leaves db (fn [m] (let [f (single-field m)]
                                     (and f (= :bool (:type f)))))))

(defn- match-arm
  "One Rust match arm: command-id → typed `cmd::root::Payload` construction with
   `struct-body` as the command-message body (`{ value: value }` or `{}`)."
  [{:keys [command-id payload-variant module cmd-variant struct]} struct-body]
  (str "        \"" command-id "\" => cmd::root::Payload::" payload-variant
       "(cmd::" module "::Root {\n"
       "            cmd: Some(cmd::" module "::root::Cmd::" cmd-variant
       "(cmd::" module "::" struct " " struct-body ")),\n"
       "        }),"))

(defn- builder-fn
  "Emit a `pub fn <name>(<extra-args>) -> Option<cmd::Root>` whose match arms are
   `(arm-fn spec)`-rendered."
  [fn-name extra-args doc arms arm-fn]
  (str "/// " doc "\n"
       "pub fn " fn-name "(command_id: &str" extra-args ") -> Option<cmd::Root> {\n"
       "    let payload = match command_id {\n"
       (str/join "\n" (map arm-fn arms)) "\n"
       "        _ => return None,\n"
       "    };\n"
       "    Some(cmd::Root { payload: Some(payload), ..Default::default() })\n"
       "}\n"))

(defn- field-body
  "The command-message struct body `{ <field>: value }` for a single-field arm."
  [{:keys [field]}]
  (str "{ " field ": value }"))

(defn cmd-builders-rust
  "Emit cmd_builders.rs: typed `build_set_value_command` (single double field),
   `build_action_command` (parameterless), `build_set_enum_command` (single enum
   field), and `build_set_int_command` (single int32 field), over every
   oneof-reachable cmd leaf."
  [db]
  (let [sv (set-value-command-arms db)
        ab (action-command-arms db)
        se (set-enum-command-arms db)
        si (set-int-command-arms db)
        sb (set-bool-command-arms db)]
    {:rust (str "// @generated by protodoc.nodes (jettison_protogen) — DO NOT EDIT.\n"
                "// Typed command builders: command_id -> cmd::Root.\n"
                "// include!'d into jettison_view's `generated` module (cmd in scope).\n\n"
                (builder-fn "build_set_value_command" ", value: f64"
                            (str "Build a typed `cmd::Root` for a set-value command (single double\n"
                                 "/// field). Returns `None` for an unknown command id.")
                            sv #(match-arm % (field-body %)))
                "\n"
                (builder-fn "build_action_command" ""
                            (str "Build a typed `cmd::Root` for a parameterless action command.\n"
                                 "/// Returns `None` for an unknown command id.")
                            ab #(match-arm % "{}"))
                "\n"
                (builder-fn "build_set_enum_command" ", value: i32"
                            (str "Build a typed `cmd::Root` for a set-enum command (single enum\n"
                                 "/// field, i32). Returns `None` for an unknown command id.")
                            se #(match-arm % (field-body %)))
                "\n"
                (builder-fn "build_set_int_command" ", value: i32"
                            (str "Build a typed `cmd::Root` for a set-int command (single int32\n"
                                 "/// field — the shift-stepper's ±step). Returns `None` for an\n"
                                 "/// unknown command id.")
                            si #(match-arm % (field-body %)))
                "\n"
                (builder-fn "build_set_bool_command" ", value: bool"
                            (str "Build a typed `cmd::Root` for a set-bool command (single bool\n"
                                 "/// field — the bool-toggle's switch value). Returns `None` for\n"
                                 "/// an unknown command id.")
                            sb #(match-arm % (field-body %))))
     :set-value-count (count sv)
     :action-count (count ab)
     :set-enum-count (count se)
     :set-int-count (count si)
     :set-bool-count (count sb)}))

;; ============================================================================
;; ToggleControl pairing (Enable ↔ Disable parameterless command siblings)
;; ============================================================================

(defn- toggle-polarity
  "Strip an Enable/Disable verb (as prefix OR suffix) from a command NAME,
   returning `[base :on|:off]` or nil. `RecognitionModeEnable` → [\"RecognitionMode\"
   :on]; `EnableGeodesicMode` → [\"GeodesicMode\" :on]."
  [nm]
  (cond
    (str/ends-with? nm "Enable") [(subs nm 0 (- (count nm) 6)) :on]
    (str/ends-with? nm "Disable") [(subs nm 0 (- (count nm) 7)) :off]
    (str/starts-with? nm "Enable") [(subs nm 6) :on]
    (str/starts-with? nm "Disable") [(subs nm 7) :off]
    :else nil))

(defn derive-toggle-nodes
  "Pair `:ui-pattern :toggle` parameterless commands that differ only by
   Enable↔Disable (within one subsystem) into ToggleControl node IR entries.
   Both commands are parameterless, so they reuse `build_action_command`; only a
   `{:on :off}` group with BOTH polarities becomes a node."
  [db]
  (let [toggles (->> (vals (:messages db))
                     (filter #(and (= :toggle (get-in % [:interaction :ui-pattern]))
                                   (empty? (:fields %))
                                   (= 3 (count (str/split (:id %) #"\."))))))
        groups (reduce (fn [acc m]
                         (let [subsystem (nth (str/split (:id m) #"\.") 1)]
                           (if-let [[base pol] (toggle-polarity (:name m))]
                             (update acc [subsystem base] assoc pol (:id m))
                             acc)))
                       {} toggles)]
    (->> (for [[[subsystem base] pols] groups
               :when (and (:on pols) (:off pols))]
           {:id (str "toggle." subsystem "." base)
            :kind :toggle
            :title (->> (camel-words base) (str/join " "))
            :command-on (:on pols)
            :command-off (:off pols)})
         (sort-by :id)
         vec)))

;; ============================================================================
;; EnumPicker derivation (single-:enum-field set-enum commands + options)
;; ============================================================================

(defn- longest-common-prefix
  "Longest common character prefix shared by all strings (\"\" if none)."
  [strs]
  (if (seq strs)
    (->> (apply map vector strs)
         (take-while (fn [chars] (apply = chars)))
         (map first)
         (apply str))
    ""))

(defn- strip-enum-prefix
  "Drop the longest common `_`-terminated prefix shared by the enum value names,
   leaving the distinguishing label (`JON_GUI_DATA_FX_MODE_DAY_A` → `A`)."
  [names]
  (let [lcp (longest-common-prefix names)
        cut (if-let [i (str/last-index-of lcp "_")] (inc i) 0)]
    (mapv #(subs % cut) names)))

(defn derive-enum-picker-nodes
  "Derive EnumPicker nodes from single-`:enum`-field `:ui-pattern :enum-picker`
   commands. Options come from the enum's `:values` (prefix-stripped label + the
   enum number), in declaration order — an explicit index→value map."
  [db]
  (let [enums (:enums db)
        pickers (->> (vals (:messages db))
                     (filter #(and (= :enum-picker (get-in % [:interaction :ui-pattern]))
                                   (= 1 (count (:fields %)))
                                   (= :enum (:type (first (:fields %)))))))]
    (->> (for [m pickers
               :let [f (first (:fields m))
                     values (:values (get enums (:type-ref f)))]
               :when (seq values)
               :let [labels (strip-enum-prefix (mapv :name values))]]
           {:id (:id m)
            :kind :enum-picker
            :title (->> (camel-words (strip-prefix (:name m) "Set")) (str/join " "))
            :command-id (:id m)
            :options (mapv (fn [v lbl] {:label lbl :value (:number v)}) values labels)})
         (sort-by :id)
         vec)))

;; ============================================================================
;; StepperControl pairing (Plus ↔ Minus parameterless command siblings)
;; ============================================================================

(defn- stepper-polarity
  "Strip a step verb (Plus/Minus or Increase/Decrease, as a suffix) from a command
   NAME → `[base :inc|:dec]` or nil. `FocusStepPlus` → [\"FocusStep\" :inc]."
  [nm]
  (cond
    (str/ends-with? nm "Plus") [(subs nm 0 (- (count nm) 4)) :inc]
    (str/ends-with? nm "Minus") [(subs nm 0 (- (count nm) 5)) :dec]
    (str/ends-with? nm "Increase") [(subs nm 0 (- (count nm) 8)) :inc]
    (str/ends-with? nm "Decrease") [(subs nm 0 (- (count nm) 8)) :dec]
    :else nil))

(defn derive-stepper-nodes
  "Pair `:ui-pattern :stepper` parameterless commands that differ only by
   Plus↔Minus / Increase↔Decrease (within a subsystem) into StepperControl node IR
   entries. Both commands are parameterless (reuse `build_action_command`)."
  [db]
  (let [steppers (->> (vals (:messages db))
                      (filter #(and (= :stepper (get-in % [:interaction :ui-pattern]))
                                    (empty? (:fields %))
                                    (= 3 (count (str/split (:id %) #"\."))))))
        groups (reduce (fn [acc m]
                         (let [subsystem (nth (str/split (:id m) #"\.") 1)]
                           (if-let [[base pol] (stepper-polarity (:name m))]
                             (update acc [subsystem base] assoc pol (:id m))
                             acc)))
                       {} steppers)]
    (->> (for [[[subsystem base] pols] groups
               :when (and (:inc pols) (:dec pols))]
           {:id (str "stepper." subsystem "." base)
            :kind :stepper
            :title (->> (camel-words base) (str/join " "))
            :command-increment (:inc pols)
            :command-decrement (:dec pols)})
         (sort-by :id)
         vec)))

;; ============================================================================
;; ShiftStepper derivation (single-int32-field ±step commands)
;; ============================================================================

(def ^:private default-int-shift-step
  "Default ±delta an int32 shift-stepper button sends (raw units; 1 is the safe
   unit default — operator-tunable later via a `node-config.edn` step table)."
  1)

(def ^:private default-double-shift-step
  "Default ±delta a normalized-double shift-stepper button sends, in per-mille
   scaled units → 50 = ±0.05 (5%) per click. A sensible generic default for a
   `[-1,1]` normalized delta (per-command tuning is a later `node-config.edn`)."
  50)

(defn- shift-stepper-node
  "Build a ShiftStepper IR entry for command message `m` if it is a buildable
   single-`:int32`-field (raw ±step) OR single-`:normalized-double`-field (±step
   over a per-mille `scale`, like the slider) `:stepper` leaf; nil otherwise.
   Non-normalized doubles (`:angle`/`:raw`) have no derivable scale → skipped,
   exactly as the slider skips them."
  [m int-buildable dbl-buildable]
  (when-let [f (single-field m)]
    (let [title (->> (camel-words (strip-prefix (:name m) "Set")) (str/join " "))
          base {:id (:id m) :kind :shift-stepper :title title :command-id (:id m)}]
      (cond
        (and (= :int32 (:type f)) (int-buildable (:id m)))
        (assoc base :step default-int-shift-step)

        (and (= :double (:type f)) (dbl-buildable (:id m))
             (scale-for (get-in f [:interaction :semantic-type])))
        (assoc base
               :step default-double-shift-step
               :scale (scale-for (get-in f [:interaction :semantic-type])))

        :else nil))))

(defn derive-shift-stepper-nodes
  "Derive ShiftStepper nodes from single-field `:ui-pattern :stepper` commands —
   `:int32` (raw ±step) and normalized `:double` (±step over a per-mille scale).
   Only cmd-oneof-reachable leaves are emitted (unreachable/unbuildable ones are
   skipped), and non-normalized doubles (whose delta units have no generic
   default) are skipped just like the slider."
  [db]
  (let [int-buildable (set (map :command-id (set-int-command-arms db)))
        dbl-buildable (set (map :command-id (set-value-command-arms db)))]
    (->> (vals (:messages db))
         (filter #(= :stepper (get-in % [:interaction :ui-pattern])))
         (keep #(shift-stepper-node % int-buildable dbl-buildable))
         (sort-by :id)
         vec)))

;; ============================================================================
;; BoolToggle derivation (single-bool-field set commands)
;; ============================================================================

(defn derive-bool-toggle-nodes
  "Derive BoolToggle nodes from single-`:bool`-field `:ui-pattern :toggle`
   commands (oneof-reachable only). A switch sends the command carrying its bool
   value via `build_set_bool_command` (distinct from `ToggleControl`, which fires
   two PARAMETERLESS enable/disable commands)."
  [db]
  (let [buildable (set (map :command-id (set-bool-command-arms db)))]
    (->> (vals (:messages db))
         (filter #(and (= :toggle (get-in % [:interaction :ui-pattern]))
                       (when-let [f (single-field %)] (= :bool (:type f)))
                       (buildable (:id %))))
         (map (fn [m]
                {:id (:id m)
                 :kind :bool-toggle
                 :title (->> (camel-words (strip-prefix (:name m) "Set")) (str/join " "))
                 :command-id (:id m)}))
         (sort-by :id)
         vec)))

;; ============================================================================
;; Generation
;; ============================================================================

(defn derive-nodes
  "Pure transform: proto-db → sorted vector of node IR entries (slider + toggle +
   enum-picker kinds). A slider whose bindings can't be cleanly derived is
   SKIPPED-with-reason (loudly), never emitted as a degraded node and never
   aborting the rest of the run. Toggles are paired Enable↔Disable command
   siblings; enum-pickers are single-enum-field set commands. Remaining command
   leaves are counted only."
  [db]
  (let [msgs (vals (:messages db))
        sliders (filter slider-node? msgs)
        results (for [m sliders]
                  (try
                    {:node (derive-slider-node m)}
                    (catch clojure.lang.ExceptionInfo e
                      {:skipped {:id (:id m) :reason (.getMessage e)}})))
        toggles (derive-toggle-nodes db)
        pickers (derive-enum-picker-nodes db)
        steppers (derive-stepper-nodes db)
        shift-steppers (derive-shift-stepper-nodes db)
        bool-toggles (derive-bool-toggle-nodes db)]
    {:nodes (->> (concat (keep :node results) toggles pickers steppers shift-steppers
                         bool-toggles)
                 (sort-by :id) vec)
     :skipped (->> results (keep :skipped) (sort-by :id) vec)
     :slider-count (count (keep :node results))
     :toggle-count (count toggles)
     :bool-toggle-count (count bool-toggles)
     :enum-picker-count (count pickers)
     :stepper-count (count steppers)
     :shift-stepper-count (count shift-steppers)
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
        {:keys [nodes skipped slider-count toggle-count bool-toggle-count
                enum-picker-count stepper-count shift-stepper-count
                non-slider-count]} (derive-nodes db)
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
    (let [{:keys [rust set-value-count action-count set-enum-count set-int-count
                  set-bool-count]}
          (cmd-builders-rust db)
          cf (io/file out-dir "cmd_builders.rs")]
      (spit cf rust)
      (t/log! :info ["Wrote" (.getPath cf) "with" set-value-count "set-value +"
                     action-count "action +" set-enum-count "set-enum +" set-int-count
                     "set-int +" set-bool-count "set-bool command builder(s)"]))
    (doseq [{:keys [id reason]} skipped]
      (t/log! :warn ["Skipped slider node" id "—" reason]))
    (t/log! :info ["Generated nodes:" slider-count "slider +" toggle-count "toggle +"
                   bool-toggle-count "bool-toggle +" enum-picker-count "enum-picker +"
                   stepper-count "stepper +" shift-stepper-count "shift-stepper ("
                   (count nodes) "total)," (count skipped) "slider(s) skipped,"
                   non-slider-count "non-slider message(s)"])
    manifest))
