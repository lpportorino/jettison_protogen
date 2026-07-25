(ns uigen.resolve
  "Join layer (compose-don't-re-derive): resolve a node's bindings against
   the EXISTING protogen manifests (`endpoints.json`, `signals.json` via
   `asgard.manifest`) rather than re-deriving them. This is what lets
   a node entry stay a pure classification+bindings manifest while uigen
   pulls the raw field metadata (semantic-type / constraints / presets) it
   needs to compute render params.

     command-id  → endpoint {:path :subsystem :command :root-id :oneof-field}
                   + primary-field {:name :type :semantic-type :constraints
                                    :presets :unit :precision :display-format}
     state-field → signal {:signal-name :constraints :type}

   The endpoint :path (e.g. \"cmd/day-camera/set-clahe-level\") is the route the
   application layer exposes. The LVGL emitter pre-encodes cmd.* itself
   (uigen.cmd-spec) and the renderer relays opaque bytes over host_command; the
   route also supplies the subsystem/command split for that pre-encode
   (subsystem + cmd-path split)."
  (:require [asgard.manifest :as mf]
            [asgard.schema :as s]
            [clojure.string :as str]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

(def ^:private !channel-split-ids
  "The command-ids that are CHANNEL-SPLIT: they appear in endpoints.json MORE THAN
   ONCE, under the SAME :id but a DISTINCT :oneof-field — the channel is the OUTER
   oneof arm (cmd.Lrf_calib.{Set,Shift,Save,Reset}Offsets as day.set + heat.set,
   …), a genuinely different device command per channel. This is DISTINCT from a
   focus/zoom PATH-variant of ONE command (cmd.DayCamera.Halt via focus/ + zoom/),
   whose duplicate entries share the SAME :oneof-field — those stay a single node
   (uigen.derive/distinct-by-id). Delay-cached."
  (delay (->> (:endpoints (mf/endpoints))
              (group-by :id)
              (keep (fn [[id group]]
                      (when (> (count (distinct (map :oneof-field group))) 1) id)))
              set)))

(defn channel-split-id?
  "True when `command-id` is channel-split (appears in >1 endpoint with distinct
   :oneof-fields — the outer oneof channel, e.g. cmd.Lrf_calib.SetOffsets)."
  [command-id]
  (contains? @!channel-split-ids command-id))
(m/=> channel-split-id? [:=> [:cat s/ne-string] :boolean])

(defn endpoint-channel
  "The outer-oneof CHANNEL of a channel-split endpoint `ep` — its :oneof-field's
   first dotted segment (\"day.set\" → \"day\", \"heat.shift\" → \"heat\") — or nil
   for a non-split endpoint. The channel that distinguishes one device command from
   its sibling on the other optical channel."
  [ep]
  (when (channel-split-id? (:id ep))
    (first (str/split (:oneof-field ep) #"\."))))
(m/=> endpoint-channel [:=> [:cat [:map-of :keyword :any]] [:maybe s/ne-string]])

(defn channel-qualified-id
  "The command-id under which endpoint `ep` is indexed AND the id of its derived
   per-channel node: `<id>.<channel>` for a channel-split endpoint
   (cmd.Lrf_calib.SetOffsets.day / .heat — so each channel is its OWN screen with
   its OWN route + pre-encode), or the plain :id for a non-split endpoint. The ONE
   home for the qualification, shared by the endpoint index (below) and the node
   derivation (uigen.derive) so their ids agree."
  [ep]
  (if-let [ch (endpoint-channel ep)] (str (:id ep) "." ch) (:id ep)))
(m/=> channel-qualified-id [:=> [:cat [:map-of :keyword :any]] s/ne-string])

(def ^:private !endpoint-index
  "command-id → endpoints.json entry (delay-cached). A channel-split command
   (channel-split-id?) is ADDITIONALLY keyed under its channel-qualified-id
   (cmd.Lrf_calib.SetOffsets.day / .heat) so a per-channel node resolves its OWN
   route/fields/pre-encode. The plain :id key stays (last-wins, unchanged) for any
   residual lookup; a channel-split node addresses the qualified key exclusively."
  (delay
    (reduce (fn [acc ep]
              (cond-> (assoc acc (:id ep) ep)
                (channel-split-id? (:id ep)) (assoc (channel-qualified-id ep) ep)))
            {}
            (:endpoints (mf/endpoints)))))

(def ^:private !signal-index
  "[subsystem-field field-name] → signals.json entry. Delay-cached."
  (delay (into {}
               (map (fn [sig] [[(:subsystem-field sig) (:field-name sig)] sig]))
               (:signals (mf/signals)))))

(def ^:private !signal-by-message-index
  "proto-message → vector of its signals.json entries (sorted by proto field
   number for a stable readout order). Delay-cached."
  (delay (into {}
               (map (fn [[msg sigs]] [msg (vec (sort-by :proto-field-number sigs))]))
               (group-by :proto-message (:signals (mf/signals))))))

(defn endpoint
  "The endpoints.json entry for `command-id`, or nil."
  [command-id]
  (get @!endpoint-index command-id))
(m/=> endpoint [:=> [:cat s/ne-string] [:maybe [:map-of :keyword :any]]])

(defn command-route
  "POST route path for `command-id` (e.g. \"cmd/day-camera/set-clahe-level\"),
   or nil. It is shared by application requests and native command pre-encoding."
  [command-id]
  (:path (endpoint command-id)))
(m/=> command-route [:=> [:cat s/ne-string] [:maybe s/ne-string]])

(defn command-subsystem
  "Subsystem segment of `command-id`'s route (e.g. \"day-camera\"), or nil.
   Derived from the route path \"cmd/<subsystem>/<command>\"."
  [command-id]
  (when-let [p (command-route command-id)] (second (str/split p #"/"))))
(m/=> command-subsystem [:=> [:cat s/ne-string] [:maybe s/ne-string]])

(defn primary-field
  "The single primary input field of `command-id`'s endpoint (first field),
   with its interaction metadata flattened, or nil for a parameterless
   command. Keys: {:name :type :constraints :semantic-type :presets :unit
   :precision :display-format}."
  [command-id]
  (when-let [f (first (:fields (endpoint command-id)))]
    (let [i (:interaction f)]
      {:name (:name f)
       :type (:type f)
       :constraints (:constraints f)
       :semantic-type (:semantic-type i)
       :presets (:presets i)
       :unit (:unit i)
       :precision (:precision i)
       :display-format (:display-format i)})))
(m/=> primary-field [:=> [:cat s/ne-string] [:maybe [:map-of :keyword :any]]])

(defn- humanize-label
  "Prefix-stripped enum short name → human dropdown label: underscores to
   spaces + per-word title-case (\"MODE_1\" → \"Mode 1\", \"1_HZ_CONTINUOUS\" →
   \"1 Hz Continuous\"). Display-side ONLY — the `:match-key`/`:value` carriers
   keep the raw short name / number, so command output and live-signal matching
   never depend on the display form."
  [short-name]
  (->> (str/split short-name #"_")
       (map str/capitalize)
       (str/join " ")))
(m/=> humanize-label [:=> [:cat s/ne-string] s/ne-string])

(defn enum-options
  "Offered enum options for command `cid`'s enum field `f` (a field map with
   `:name` + `:constraints`): the enum's values as prefix-stripped, HUMANIZED
   labels (humanize-label) paired with their numbers, MINUS the
   `_UNSPECIFIED`-by-name value AND any value the field's buf.validate `:not-in`
   forbids. (E.g. `SetFxMode.mode` is `not-in:[0]`, so the DEFAULT=0 value — NOT
   named `_UNSPECIFIED`, so it survives the name drop — must still be excluded,
   or the command would offer a value cmd_server rejects.) The enum type resolves
   from the FIELD's own `:type-ref` when the field map carries one (a proto-db
   flattened field — a nested/oneof arm scalar, whose owning message is NOT
   `cid`), else by field-name lookup on `cid` itself; an enum field that resolves
   to NO values FAILS LOUD — an empty dropdown is a stub-options dead control,
   never a silent degrade. Single source for both the standalone enum-picker
   (uigen.derive) and the composite collecting-select (uigen.lower). Each option
   is `{:label :value :match-key}` — see the `:match-key` note in the body."
  [cid f]
  (let [type-ref (or (:type-ref f) (mf/field-enum-type-ref cid (:name f)))
        not-in (set (:not-in (:constraints f)))
        all-values (get-in (mf/proto-db) [:enums type-ref :values])
        _ (when (empty? all-values)
            (throw (ex-info (str "uigen.resolve/enum-options: enum field "
                                 (pr-str (:name f))
                                 " of "
                                 cid
                                 " resolves no enum values (type-ref "
                                 (pr-str type-ref)
                                 ") — an empty dropdown is a dead control")
                            {:command-id cid :field (:name f) :type-ref type-ref})))
        ;; :match-key = the enum's prefix-stripped short name over ALL values —
        ;; the SAME string the live enum signal's display formatter delivers. The
        ;; enum NUMBER (:value) can't match that display string; keeping
        ;; both representations lets native controls select the active option and
        ;; emit the numeric command value. ALL-values basis (not the
        ;; offered subset) so it equals the signal's basis regardless of :not-in.
        num->key (zipmap (map :number all-values)
                         (mf/strip-common-prefix (mapv :name all-values)))
        values (->> all-values
                    (remove #(str/ends-with? (:name %) "_UNSPECIFIED"))
                    (remove #(contains? not-in (:number %))))
        labels (mapv humanize-label (mf/strip-common-prefix (mapv :name values)))]
    (mapv (fn [v lbl] {:label lbl :value (:number v) :match-key (num->key (:number v))})
          values
          labels)))
(m/=> enum-options
      [:=> [:cat s/ne-string [:map-of :keyword :any]]
       [:sequential [:map-of :keyword :any]]])

(defn video-channel-value
  "The `ser.JonGuiDataVideoChannel` enum NUMBER whose name ends `_<suffix>`
   (\"DAY\" → 2, \"HEAT\" → 1), resolved from the `cmd.CV.SetAutoFocus` `channel`
   field — no magic number, self-correcting if the enum renumbers. The one home
   for the video-channel lookup: the day/heat palette AND the lvgl gesture-surface
   emitter (which pins a pane's gesture commands to its channel) both resolve
   here. Fail-loud when the suffix names no channel."
  [suffix]
  (let [tref (mf/field-enum-type-ref "cmd.CV.SetAutoFocus" "channel")]
    (or (->> (get-in (mf/proto-db) [:enums tref :values])
             (some #(when (str/ends-with? (:name %) (str "_" suffix)) (:number %))))
        (throw (ex-info (str "uigen.resolve: no video channel " suffix)
                        {:suffix suffix})))))
(m/=> video-channel-value [:=> [:cat s/ne-string] :int])

(defn all-fields
  "Every input field of `command-id`'s endpoint, each flattened like
   primary-field (same key set). A single-field command returns a 1-vec; a
   multi-field command (composite) returns all its fields in proto order; a
   parameterless command returns an empty vec. Composites need all fields, not
   just the first."
  [command-id]
  (mapv (fn [f]
          (let [i (:interaction f)]
            {:name (:name f)
             :type (:type f)
             :constraints (:constraints f)
             :semantic-type (:semantic-type i)
             :presets (:presets i)
             :unit (:unit i)
             :precision (:precision i)
             :display-format (:display-format i)}))
        (:fields (endpoint command-id))))
(m/=> all-fields [:=> [:cat s/ne-string] [:sequential [:map-of :keyword :any]]])

(def ^:private sync-field-names
  "uint64/int64 fields the host/runtime fills on commit — never an input widget."
  #{"frame_time" "state_time"})

(defn widget-field?
  "True when a command field is an operator-facing INPUT (gets a widget), vs a
   host-filled sync field. Excludes frame_time/state_time, semantic-type
   timestamp, nested message fields, and bare uint64/int64 with no semantic-type.
   Shared by the composite deriver (which-commands-are-composites) and the
   composite lowerer (which-fields-get-widgets) so the two never drift."
  [f]
  (let [t (:type f)
        nm (:name f)
        st (:semantic-type f)]
    (not (or (= "message" t)
             (contains? sync-field-names nm)
             (= "timestamp" st)
             (and (#{"uint64" "int64"} t) (nil? st))))))
(m/=> widget-field? [:=> [:cat [:map-of :keyword :any]] :boolean])

(defn widget-fields
  "The operator-facing input fields of `command-id` — all-fields minus host-filled
   sync/nested fields. The fields a composite renders as collecting widgets."
  [command-id]
  (filterv widget-field? (all-fields command-id)))
(m/=> widget-fields [:=> [:cat s/ne-string] [:sequential [:map-of :keyword :any]]])

(defn signal-for
  "Resolve a state-field-path (\"camera_day.clahe_level\") to its signal
   {:signal-name :constraints :type}, or nil. `:signal-name` identifies the
   application value from which the separate protobuf controls
   projection can be populated; the path joins signals.json by
   (subsystem-field, field-name)."
  [state-field-path]
  (let [[sub fld] (str/split state-field-path #"\." 2)]
    (when-let [sig (get @!signal-index [sub fld])]
      {:signal-name (:signal-name sig) :constraints (:constraints sig) :type (:type sig)})))
(m/=> signal-for [:=> [:cat s/ne-string] [:maybe [:map-of :keyword :any]]])

(defn signals-for-message
  "All broadcast signals for a state message `proto-message`
   (\"ser.JonGuiDataMeteo\"), sorted by proto field number. Each entry:
   {:name :signal-name :type :constraints}. Empty when the message broadcasts
   no signals (e.g. ser.ObjectDetection). The join is by :proto-message."
  [proto-message]
  (mapv (fn [s]
          {:name (:field-name s)
           :signal-name (:signal-name s)
           :type (:type s)
           :constraints (:constraints s)})
        (get @!signal-by-message-index proto-message)))
(m/=> signals-for-message [:=> [:cat s/ne-string] [:sequential [:map-of :keyword :any]]])

(defn- flatten-proto-field
  "Normalize a proto-db message field to the resolve/all-fields shape: a STRING
   :type (proto-db carries a keyword, :float) + a flattened :interaction. lower +
   scales tolerate a keyword :semantic-type (scales keywordizes), but :type MUST be
   a string (the type-set checks compare strings). The proto-db `:type-ref` rides
   along when present: a nested/oneof arm scalar's owning message is NOT the outer
   command id, so `enum-options` must resolve its enum type from the field itself
   (the endpoints-view fields have no :type-ref and keep the by-name lookup)."
  [f]
  (let [i (:interaction f)]
    (cond-> {:name (:name f)
             :type (name (:type f))
             :constraints (:constraints f)
             :semantic-type (:semantic-type i)
             :presets (:presets i)
             :unit (:unit i)
             :precision (:precision i)
             :display-format (:display-format i)}
      (:type-ref f) (assoc :type-ref (:type-ref f)))))
(m/=> flatten-proto-field [:=> [:cat [:map-of :keyword :any]] [:map-of :keyword :any]])

(defn message-subforms
  "For `command-id`, one entry per MESSAGE-typed field (a nested sub-form),
   resolved ONE level into the proto-db message graph (the endpoints view is flat —
   it carries no :type-ref, so the recursion reads proto-db). Each entry:
   {:field <message-field-name> :type-ref <nested-message> :scalars [<widget-eligible
   nested scalar fields, flattened to the all-fields shape>]}. Empty when the
   command has no message fields; a nested field that is itself a message is dropped
   by widget-field? (no deeper recursion)."
  [command-id]
  (let [pdb (mf/proto-db)]
    (->> (get-in pdb [:messages command-id :fields])
         (filter #(= :message (:type %)))
         (mapv (fn [mfld]
                 {:field (:name mfld)
                  :type-ref (:type-ref mfld)
                  :scalars (->> (get-in pdb [:messages (:type-ref mfld) :fields])
                                (mapv flatten-proto-field)
                                (filterv widget-field?))})))))
(m/=> message-subforms [:=> [:cat s/ne-string] [:sequential [:map-of :keyword :any]]])

(defn oneof-arms
  "For `command-id`'s message field `field-name`, when the nested message carries a
   `:oneofs` group (e.g. cmd.RotaryPlatform.Axis/azimuth → cmd.RotaryPlatform.Azimuth,
   a required 6-arm oneof), return {:discriminator <oneof-name> :required <bool>
   :arms [{:arm <field-name> :arm-index <0-based> :type-ref <arm-msg> :scalars
   [<widget-eligible leaf scalars, flattened>]}]}, else nil. Maps each oneof field-
   number → its arm via the nested message's :fields, then recurses TWO levels into
   each arm leaf's scalars (extends message-subforms one deeper). Each invocation is
   INDEPENDENT — azimuth and elevation arms differ (elevation drops :direction on 3
   arms, uses :normalized speed), so never template one onto the other. An empty arm
   (e.g. halt) yields :scalars [] (a parameterless arm — a bare commit, no sub-form)."
  [command-id field-name]
  (let [pdb (mf/proto-db)
        cmd-fields (get-in pdb [:messages command-id :fields])
        mfield (first (filter #(= field-name (:name %)) cmd-fields))
        nested (get-in pdb [:messages (:type-ref mfield)])
        oneof (first (:oneofs nested))]
    (when oneof
      (let [by-num (into {} (map (juxt :number identity)) (:fields nested))]
        {:discriminator (:name oneof)
         :required (boolean (:required oneof))
         :arms (vec (map-indexed (fn [idx fnum]
                                   (let [arm-field (by-num fnum)
                                         arm-msg (:type-ref arm-field)]
                                     {:arm (:name arm-field)
                                      :arm-index idx
                                      :type-ref arm-msg
                                      :scalars (->> (get-in pdb [:messages arm-msg :fields])
                                                    (mapv flatten-proto-field)
                                                    (filterv widget-field?))}))
                                 (:fields oneof)))}))))
(m/=> oneof-arms [:=> [:cat s/ne-string s/ne-string] [:maybe [:map-of :keyword :any]]])