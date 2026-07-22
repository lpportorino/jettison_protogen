(ns lvgl-codegen.construct.registry
  "The assign-once proto-numbering registry
   (`docs/lvgl-factory/03-FACTORY-DESIGN.md`, enrichment pass 7).

   Proto enum NUMBERS are the wire contract: the `.pb` is deployed to the target
   and can lag the wasm, so a number, once assigned to a member name, never
   changes — never re-derived from LVGL ordering. The registry
   (`edn/proto-numbering-registry.edn`) is the one home for that fact
   (`cohesion.md`): `{proto-type {proto-name proto-number}}`, append-only.

   Reconcile semantics per member, by the enum's `:cast-class`:

   - **pinned** (name already in the registry) — the pin wins. For `:direct`
     enums the pin must still equal the probe's `:resolved-int` (the parity
     contract); a mismatch means LVGL renumbered a deployed value → THROW, a
     human decides (switch the enum to `:lut`, or a deliberate wire break).
     For `:lut` enums an LVGL renumber is absorbed by regenerating the LUT.
   - **new** — `:direct` pins at `:resolved-int`; `:lut` pins at the next free
     number. A new number colliding with a LIVE member's pin would need proto
     `allow_alias` → THROW. Colliding with a RETIRED name (in the registry but
     absent from the current IR) is a rename takeover — LVGL renamed the member,
     value unchanged — and is allowed; the retired name stays (append-only).

   `apply-numbering` then stamps the registry numbers over the IR — it is the
   authoritative numbering pass; the lift's `:proto-number` is provisional."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [lvgl-codegen.construct.schema :as schema]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

(def registry-path
  "Canonical home of the committed numbering registry."
  "edn/proto-numbering-registry.edn")

(def registry-schema
  "`{proto-type {proto-name proto-number}}` — e.g.
   `{\"Align\" {\"ALIGN_CENTER\" 9}}`."
  [:map-of [:string {:min 1}] [:map-of [:string {:min 1}] [:int {:min 0}]]])

(defn load-registry
  "Read + validate the registry EDN at `path`. A missing or malformed file
   throws — the registry is a hard prerequisite of numbering, never defaulted
   (`fail-fast.md`)."
  [path]
  (when-not (java.io.File/.isFile (io/file path))
    (throw (ex-info "Proto-numbering registry not found" {:path path})))
  (let [registry (edn/read-string (slurp path))]
    (if (m/validate registry-schema registry)
      registry
      (throw (ex-info "Malformed proto-numbering registry"
                      {:path path :explain (m/explain registry-schema registry)})))))
(m/=> load-registry [:=> [:cat [:string {:min 1}]] registry-schema])

(defn save-registry!
  "Write `registry` to `path` deterministically (nested sorted maps), so the
   committed file diffs cleanly across reconciles."
  [path registry]
  (spit path
        (with-out-str (pp/pprint (into (sorted-map)
                                       (map (fn [[proto-type entry]] [proto-type
                                                                      (into (sorted-map)
                                                                            entry)]))
                                       registry))))
  nil)
(m/=> save-registry! [:=> [:cat [:string {:min 1}] registry-schema] :nil])

(defn- next-free-number
  "Smallest number above every pin in `entry` (0 for an empty entry). Never
   reuses a retired name's number — retirement reserves it."
  [entry]
  (if (empty? entry) 0 (inc (reduce max (vals entry)))))
(m/=> next-free-number
      [:=> [:cat [:map-of [:string {:min 1}] [:int {:min 0}]]] [:int {:min 0}]])

(defn- reconcile-member
  "Fold one member into an enum's registry entry (see ns docstring for the
   pinned / new / takeover / collision semantics)."
  [proto-type cast-class live-names entry {:keys [proto-name resolved-int]}]
  (when (and (= :direct cast-class) (nil? resolved-int))
    (throw (ex-info "Synthetic (no-LVGL-counterpart) member in a direct-cast enum"
                    {:proto-type proto-type :member proto-name})))
  (if-let [pinned (get entry proto-name)]
    (do (when (and (= :direct cast-class) (not= pinned resolved-int))
          (throw
           (ex-info
            "Proto-number drift: LVGL renumbered a pinned direct-cast member"
            {:proto-type proto-type :member proto-name :pinned pinned :lvgl resolved-int})))
        entry)
    (let [n (case cast-class
              :direct resolved-int
              :lut (next-free-number entry))
          holder (some (fn [[nm pin]] (when (and (= pin n) (contains? live-names nm)) nm))
                       entry)]
      (when holder
        (throw (ex-info
                "Proto-number collision: two live members would share a number"
                {:proto-type proto-type :member proto-name :number n :held-by holder})))
      (assoc entry proto-name n))))
(m/=> reconcile-member
      [:=>
       [:cat [:string {:min 1}] [:enum :direct :lut] [:set [:string {:min 1}]]
        [:map-of [:string {:min 1}] [:int {:min 0}]] schema/enum-member]
       [:map-of [:string {:min 1}] [:int {:min 0}]]])

(defn reconcile
  "Fold every member of `enums` into `registry`, returning the grown registry.
   Append-only: existing pins are never changed or removed; idempotent — a
   second reconcile of the same IR is a no-op."
  [registry enums]
  (reduce (fn [reg {:keys [proto-type cast-class members]}]
            (let [live-names (into #{} (map :proto-name) members)]
              (assoc reg
                     proto-type
                     (reduce #(reconcile-member proto-type cast-class live-names %1 %2)
                             (get reg proto-type {})
                             members))))
          registry
          enums))
(m/=> reconcile
      [:=> [:cat registry-schema [:vector schema/enum-construct]] registry-schema])

(defn apply-numbering
  "Stamp the registry's numbers over `enums` (`:proto-number` per member) — the
   authoritative numbering pass (ir→ir, idempotent). Every enum + member must
   already be pinned (run `reconcile` first); a miss throws."
  [registry enums]
  (mapv (fn [{:keys [proto-type members] :as construct}]
          (let [entry (or (get registry proto-type)
                          (throw (ex-info "Enum not in the numbering registry"
                                          {:proto-type proto-type})))]
            (assoc construct
                   :members
                   (mapv (fn [{:keys [proto-name] :as member}]
                           (if-let [n (get entry proto-name)]
                             (assoc member :proto-number n)
                             (throw (ex-info "Member not in the numbering registry"
                                             {:proto-type proto-type :member proto-name}))))
                         members))))
        enums))
(m/=> apply-numbering
      [:=> [:cat registry-schema [:vector schema/enum-construct]]
       [:vector schema/enum-construct]])