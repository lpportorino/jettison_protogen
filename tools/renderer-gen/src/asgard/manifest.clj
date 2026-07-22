(ns asgard.manifest
  "Load and parse machine-readable JSON manifests from jettison_protogen submodule.
   Also loads proto-db.edn for rich descriptions and enum resolution."
  (:require [asgard.schema :as s]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as j]
            [malli.core :as m])
  (:import [java.io File PushbackReader]))

(set! *warn-on-reflection* true)

(def ^:private manifests-dir "../protogen/output/manifests")

(def ^:private proto-db-path "../protogen/docs/.protodoc/proto-db.edn")

(defn- load-manifest
  "Load a JSON manifest file. Tries classpath first (uberjar), falls back to filesystem (dev)."
  [filename]
  (if-let [url (io/resource (str "manifests/" filename))]
    (j/read-value url j/keyword-keys-object-mapper)
    (let [f (io/file manifests-dir filename)]
      (when (File/.exists f) (j/read-value f j/keyword-keys-object-mapper)))))
(m/=> load-manifest [:=> [:cat [:string {:min 1}]] [:maybe [:map-of :keyword :any]]])

(defn endpoints "Load endpoints manifest." [] (load-manifest "endpoints.json"))
(m/=> endpoints [:=> [:cat] [:maybe [:map-of :keyword :any]]])

(defn signals "Load signals manifest." [] (load-manifest "signals.json"))
(m/=> signals [:=> [:cat] [:maybe [:map-of :keyword :any]]])

(defn sub-signals "Load sub-signals manifest." [] (load-manifest "sub-signals.json"))
(m/=> sub-signals [:=> [:cat] [:maybe [:map-of :keyword :any]]])

;; ── Proto-DB (EDN) ───────────────────────────────────────────────────
(def ^:private !proto-db
  "Delay-cached proto-db.edn. Loaded once on first access.
   Tries classpath first (uberjar), falls back to filesystem (dev)."
  (delay (or (when-let [url (io/resource "manifests/proto-db.edn")]
               (with-open [rdr (PushbackReader. (io/reader url))] (edn/read rdr)))
             (let [f (io/file proto-db-path)]
               (when (File/.exists f)
                 (with-open [rdr (PushbackReader. (io/reader f))] (edn/read rdr)))))))

(defn proto-db
  "Load proto-db.edn from protogen submodule. Cached after first load."
  []
  @!proto-db)
(m/=> proto-db [:=> [:cat] [:maybe [:map-of :keyword :any]]])

(defn subsystem-tags
  "Map of {kebab-keyword → proto field tag number} for the JonGUIState top-level
   subsystem message fields — the wire-scan interest set, the same
   set protogen's binary-dedup analyzer emits to binary_dedup_tags.ts. Derived
   from proto-db: the root message's :message-typed fields with field number >= 13
   (below 13 is frame metadata like opaque_payloads, not a subsystem). This is
   the Clojure source of truth; the generated TS is a peer projection."
  []
  (when-let [db (proto-db)]
    (into {}
          (for [f (get-in db [:messages "ser.JonGUIState" :fields])
                :when (and (= :message (:type f)) (>= (long (:number f)) 13))]
            [(keyword (str/replace (:name f) "_" "-")) (long (:number f))]))))
(m/=> subsystem-tags [:=> [:cat] [:maybe [:map-of :keyword :int]]])

;; ── Enum Resolution ──────────────────────────────────────────────────
(defn strip-common-prefix
  "Strip the longest common prefix shared by all value names.
   e.g., [\"FOO_BAR_A\" \"FOO_BAR_B\"] → [\"A\" \"B\"]"
  [names]
  (when (seq names)
    (let [prefix-len (loop [i 0]
                       (if (and (< i (count (first names)))
                                (let [c (nth ^String (first names) i)]
                                  (every? #(and (< i (count ^String %))
                                                (= c (nth ^String % i)))
                                          (rest names))))
                         (recur (inc i))
                         i))
          ;; Walk back to last underscore for clean word boundary
          prefix-end (let [s (subs (first names) 0 prefix-len)
                           last-us (str/last-index-of s "_")]
                       (if last-us (inc (long last-us)) 0))]
      (mapv #(subs ^String % prefix-end) names))))
(m/=> strip-common-prefix
      [:=> [:cat [:maybe [:sequential s/ne-string]]] [:maybe [:sequential :string]]])

(defn enum-values
  "Resolve enum type-ref to its valid value names (short form).
   Excludes UNSPECIFIED sentinel values. Includes DEFAULT (valid value).
   e.g., \"ser.JonGuiDataVideoChannel\" → [\"HEAT\" \"DAY\"]
   e.g., \"ser.JonGuiDataFxModeDay\" → [\"DEFAULT\" \"A\" \"B\" \"C\" \"D\" \"E\" \"F\"]"
  [enum-type-ref]
  (when-let [db (proto-db)]
    (when-let [enum-def (get-in db [:enums enum-type-ref])]
      (let [all-values (:values enum-def)
            valid (filterv #(not (str/ends-with? ^String (:name %) "_UNSPECIFIED"))
                           all-values)
            names (mapv :name valid)]
        (when (seq names) (strip-common-prefix names))))))
(m/=> enum-values [:=> [:cat [:string {:min 1}]] [:maybe [:sequential :string]]])

;; ── Message & Field Descriptions ─────────────────────────────────────
(defn message-description
  "Get full prose description for a proto message ID.
   e.g., \"cmd.Compass.SetMagneticDeclination\" → \"Sets the magnetic declination...\""
  [message-id]
  (when-let [db (proto-db)] (get-in db [:messages message-id :description])))
(m/=> message-description [:=> [:cat [:string {:min 1}]] [:maybe :string]])

(defn- find-field-in-message
  "Find a field by name in a proto-db message definition."
  [db message-id field-name]
  (when-let [msg (get-in db [:messages message-id])]
    (first (filter #(= field-name (:name %)) (:fields msg)))))
(m/=> find-field-in-message
      [:=> [:cat [:maybe [:map-of :keyword :any]] [:string {:min 1}] [:string {:min 1}]]
       [:maybe [:map-of :keyword :any]]])

(defn field-description
  "Get per-field description from proto-db message fields.
   e.g., \"cmd.CV.SetAutoFocus\" field \"channel\" → \"Video channel selector\""
  [message-id field-name]
  (when-let [db (proto-db)]
    (:description (find-field-in-message db message-id field-name))))
(m/=> field-description [:=> [:cat [:string {:min 1}] [:string {:min 1}]] [:maybe :string]])

(defn field-enum-type-ref
  "Get the enum type-ref for a field in a message.
   e.g., \"cmd.CV.SetAutoFocus\" field \"channel\" → \"ser.JonGuiDataVideoChannel\""
  [message-id field-name]
  (when-let [db (proto-db)]
    (let [field (find-field-in-message db message-id field-name)]
      (when (= :enum (:type field)) (:type-ref field)))))
(m/=> field-enum-type-ref
      [:=> [:cat [:string {:min 1}] [:string {:min 1}]] [:maybe :string]])