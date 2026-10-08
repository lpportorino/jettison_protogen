(ns gate.api-source
  "Passive, platform-specific declaration observations over bounded source text, without evaluation."
  (:require [clojure.string :as str]
            [edamame.core :as parser]
            [gate.api-source-contract :as sc]
            [gate.canonical :as canonical]
            [malli.core :as m]))

(def default-limits {:bytes 1048576 :forms 4096 :nodes 262144})
(def ^:private core-heads '#{ns def defn defn- defmacro declare set!})
(def Form
  "Parser-owned syntax values; nested collection contents are charged by the reader's node budget."
  [:or :nil :boolean [:fn number?] [:fn char?] :symbol :keyword sc/Source
   [:fn coll?] [:fn #(instance? java.util.Date %)] [:fn uuid?]])
(def Context
  [:map {:closed true} [:namespace sc/Name]
   [:aliases [:map-of {:max 262144} :symbol :symbol]]
   [:refers [:map-of {:max 262144} :symbol :symbol]]
   [:excluded [:set {:max 262144} :symbol]]
   [:definitions {:optional true} [:map-of {:max 4096} sc/Name sc/Declaration]]
   [:declared {:optional true} [:set {:max 262144} sc/Name]]
   [:registrations {:optional true} [:map-of {:max 4096} sc/Name sc/Registration]]])
(def NodeCounter [:fn #(instance? clojure.lang.Volatile %)])
(def ^:private reader-class (class (parser/source-reader "")))
(def NativeReader [:fn #(.isInstance ^Class reader-class %)])
(def NativeOptions
  "Opaque Edamame configuration; parser callbacks are local trusted functions, never persisted data."
  [:fn #(and (map? %) (= :allow (:read-cond %)) (= false (:read-eval %))
             (contains? #{#{:clj} #{:cljs}} (:features %)) (fn? (:postprocess %)))])
(def End [:fn #(= Object (class %))])

(defn- position
  "Retain bounded source coordinates only; discarded parser diagnostics may contain source data."
  [form]
  {:row (max 1 (min 1048577 (or (:row (meta form)) 1)))
   :column (max 1 (min 1048577 (or (:col (meta form)) 1)))})
(m/=> position [:=> [:cat Form] sc/Position])

(defn- refuse!
  "Throw a closed source refusal at the form location without echoing rejected text."
  [code form]
  (throw (ex-info "API source observation refused" (assoc (position form) :code code))))
(m/=> refuse! [:=> [:cat sc/Code Form] :nil])

(defn- local-name!
  "Require an unqualified source symbol; preserve valid punctuation such as apostrophes."
  [form subject]
  (when-not (and (symbol? subject) (nil? (namespace subject))
                 (<= 1 (count (str subject)) 256))
    (refuse! :api-source-form form))
  (str subject))
(m/=> local-name! [:=> [:cat Form Form] sc/Name])

(defn- require-option
  "Admit explicit aliases and finite referred symbols for one library, without loading it."
  [context lib form [option value]]
  (case option
    (:as :as-alias)
    (do (when-not (and (symbol? value) (not (contains? (:aliases context) value)))
          (refuse! :api-source-namespace form))
        (assoc-in context [:aliases value] lib))
    (:refer :refer-macros)
    (do (when-not (and (vector? value) (every? symbol? value))
          (refuse! :api-source-namespace form))
        (reduce (fn [out referred]
                  (when (contains? (:refers out) referred) (refuse! :api-source-namespace form))
                  (assoc-in out [:refers referred] (symbol (str lib) (str referred)))) context value))
    (refuse! :api-source-namespace form)))
(m/=> require-option [:=> [:cat Context :symbol Form Form] Context])

(defn- require-lib
  "Require a literal library vector; prefix lists and dynamic loading require a separate adapter."
  [context form lib]
  (when-not (and (vector? lib) (symbol? (first lib)) (even? (count (rest lib))))
    (refuse! :api-source-namespace form))
  (reduce #(require-option %1 (first lib) form %2) context (partition 2 (rest lib))))
(m/=> require-lib [:=> [:cat Context Form Form] Context])

(defn- namespace-clause
  "Account for supported namespace clauses, rejecting implicit or ambiguous name resolution."
  [context form clause]
  (when-not (seq? clause) (refuse! :api-source-namespace form))
  (case (first clause)
    (:require :require-macros) (reduce #(require-lib %1 form %2) context (rest clause))
    :refer-clojure
    (if (and (= 3 (count clause)) (= :exclude (second clause))
             (vector? (nth clause 2)) (every? symbol? (nth clause 2)))
      (update context :excluded into (nth clause 2))
      (refuse! :api-source-namespace form))
    :import context
    (refuse! :api-source-namespace form)))
(m/=> namespace-clause [:=> [:cat Context Form Form] Context])

(defn- namespace-context
  "Read explicit require aliases/refers and core exclusions; refuse ambiguous namespace configurations."
  [form]
  (let [subject (second form)
        tail (drop 2 form)
        tail (if (string? (first tail)) (next tail) tail)
        tail (if (map? (first tail)) (next tail) tail)]
    (when-not (and (symbol? subject) (nil? (namespace subject))
                   (<= 1 (count (str subject)) 255))
      (refuse! :api-source-namespace form))
    (reduce #(namespace-clause %1 form %2)
            {:namespace (str subject) :aliases {} :refers {} :excluded #{}} tail)))
(m/=> namespace-context [:=> [:cat Form] Context])

(defn- resolve-head
  "Resolve explicit namespace aliases and core names without consulting ambient loaded namespaces."
  [{:keys [aliases refers excluded definitions]} head]
  (when (symbol? head)
    (if-let [qualifier (namespace head)]
      (symbol (str (get aliases (symbol qualifier) (symbol qualifier))) (name head))
      (or (get refers head)
          (when (and (contains? core-heads head) (not (contains? excluded head))
                     (not (contains? definitions (str head))))
            (symbol "clojure.core" (str head)))))))
(m/=> resolve-head [:=> [:cat Context Form] [:maybe :symbol]])

(defn- declaration
  "Observe binding/function/macro identity and declared privacy; a binding is not classified by IFn."
  [context form raw head]
  (let [subject (second form) local (local-name! form subject)
        body (drop 2 form)
        body (if (string? (first body)) (next body) body)
        attr (when (map? (first body)) (first body))
        body (if attr (next body) body)
        trailing (when (and (not= "def" head) (seq? (first body)) (map? (last body))) (last body))
        attributes (merge (meta subject) (when (= "defn-" head) {:private true})
                          (when (not= "def" head) attr) trailing)
        kind (case head "def" :binding "defmacro" :macro :function)]
    (when (and (= "def" head) (< (count form) 3)) (refuse! :api-source-unbound form))
    (when (and (not= "def" head) (not (or (vector? (first body)) (seq? (first body)))))
      (refuse! :api-source-form form))
    {:id (str (:namespace context) "/" local) :kind kind
     :private? (boolean (:private attributes))
     :position (position form) :source (canonical/sha256 raw)}))
(m/=> declaration [:=> [:cat Context Form sc/Source [:enum "def" "defn" "defn-" "defmacro"]] sc/Declaration])

(defn- absorb
  "Account for every supported top-level declaration; unknown calls refuse rather than disappear."
  [context form raw]
  (when-not (seq? form) (refuse! :api-source-form form))
  (let [head (resolve-head context (first form))
        core? (and head (contains? #{"clojure.core" "cljs.core"} (namespace head)))
        head-name (when head (name head))]
    (cond
      (and core? (contains? #{"def" "defn" "defn-" "defmacro"} head-name))
      (let [entry (declaration context form raw head-name) local (str (second form))]
        (when (contains? (:definitions context) local) (refuse! :api-source-duplicate form))
        (-> context (assoc-in [:definitions local] entry) (update :declared disj local)))

      (and core? (= "declare" head-name))
      (do (when (< (count form) 2) (refuse! :api-source-form form))
          (reduce (fn [ctx subject]
                    (let [local (local-name! form subject)]
                      (when (contains? (:definitions ctx) local) (refuse! :api-source-duplicate form))
                      (update ctx :declared conj local))) context (rest form)))

      (= 'malli.core/=> head)
      (let [subject (second form)
            _ (when-not (and (= 3 (count form)) (symbol? subject)) (refuse! :api-source-spec form))
            id (if (namespace subject) (str subject) (str (:namespace context) "/" subject))]
        (when-not (str/starts-with? id (str (:namespace context) "/")) (refuse! :api-source-spec form))
        (when (contains? (:registrations context) id) (refuse! :api-source-duplicate form))
        (assoc-in context [:registrations id] {:id id :position (position form) :source (canonical/sha256 raw)}))

      (and core? (= "set!" head-name) (= 3 (count form))
           (= '*warn-on-reflection* (second form)) (boolean? (nth form 2))) context

      :else (refuse! :api-source-form form))))
(m/=> absorb [:=> [:cat Context Form sc/Source] Context])

(defn- parse-options
  "Bound parsed nodes and disable read-time evaluation and custom data-reader execution."
  [platform max-nodes nodes]
  (parser/normalize-opts
   {:all true :read-eval false :read-cond :allow :features #{platform}
    :auto-resolve-ns true :readers {'js #(list 'api-source/js %)}
    :regex #(list 'api-source/regex %)
    :postprocess (fn [{:keys [obj loc]}]
                   (when (> (vswap! nodes inc) max-nodes) (refuse! :api-source-limit obj))
                   (if (instance? clojure.lang.IObj obj) (vary-meta obj merge loc) obj))}))
(m/=> parse-options [:=> [:cat [:enum :clj :cljs] [:int {:min 1 :max 262144}] NodeCounter] NativeOptions])

(defn- read-next!
  "Translate reader failures at bounded coordinates; never expose exception messages or source forms."
  [reader options]
  (let [location #(with-meta '() {:row (parser/get-line-number reader)
                                  :col (parser/get-column-number reader)})]
    (try
      (parser/parse-next+string reader options)
      (catch Exception error
        (if (m/validate sc/Failure (ex-data error)) (throw error)
            (refuse! :api-source-read (location))))
      (catch StackOverflowError _ (refuse! :api-source-limit (location))))))
(m/=> read-next! [:=> [:cat NativeReader NativeOptions] [:tuple [:or Form End] sc/Source]])

(defn- valid-unicode?
  "Reject unpaired UTF-16 surrogates before replacement encoding can collapse distinct source identities."
  [text]
  (loop [i 0]
    (if (= i (count text)) true
        (let [value (.charAt ^String text i)]
          (cond
            (Character/isHighSurrogate value)
            (and (< (inc i) (count text)) (Character/isLowSurrogate (.charAt ^String text (inc i)))
                 (recur (+ i 2)))
            (Character/isLowSurrogate value) false
            :else (recur (inc i)))))))
(m/=> valid-unicode? [:=> [:cat sc/Source] :boolean])

(defn- finish
  "Refuse unbound declarations and orphan registrations before returning deterministic observations."
  [context request forms nodes]
  (when (seq (:declared context)) (refuse! :api-source-unbound nil))
  (let [declarations (vec (sort-by :id (vals (:definitions context))))
        registrations (vec (sort-by :id (vals (:registrations context))))
        names (set (map :id declarations))]
    (when-not (every? #(contains? names (:id %)) registrations) (refuse! :api-source-spec nil))
    {:profile :passive-declarations-v1 :platform (:platform request)
     :namespace (:namespace context) :source (canonical/sha256 (:source request))
     :forms forms :nodes nodes :declarations declarations :registrations registrations}))
(m/=> finish [:=> [:cat Context sc/Request [:int {:min 1 :max 4096}] [:int {:min 1 :max 262144}]] sc/Result])

(defn scan
  "Observe one bounded source file on an explicit CLJ or CLJS platform without loading its namespace.
   Records all supported declarations (including private vars and aliases) and exact source hashes;
   unsupported top-level forms, unresolved declares and ambiguous namespace configuration refuse.
   Returned binding records are not assumed callable or schemas. This is passive source evidence,
   not export-completeness, macro-expansion, compilation or semantic-compatibility proof. Fresh
   runtime/analyzer observations and total explicit role policy must reconcile it before publication.
   Examples: (scan {:source \"(ns demo) (def answer 42)\" :platform :clj :limits default-limits})."
  [request]
  (when-not (m/validate sc/Request request) (refuse! :api-source-shape nil))
  (let [{:keys [source platform limits]} request
        _ (when-not (valid-unicode? source) (refuse! :api-source-read nil))
        _ (when (> (canonical/utf8-size source) (:bytes limits)) (refuse! :api-source-limit nil))
        reader (parser/source-reader source) nodes (volatile! 0) end (Object.)
        options (assoc (parse-options platform (:nodes limits) nodes) :eof end)]
    (loop [context nil forms 0]
      (let [[form raw] (read-next! reader options)]
        (if (identical? end form)
          (if context (finish context request forms @nodes) (refuse! :api-source-namespace nil))
          (do
            (when (>= forms (:forms limits)) (refuse! :api-source-limit form))
            (if context
              (recur (absorb context form raw) (inc forms))
              (do (when-not (and (seq? form) (contains? '#{ns clojure.core/ns cljs.core/ns} (first form)))
                    (refuse! :api-source-namespace form))
                  (recur (assoc (namespace-context form) :definitions {} :declared #{} :registrations {}) 1)))))))))
(m/=> scan [:=> [:cat sc/Request] sc/Result])
