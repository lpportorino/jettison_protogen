(ns gate.extension
  "Immutable versioned observation registration and sidecars bound to validated core archives."
  (:require [gate.admission :as admission]
            [gate.archive :as archive]
            [gate.archive-contract :as ac]
            [gate.canonical :as canonical]
            [gate.contract :as c]
            [gate.extension-contract :as ec]
            [gate.extension-schema :as schema]
            [gate.portable :as p]
            [malli.core :as m]))

(def ^:private seal #?(:clj (Object.) :cljs (js-obj)))
(deftype PreparedExtensions [entries keywords stamp]
  #?(:clj clojure.lang.ILookup :cljs ILookup)
  (#?(:clj valAt :cljs -lookup) [_ field] (case field :entries entries :keywords keywords nil))
  (#?(:clj valAt :cljs -lookup) [_ field absent] (case field :entries entries :keywords keywords absent)))
(deftype PreparedPair [core sidecar stamp]
  #?(:clj clojure.lang.ILookup :cljs ILookup)
  (#?(:clj valAt :cljs -lookup) [_ field] (case field :core core :sidecar sidecar nil))
  (#?(:clj valAt :cljs -lookup) [_ field absent] (case field :core core :sidecar sidecar absent)))
(def Prepared [:fn #(and (instance? PreparedExtensions %) (identical? seal (.-stamp ^PreparedExtensions %)))])
(def Pair [:fn #(and (instance? PreparedPair %) (identical? seal (.-stamp ^PreparedPair %)))])
(def ContextInput [:or Prepared p/Input])
(def PairInput [:or Pair p/Input])
(def NativeEntries [:map-of {:max (:registrations ec/limits)} [:tuple ec/ExtensionId ec/Version] [:fn map?]])
(def Seal [:fn #(identical? seal %)])
#?(:clj (do (alter-meta! #'->PreparedExtensions assoc :private true :doc "Internal sealed registry constructor.")
            (alter-meta! #'->PreparedPair assoc :private true :doc "Internal verified-pair constructor.")))
(m/=> ->PreparedExtensions [:=> [:cat NativeEntries admission/Vocabulary Seal] Prepared])
(m/=> ->PreparedPair [:=> [:cat ac/Document ec/Document Seal] Pair])

(defn require-context!
  "Reject a map or record that merely claims to be a prepared context, independently of optional
   function instrumentation. The seal guards against unvalidated data, not against hostile in-process
   code, which could reach the constructor; callbacks and callers are trusted code here."
  [value]
  (when-not (m/validate Prepared value) (ec/refuse! :extension-registration))
  value)
(m/=> require-context! [:=> [:cat ContextInput] Prepared])

(defn require-pair!
  "Require the sealed result of complete pair validation, never a map claiming validation happened.
   The same trust boundary as require-context!: data is checked, in-process code is trusted."
  [value]
  (when-not (m/validate Pair value) (ec/refuse! :extension-shape))
  value)
(m/=> require-pair! [:=> [:cat PairInput] Pair])

(defn- fingerprint
  "Bind canonical contract bytes with an explicit domain, independently of runtime callback objects."
  [contract]
  (canonical/sha256 (str "gate.extension.contract.v1\n" (canonical/encode-portable contract 1048576))))
(m/=> fingerprint [:=> [:cat ec/Contract] c/Digest])

(defn prepare
  "Compile a complete immutable trusted registration set; no ambient registry or Var dispatch survives."
  [registrations]
  (when-not (and (vector? registrations) (<= (count registrations) (:registrations ec/limits))) (ec/refuse! :extension-registration))
  (let [counter (volatile! 0)
        entries (reduce
                 (fn [out registration]
                   (when-not (and (map? registration) (contains? registration :contract)
                                  (every? #{:contract :projector} (keys registration)))
                     (ec/refuse! :extension-registration))
                   (let [contract (schema/contract! (:contract registration) counter)
                         registration-key [(:extension/id contract) (:extension/version contract)]]
                     (when-not (m/validate ec/Registration registration) (ec/refuse! :extension-registration))
                     (when (contains? out registration-key) (ec/refuse! :extension-duplicate))
                     (assoc out registration-key {:contract contract :fingerprint (fingerprint contract)
                                                  :validate (m/validator (schema/compile-schema contract))
                                                  :projector (:projector registration)}))) {} registrations)
        _ (when (> (reduce + 0 (map #(count (get-in % [:contract :payload/definitions])) (vals entries))) (:definitions ec/limits))
            (ec/refuse! :extension-limit))
        keywords (into {} (map (juxt str identity))
                       (filter keyword? (tree-seq coll? seq [ec/Document ec/Projection
                                                             (mapv :contract (vals entries))])))]
    (when (> (count keywords) (:keywords ec/limits)) (ec/refuse! :extension-limit))
    (PreparedExtensions. entries keywords seal)))
(m/=> prepare [:=> [:cat p/Input] Prepared])

(defn- entry!
  "Resolve only an exact admitted ID/version pair, distinguishing unknown IDs from unsupported versions."
  [context id version]
  (or (get (:entries context) [id version])
      (ec/refuse! (if (some #(= id (first %)) (keys (:entries context))) :extension-version :extension-unknown))))
(m/=> entry! [:=> [:cat Prepared ec/ExtensionId ec/Version] [:fn map?]])

(defn- source!
  "Require the named source to belong to this core graph; declarations are not proof of callback compilation."
  [core id]
  (or (first (filter #(= id (:id %)) (get-in core [:graph :sources]))) (ec/refuse! :extension-source)))
(m/=> source! [:=> [:cat ac/Document c/Id] c/Source])

(defn- observation!
  "Validate complete payload and presentation under the exact contract and bound core identities."
  [context core observation]
  (p/require-bounded! observation)
  (when-not (m/validate ec/Observation observation) (ec/refuse! :extension-shape))
  (let [entry (entry! context (:extension/id observation) (:extension/version observation))]
    (when-not (= (:fingerprint entry) (:schema/fingerprint observation)) (ec/refuse! :extension-fingerprint))
    (canonical/encode-portable (:payload observation) (:payload-bytes ec/limits))
    (when-not ((:validate entry) (:payload observation)) (ec/refuse! :extension-payload))
    (source! core (:source observation))
    (let [{:keys [kind id]} (:subject observation)]
      (when-not (case kind
                  :run (= id (get-in core [:graph :run :id]))
                  :node (some #(= id (:id %)) (get-in core [:graph :nodes]))) (ec/refuse! :extension-subject)))
    (when-let [projection (:projection observation)]
      (canonical/encode-portable projection (:projection-bytes ec/limits))
      (let [declared (get-in projection [:provenance :implementation/source])]
        (when-not (= declared (source! core (:id declared))) (ec/refuse! :extension-source))))
    observation))
(m/=> observation! [:=> [:cat Prepared ac/Document p/Input] ec/Observation])

(defn observe
  "Admit one payload and optionally compute a validated presentation; callback errors never become missing evidence."
  [context core request]
  (require-context! context)
  (archive/require-valid! core)
  (p/require-bounded! request)
  (when-not (m/validate ec/ObservationRequest request) (ec/refuse! :extension-shape))
  (let [entry (entry! context (:extension/id request) (:extension/version request))
        observation (observation! context core (assoc request :schema/fingerprint (:fingerprint entry)))]
    (if-let [bound (:projector entry)]
      (let [declared (:implementation/source bound)
            _ (when-not (= declared (source! core (:id declared))) (ec/refuse! :extension-source))
            projection (try ((:projector/fn bound) (:payload observation))
                            (catch #?(:clj Exception :cljs :default) _ (ec/refuse! :extension-projector)))]
        (when-not (m/validate ec/Projection projection) (ec/refuse! :extension-projection))
        (p/require-bounded! projection)
        (observation! context core (assoc observation :projection
                                          (assoc projection :provenance (dissoc bound :projector/fn)))))
      observation)))
(m/=> observe [:=> [:cat ContextInput ac/Document p/Input] ec/Observation])

(defn- material
  "Normalize unique observation identities and derive the exact used contract manifest."
  [context core observations]
  (when-not (and (vector? observations) (<= (count observations) (:observations ec/limits))) (ec/refuse! :extension-limit))
  (when-not (= (count observations) (count (set (map :observation/id observations))))
    (ec/refuse! :extension-observation))
  (doseq [observation observations] (observation! context core observation))
  {:schema/version 1 :core/artifact (:artifact core)
   :contracts (vec (sort-by (juxt (comp str :extension/id) :extension/version)
                            (distinct (map #(select-keys % [:extension/id :extension/version :schema/fingerprint]) observations))))
   :observations (vec (sort-by :observation/id observations))})
(m/=> material [:=> [:cat Prepared ac/Document p/Input] ec/Material])

(defn create
  "Seal a normalized extension sidecar; all observation bytes bind to the existing core archive digest."
  [context core observations]
  (require-context! context)
  (archive/require-valid! core)
  (let [value (material context core observations)
        digest (canonical/sha256 (str "gate.extension.sidecar.v1\n" (canonical/encode-portable value (:sidecar-bytes ec/limits))))
        document (assoc value :artifact digest)]
    (canonical/encode-portable document (:sidecar-bytes ec/limits))
    document))
(m/=> create [:=> [:cat ContextInput ac/Document p/Input] ec/Document])

(defn pair
  "Return a sealed accepted pair only after core, every extension, manifest, identity and binding checks pass."
  [context core sidecar]
  (require-context! context)
  (archive/require-valid! core)
  (p/require-bounded! sidecar)
  (when-not (m/validate ec/Document sidecar) (ec/refuse! :extension-shape))
  (when-not (= (:artifact core) (:core/artifact sidecar)) (ec/refuse! :extension-binding))
  (let [expected (create context core (:observations sidecar))]
    (when-not (= (:contracts expected) (:contracts sidecar)) (ec/refuse! :extension-manifest))
    (when-not (= expected sidecar) (ec/refuse! :extension-identity))
    (PreparedPair. core sidecar seal)))
(m/=> pair [:=> [:cat ContextInput ac/Document p/Input] Pair])

(defn encode
  "Encode only a verified pair's sidecar; the unchanged core archive has its separate existing encoder."
  [verified]
  (require-pair! verified)
  (canonical/encode-portable (:sidecar verified) (:sidecar-bytes ec/limits)))
(m/=> encode [:=> [:cat PairInput] canonical/Text])

(defn decode
  "Admit bounded sidecar EDN using this prepared vocabulary, then verify the complete pair."
  [context core text limits]
  (require-context! context)
  (when-not (and (m/validate c/AdmissionLimits limits)
                 (every? (fn [[k ceiling]] (<= (get limits k) ceiling))
                         (assoc admission/default-limits :bytes (inc (:sidecar-bytes ec/limits))))) (ec/refuse! :extension-limit))
  (pair context core (admission/decode-portable text (:keywords context) limits)))
(m/=> decode [:=> [:cat ContextInput ac/Document admission/SourceText c/AdmissionLimits] Pair])
