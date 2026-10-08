(ns gate.api-source-test
  "Synthetic independent declaration oracles, platform branches and passive-reader rejection probes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.properties :as prop]
            [gate.admission :as admission]
            [gate.api-source :as source]
            [gate.api-source-contract :as sc]
            [gate.canonical :as canonical]
            [malli.core :as m]
            [malli.generator :as mg]))

(def raw-scan
  "Capture the uninstrumented public boundary to test malformed requests independently of Malli's input guard."
  source/scan)

(defn- scan
  "Use the production finite budgets for one synthetic source and selected platform."
  ([text] (scan text :clj))
  ([text platform] (source/scan {:source text :platform platform :limits source/default-limits})))

(defn- refusal
  "Require closed diagnostics, so leaked parser messages cannot accidentally satisfy a negative fixture."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo error
                 (is (m/validate sc/Failure (ex-data error))) (:code (ex-data error)))))

(deftest observes-functions-aliases-values-and-private-definitions
  (let [text "(ns demo (:require [malli.core :as m]))
              (defn public' \"doc\" [x] x)
              (m/=> public' [:=> [:cat :int] :int])
              (def alias public') (def table {:key true})
              (defn- hidden [x] x) (defn concealed {:private true} [] nil)
              (def ^:private secret 1)"
        result (scan text)]
    (is (m/validate sc/Result result))
    (is (= [[:binding "demo/alias" false] [:function "demo/concealed" true]
            [:function "demo/hidden" true] [:function "demo/public'" false]
            [:binding "demo/secret" true] [:binding "demo/table" false]]
           (mapv (juxt :kind :id :private?) (:declarations result))))
    (is (= ["demo/public'"] (mapv :id (:registrations result))))
    (is (= (canonical/sha256 text) (:source result)))
    (is (= result (scan text)))
    (is (= result (admission/decode (canonical/encode result 65536) :value admission/default-limits)))
    (is (nil? (find-ns 'demo)))))

(deftest selects-platform-without-implicit-jvm-feature
  (let [text "(ns example (:require #?(:clj [clojure.string :as text] :cljs [cljs.string :as text])))
              #?(:clj (def host ::text/item) :cljs (def browser ::text/item))
              (def both #?(:clj 1 :cljs 2))"
        host (scan text :clj) browser (scan text :cljs)]
    (is (= ["example/both" "example/host"] (mapv :id (:declarations host))))
    (is (= ["example/both" "example/browser"] (mapv :id (:declarations browser))))
    (is (= (:source host) (:source browser)))
    (is (not= (:platform host) (:platform browser)))))

(deftest resolves-real-malli-registration-only
  (doseq [text ["(ns demo (:require [other.core :as m])) (defn f [] 1) (m/=> f :int)"
                "(ns demo (:require [other.core :refer [defn]])) (defn f [] 1)"
                "(ns demo (:refer-clojure :exclude [defn])) (defn f [] 1)"
                "(ns demo) (def defn identity) (defn f [] 1)"]]
    (is (= :api-source-form (refusal #(scan text)))))
  (is (= ["demo/f"]
         (mapv :id (:registrations
                    (scan "(ns demo (:require [malli.core :refer [=>]])) (defn f [] 1) (=> f [:=> [:cat] :int])"))))))

(deftest declarations-reconcile-and-duplicates-refuse
  (is (= ["demo/later"] (mapv :id (:declarations (scan "(ns demo) (declare later) (defn later [] 1)")))))
  (doseq [text ["(ns demo) (declare later)" "(ns demo) (def unbound)"]]
    (is (= :api-source-unbound (refusal #(scan text)))))
  (doseq [text ["(ns demo) (def x 1) (def x 2)"
                "(ns demo (:require [malli.core :as m])) (defn f [] 1) (m/=> f :int) (m/=> f :int)"]]
    (is (= :api-source-duplicate (refusal #(scan text)))))
  (is (= :api-source-spec
         (refusal #(scan "(ns demo (:require [malli.core :as m])) (m/=> missing :int)")))))

(deftest unsupported-generators-and-top-level-effects-never-disappear
  (doseq [form ["(defrecord Record [x])" "(defprotocol P (f [x]))" "(emit-api)"
                "(intern *ns* 'hidden identity)" "(eval '(def hidden 1))"
                "(do (def hidden 1))" "(alter-meta! #'known assoc :private false)"
                "(set! *warn-on-reflection* (do (def hidden 1) true))" "42"]]
    (testing form (is (= :api-source-form (refusal #(scan (str "(ns demo) " form)))))))
  (is (= :macro (-> (scan "(ns demo) (defmacro macro! [x] `(identity ~x))") :declarations first :kind))))

(deftest reader-does-not-evaluate-or-use-ambient-tags
  (let [called (atom 0)]
    (binding [*data-readers* {'danger (fn [_] (swap! called inc))}]
      (doseq [text ["(ns demo) (def x #=(throw (Exception. \"executed\")))"
                    "(ns demo) (def x #danger 1)"]]
        (is (= :api-source-read (refusal #(scan text)))))
      (is (zero? @called)))))

(deftest counts-utf8-bytes-forms-and-parser-nodes
  (doseq [value [nil 42 [] "source" true]]
    (is (= :api-source-shape (refusal #(raw-scan value)))))
  (let [text "(ns demo) (def emoji \"😀\")"
        request {:source text :platform :clj :limits source/default-limits}]
    (is (= :api-source-limit
           (refusal #(source/scan (assoc-in request [:limits :bytes] (count text))))))
    (is (= :api-source-limit (refusal #(source/scan (assoc-in request [:limits :forms] 1)))))
    (is (= :api-source-limit (refusal #(source/scan (assoc-in request [:limits :nodes] 1)))))
    (is (= :api-source-shape (refusal #(raw-scan (assoc request :extra true)))))
    (is (= :api-source-shape (refusal #(raw-scan (assoc request :platform :bb)))))))

(deftest exact-source-evidence-binds-ordered-arity-contracts
  (let [before "(ns demo (:require [malli.core :as m]))
                (defn f ([] 1) ([x] \"result\"))
                (m/=> f [:function [:=> [:cat] :int] [:=> [:cat :int] :string]])"
        after (str/replace before
                           "[:function [:=> [:cat] :int] [:=> [:cat :int] :string]]"
                           "[:function [:=> [:cat] :string] [:=> [:cat :int] :int]]")]
    (is (not= (-> (scan before) :registrations first :source)
              (-> (scan after) :registrations first :source)))
    (is (= (:declarations (scan before)) (:declarations (scan after))))))

(deftest malformed-or-multiple-namespaces-refuse
  (doseq [[text expected] [["" :api-source-namespace]
                           ["(def first 1)" :api-source-namespace]
                           ["(ns demo) (ns second)" :api-source-form]
                           ["(ns demo (:require [x :refer :all]))" :api-source-namespace]
                           ["(ns demo) (def" :api-source-read]]]
    (is (= expected (refusal #(scan text))))))

(deftest malformed-unicode-and-deep-reader-input-have-closed-refusals
  (is (= :api-source-read (refusal #(scan (str "(ns demo) (def x \"" (char 0xd800) "\")")))))
  (let [text (str "(ns demo) (def x " (apply str (repeat 20000 "[")) "0"
                  (apply str (repeat 20000 "]")) ")")]
    (is (= :api-source-limit (refusal #(scan text))))))

(deftest source-keyword-cannot-masquerade-as-end-of-stream
  (is (= :api-source-form
         (refusal #(scan "(ns demo) :edamame.core/eof (def omitted 1)")))))

(deftest explicit-attributes-override-defn-minus-privacy
  (doseq [[declaration expected] [["(defn- visible {:private false} [] 1)" false]
                                  ["(defn- visible ([] 1) {:private false})" false]
                                  ["(defn- visible [] 1)" true]
                                  ["(defn ^:private visible {:private false} [] 1)" false]]]
    (is (= expected (-> (scan (str "(ns demo) " declaration)) :declarations first :private?)))))

(deftest generated-declaration-rosters-have-independent-exact-oracles
  (let [trial (tc/quick-check
               80
               (prop/for-all [flags (mg/generator [:vector {:min 1 :max 16} :boolean])]
                             (let [text (str "(ns sample) "
                                             (apply str (map-indexed (fn [i private?]
                                                                       (str "(def " (when private? "^:private ") "v" i " " i ")")) flags)))
                                   result (scan text)]
                               (and (m/validate sc/Result result)
                                    (= (set (map-indexed (fn [i flag] [(str "sample/v" i) flag]) flags))
                                       (set (map (juxt :id :private?) (:declarations result)))))))
               :seed 684729)]
    (is (:pass? trial) (pr-str (dissoc trial :result)))))
