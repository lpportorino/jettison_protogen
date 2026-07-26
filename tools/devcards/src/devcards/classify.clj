(ns devcards.classify
  "Consumer-supplied CLASSIFICATION — the facts a widget type must declare
   before a geometry or readability rule can judge it, and the lookup that
   REFUSES to guess when a type was never declared.

   Two facts, and they are separate axes on purpose:
   - :interactive? — can this type take the pointer? Drives the overlap
     rule, where two things competing for the same pixel is the hazard.
   - :role — what is this element FOR? Drives the per-role readability and
     occlusion thresholds. `roles` below is the closed set.

   A structural container that scrolls is genuinely {:role :structural
   :interactive? true}, which is why one field cannot stand in for the
   other; the one combination that is a contradiction rather than a
   nuance (:role :interactive with :interactive? false) is rejected.

   Keyed on the dump's `type` — the LVGL class name, which dump_obj emits
   unconditionally for every node, so the key is always present to look
   up. It is a CLASS, not an instance: a per-instance fact such as `this
   button is disabled` is read off the node, never off this table.

   What a per-instance fact CANNOT be read off, today: whether this
   particular object still carries LV_OBJ_FLAG_CLICKABLE. The renderer
   clears it at runtime on a STATIC host_proxy box so the pointer falls
   through (renderer.c:1966), but dump_obj emits only hidden/checked/
   disabled, so no rule can see it. A type-keyed :interactive? therefore
   counts such an instance IN when the pointer path has already let it
   out — an OVER-report direction, named here rather than left for a
   consumer to discover from a finding it cannot explain.

   THE POINT OF THE NS: an undeclared type is a FINDING, never a skip. A
   rule that silently passes over what it could not classify reports
   'clean' and 'I could not look' with the same empty vector — the
   failure mode where broken output is byte-identical to nothing-to-report
   output. `unclassified-findings` is how a rule pays that debt out loud,
   and it is the reason `classify` returns nil rather than a default."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(def roles
  "The closed role set. :text and :interactive are the roles a human
   READS or AIMS AT — any occlusion of them is damage. :structural is
   chrome (panels, wrappers, spacers) whose partial covering is ordinary
   composition. Extending this set is a deliberate change that owes the
   new role's threshold arm in every table that keys on roles."
  #{:text :interactive :structural})

(defn- table-error
  [entry problem]
  (throw (ex-info (str "malformed classification: " problem) {:entry entry})))

(defn- validate-entry!
  [k entry]
  (when-not (map? entry) (table-error {k entry} "not a map"))
  (when-let [extra (seq (remove #{:interactive? :role} (keys entry)))]
    (table-error {k entry} (str "unknown keys " (vec extra))))
  (when-not (contains? entry :interactive?)
    (table-error {k entry} "missing :interactive? — the overlap axis"))
  (when-not (boolean? (:interactive? entry))
    (table-error {k entry} ":interactive? must be true or false"))
  (when-not (contains? roles (:role entry))
    (table-error {k entry} (str ":role must be one of " (sort roles))))
  (when (and (= :interactive (:role entry)) (not (:interactive? entry)))
    (table-error {k entry}
                 (str ":role :interactive with :interactive? false is a "
                      "contradiction — a role of interactive IS the claim "
                      "that it takes the pointer")))
  entry)

(defn validate-table!
  "Shape check for a classification table

     {:types   {\"lv_button\" {:interactive? true :role :interactive} ...}
      :default {:interactive? false :role :structural}}   ; optional

   Throws on the first malformed entry; returns the table. `:default` is
   the consumer's explicit, visible decision to stop enforcing totality —
   it must itself be a full classification, so choosing it is never a
   typo, and its presence is greppable in the consumer's config."
  [table]
  (when-not (map? table) (table-error table "table is not a map"))
  (when-let [extra (seq (remove #{:types :default} (keys table)))]
    (table-error table (str "unknown table keys " (vec extra))))
  (let [types (:types table)]
    (when-not (map? types) (table-error table ":types must be a map"))
    (doseq [[k entry] types]
      (when-not (string? k) (table-error {k entry} "type key must be a string"))
      (validate-entry! k entry)))
  ;; `contains?`, NOT `when-let`. A falsey default slipped past a when-let
  ;; binding unvalidated, and `:default false` then switched totality
  ;; enforcement off wholesale: `classify` returned false, `(some? false)` is
  ;; true, so EVERY undeclared type read as declared and the whole
  ;; :unclassified-type class went silent — output byte-identical to a clean
  ;; run. One word, no diagnostic, in the one namespace whose stated purpose
  ;; is that an unjudged element is never silence.
  (when (contains? table :default) (validate-entry! :default (:default table)))
  table)

(defn classify
  "The classification for `node-type`, or nil when the table declares
   neither it nor a :default. Returns nil rather than a fallback so every
   caller must decide, visibly, what an unknown type means to it."
  [table node-type]
  (or (get-in table [:types node-type]) (:default table)))

(defn unclassified-findings
  "One finding per DISTINCT undeclared type reachable in `nodes` (a seq of
   annotated entries carrying :node). Per-type rather than per-node: a
   corpus with 40 unclassified labels is ONE missing table row, and 40
   copies of it would bury the other findings.

   `rule` names the rule that could not proceed, so the message says what
   went unjudged rather than merely what went unnamed."
  [card-id table nodes rule]
  (let [missing (into (sorted-set)
                      (comp (map (comp :type :node))
                            (remove nil?)
                            (remove #(some? (classify table %))))
                      nodes)]
    (vec (for [t missing]
           {:card card-id
            :invariant :unclassified-type
            :node t
            :detail (str "widget type " (pr-str t) " has no classification — "
                         (name rule) " could not judge its nodes. Declare it "
                         "in the table's :types (or set an explicit "
                         ":default) — an unjudged node must never pass "
                         "silently")}))))

(defn describe-table
  "One-line table summary for an error message."
  ^String [table]
  (str (count (:types table)) " types"
       (when (contains? table :default) " + a :default")
       (when-let [ks (seq (sort (keys (:types table))))]
         (str " (" (str/join ", " (take 6 ks))
              (when (> (count ks) 6) ", …") ")"))))
