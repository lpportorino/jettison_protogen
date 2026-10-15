(ns gate.extension-test
  "Real extension boundaries with independent controls and exact refusal attribution."
  (:require [clojure.string :as str]
            [clojure.test :as test :refer [deftest is]]
            [gate.admission :as admission]
            [gate.archive :as archive]
            [gate.canonical :as canonical]
            [gate.extension :as extension]
            [gate.extension-contract :as ec]
            [gate.extension-fixture :as fixture]
            [gate.extension-io :as extension-io]
            [gate.extension-view :as view]
            [gate.portable :as portable]
            [gate.report :as report]
            [gate.report-io :as reader]
            [malli.instrument :as mi])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn code [f] (try (f) nil (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))
(defn uninstrumented
  "The var's own function, so a refusal test exercises the guard under test rather than the
   instrumentation that would refuse the same input first."
  [v] (or (mi/-original v) @v))
(defn observe [context request] (extension/observe context (fixture/core) request))
(defn asset [] {:javascript "window.extensionControl = true;" :digest (canonical/sha256 "window.extensionControl = true;")})
(defn directory [] (str (Files/createTempDirectory "extension-proof-" (make-array FileAttribute 0))))
(defn reseal [document]
  (assoc document :artifact (canonical/sha256 (str "gate.extension.sidecar.v1\n"
                                                   (canonical/encode-portable (dissoc document :artifact) (:sidecar-bytes ec/limits))))))

(deftest public-roundtrip-control
  (let [{:keys [context core sidecar pair]} (fixture/example)
        encoded (extension/encode pair)
        decoded (extension/decode context core encoded extension-io/limits)]
    (is (= sidecar (:sidecar decoded)))
    (is (= :incomplete (:status (:core decoded))))
    (is (= "900719925474099312345" (get-in sidecar [:observations 0 :payload :cache :bytes])))
    (is (= [:example.cache/observation :example.quality/notes] (mapv :extension/id (:contracts sidecar))))
    (is (= ["cache-1" "quality-1"] (mapv :observation/id (:observations sidecar))))
    (is (= "Portable 🪐" (get-in sidecar [:observations 0 :projection :text])))
    (is (nil? (get-in sidecar [:observations 1 :projection])))
    (is (= core (admission/decode (canonical/encode core 1048576) :run-archive admission/default-limits)))))

(deftest duplicate-registrations-refuse
  (let [registration {:contract fixture/cache-contract}
        changed (assoc-in registration [:contract :payload/schema] [:map {:closed true}])]
    (doseq [registrations [[registration registration] [registration changed] [changed registration]]]
      (is (= :extension-duplicate (code #(extension/prepare registrations)))))))

(deftest schemas-refuse-unsafe-or-unresolved-forms
  (doseq [form [[:map] [:map {:closed false}] [:any] [:ref :example/missing]
                [:string] [:vector {:min 0 :max 1000001} :boolean]
                [:multi {:dispatch :state} [:malli.core/default [:map {:closed true}]]]
                [:map {:closed true} [:a :boolean] [:a :nil]]]]
    (is (= :extension-schema (code #(extension/prepare [{:contract (assoc fixture/cache-contract :payload/schema form)}]))) (pr-str form)))
  (let [definition {:schema/id :example/cycle :schema/form [:ref :example/cycle]}]
    (is (= :extension-schema (code #(extension/prepare [{:contract (assoc fixture/cache-contract
                                                                          :payload/definitions [definition]
                                                                          :payload/schema [:ref :example/cycle])}])))))
  (is (= :extension-duplicate
         (code #(extension/prepare [{:contract (update fixture/cache-contract :payload/definitions
                                                       conj (first (:payload/definitions fixture/cache-contract)))}])))))

(deftest prepared-contexts-remain-independent
  (let [original {:contract fixture/cache-contract}
        alternative {:contract (assoc fixture/cache-contract :payload/schema [:map {:closed true} [:value :boolean]])}
        a (extension/prepare [original]) b (extension/prepare [alternative])
        alt (assoc (fixture/request) :payload {:value true})]
    (is (= fixture/payload (:payload (observe a (fixture/request)))))
    (is (= {:value true} (:payload (observe b alt))))
    (is (= :extension-payload (code #(observe a alt))))
    (is (= :extension-payload (code #(observe b (fixture/request)))))
    (let [registration (first (fixture/registrations)) context (extension/prepare [registration])]
      (with-redefs [fixture/projector (fn [_] (throw (ex-info "Changed Var" {})))]
        (is (= "Computation observation" (get-in (observe context (fixture/request)) [:projection :title])))))))

(deftest exact-version-and-payload-admission
  (let [a {:contract fixture/cache-contract} b (assoc-in a [:contract :extension/version] 2)
        context (extension/prepare [a b])]
    (is (= 2 (:extension/version (observe context (assoc (fixture/request) :extension/version 2)))))
    (is (= :extension-version (code #(observe context (assoc (fixture/request) :extension/version 3)))))
    (is (= :extension-unknown (code #(observe context (assoc (fixture/request) :extension/id :other/missing)))))
    (doseq [payload [(assoc fixture/payload :extra true) {:cache {:state :hit :bytes "01"}}
                     {:cache {:state :miss :reason :unknown}} {:cache {:state :unknown}}
                     {:cache {:state :hit :bytes 999}}]]
      (is (= :extension-payload (code #(observe context (assoc (fixture/request) :payload payload))))))))

(deftest contract-and-core-bindings-refuse-tampering
  (let [{:keys [context core sidecar]} (fixture/example)
        forged-schema (reseal (assoc-in sidecar [:observations 0 :schema/fingerprint] (canonical/sha256 "wrong")))
        changed-core (archive/create (assoc (:scope core) :label "Other") (:graph core) fixture/unavailable fixture/unavailable)]
    (is (= :extension-fingerprint (code #(extension/pair context core forged-schema))))
    (is (= :extension-binding (code #(extension/pair context changed-core sidecar))))
    (is (= :extension-identity (code #(extension/pair context core (assoc sidecar :artifact (canonical/sha256 "wrong"))))))
    (is (= :extension-manifest (code #(extension/pair context core (reseal (assoc sidecar :contracts []))))))
    (is (= :extension-observation (code #(extension/create context core [(first (:observations sidecar)) (first (:observations sidecar))]))))
    (doseq [[field value expected] [[:source "missing" :extension-source]
                                    [:subject {:kind :node :id "missing"} :extension-subject]]]
      (is (= expected (code #(observe context (assoc (fixture/request) field value))))))))

(deftest projection-failures-do-not-disappear
  (let [registration (first (fixture/registrations))]
    (doseq [[f expected] [[(fn [_] {:title "Missing required fields"}) :extension-projection]
                          [(fn [_] (throw (ex-info "Private payload must never escape" {}))) :extension-projector]]]
      (let [context (extension/prepare [(assoc-in registration [:projector :projector/fn] f)])]
        (is (= expected (code #(observe context (fixture/request)))))))
    (let [context (extension/prepare [(assoc-in registration [:projector :implementation/source :digest] (canonical/sha256 "wrong"))])]
      (is (= :extension-source (code #(observe context (fixture/request)))))))
  (let [{:keys [core sidecar]} (fixture/example)
        reader (extension/prepare [{:contract fixture/cache-contract} {:contract fixture/quality-contract}])]
    (is (= sidecar (:sidecar (extension/pair reader core sidecar))))))

(deftest bounded-codec-does-not-admit-unknown-data
  (let [{:keys [context core pair]} (fixture/example) encoded (extension/encode pair)]
    (is (= :trailing-input (code #(extension/decode context core (str encoded " {}") extension-io/limits))))
    (is (= :unknown-keyword (code #(extension/decode context core (str/replace encoded ":hit" ":never.registered/new") extension-io/limits))))
    (is (= :input-byte-limit (code #(extension/decode context core encoded (assoc extension-io/limits :bytes 10)))))
    (is (= :extension-limit (code #(extension/decode context core encoded admission/default-limits))))
    (is (= :unknown-keyword (code #(admission/decode encoded :run-archive admission/default-limits))))
    (is (= :invalid-encoding-input (code #((uninstrumented #'canonical/encode) {:not "a core value"} 1024)))
        "core encode keeps its own admission, judged past instrumentation"))
  (let [context (extension/prepare [{:contract fixture/cache-contract}])]
    ;; Both halves of the surrogate check: a lone high surrogate and a lone low
    ;; surrogate are each invalid UTF-16; the paired non-BMP label is the control.
    (is (= :portable-shape (code #(observe context (assoc-in (fixture/request) [:payload :label] (str (char 55296)))))))
    (is (= :portable-shape (code #(observe context (assoc-in (fixture/request) [:payload :label] (str (char 56320)))))))
    (is (= :portable-shape (code #(observe context (assoc-in (fixture/request) [:payload :label] (str "a" (char 56320) "b"))))))
    (is (= "Portable 🪐" (get-in (observe context (fixture/request)) [:payload :label])))))

(deftest unbounded-sequences-are-refused-without-being-realized
  ;; A sequence is outside the portable shape whatever its length, so the refusal
  ;; must come before any count: counting an unbounded one never returns. Each
  ;; tripwire throws its own code the moment anything reads past its prefix, and
  ;; each assertion takes a fresh one, because a lazy sequence whose realization
  ;; threw is left truncated at the prefix and would pass a second count.
  (let [tripwire #(concat [1 2] (lazy-seq (throw (ex-info "realized past the prefix" {:code :realized}))))
        {:keys [context]} (fixture/example)]
    (is (= :portable-shape (code #(portable/require-bounded! (tripwire)))))
    (is (= :portable-shape (code #(portable/require-bounded! {:payload (tripwire)}))))
    (is (= :portable-shape (code #(portable/require-bounded! [[(tripwire)]]))))
    (is (= :portable-shape (code #(observe context (assoc-in (fixture/request) [:payload :label] (tripwire))))))
    (is (= :portable-shape (code #(portable/require-bounded! '(1 2 3)))) "a finite list is the same shape refusal")
    (is (= :portable-shape (code #(portable/require-bounded! #{1}))) "a set is the same shape refusal")
    (is (= [1 {:a "b"}] (portable/require-bounded! [1 {:a "b"}])) "the control: a vector of maps passes untouched")))

(defrecord Probe [a])
(deftest portable-values-are-exact-and-plain
  ;; A record satisfies map? and would be written as a plain map, losing its
  ;; type on the way back; a BigInt or a ratio satisfies integer?/number? and
  ;; sits outside the portable integer domain. All are refused by shape.
  (is (= :portable-shape (code #(portable/require-bounded! (->Probe 1)))))
  (is (= :portable-shape (code #(portable/require-bounded! [{:x (->Probe 1)}]))))
  ;; Instrumentation refuses a non-fixed integer before the guard; production
  ;; has no instrumentation, so the guard is judged directly.
  (is (= :portable-shape (code #((uninstrumented #'portable/require-bounded!) 5N))))
  (is (= :portable-shape (code #((uninstrumented #'portable/require-bounded!) 1/2))))
  (is (= :portable-shape (code #(portable/require-bounded! (long 2147483648)))))
  (is (= [0 2147483647 "x"] (portable/require-bounded! [0 2147483647 "x"])) "the control passes untouched"))

(deftest rendering-refuses-a-core-page-without-its-main-element
  (let [{:keys [pair]} (fixture/example)]
    (with-redefs [report/render (fn [& _] "<html><body>no main</body></html>")]
      (is (= :extension-output (code #(extension-io/render pair (asset))))))))

(deftest canonical-ordering-is-deterministic
  (let [{:keys [context core sidecar]} (fixture/example)
        reordered (update fixture/cache-contract :payload/definitions #(vec (reverse %)))
        other (extension/prepare [{:contract reordered} {:contract fixture/quality-contract}])]
    (is (= sidecar (extension/create context core (vec (reverse (:observations sidecar))))))
    (is (= sidecar (:sidecar (extension/pair other core sidecar))))))

(deftest bounded-pages-preserve-frontier-and-pair-identity
  (let [{:keys [context core pair sidecar]} (fixture/example)
        request {:path [:observations] :budget {:rows 1 :visits 10 :bytes 4096}}
        first-page (view/page pair request) second-page (view/page pair (assoc request :cursor (:next first-page)))
        changed (extension/pair context core (extension/create context core [(first (:observations sidecar))]))]
    (is (= :row-limit (:stop first-page)))
    (is (= :complete (:stop second-page)))
    (is (= 1 (:start second-page)))
    (is (= :extension-cursor (code #(view/page changed (assoc request :cursor (:next first-page))))))
    (is (= :extension-cursor (code #(view/page pair (assoc request :cursor (assoc (:next first-page) :offset 99))))))
    (is (= "900719925474099312345"
           (get-in (view/page pair {:path [:observations 0 :payload :cache :bytes]
                                    :budget {:rows 1 :visits 1 :bytes 4096}}) [:container :value])))
    (is (= :extension-path (code #(view/page pair (assoc request :path [:missing])))))
    (is (= :visit-limit (:stop (view/page pair (assoc request :budget {:rows 10 :visits 1 :bytes 4096})))))))

(deftest paired-publication-and-required-sidecar
  (let [{:keys [context core sidecar pair]} (fixture/example) output (directory)]
    (is (= (:artifact sidecar) (:artifact (extension-io/publish! output pair (asset)))))
    (is (= sidecar (:sidecar (extension-io/read-pair! context (str output "/run.edn") (str output "/extensions.edn")))))
    (is (= core (reader/read-archive! (str output "/run.edn") admission/default-limits)))
    (is (= :extension-output-exists (code #(extension-io/publish! output pair (asset)))))
    (is (= :report-unreadable (code #(extension-io/read-pair! context (str output "/run.edn") (str output "/missing.edn")))))
    (let [link (Path/of (str output "/linked.edn") (make-array String 0))]
      (Files/createSymbolicLink link (Path/of "extensions.edn" (make-array String 0)) (make-array FileAttribute 0))
      (is (= :report-unreadable (code #(extension-io/read-pair! context (str output "/run.edn") (str link))))))))

(deftest presentation-cannot-execute-or-change-core-verdict
  (let [context (extension/prepare (fixture/registrations)) core (fixture/core)
        hostile "</main></script><script>window.extensionAttack=true</script>"
        observation (extension/observe context core (assoc-in (fixture/request) [:payload :label] hostile))
        pair (extension/pair context core (extension/create context core [observation]))
        html (extension-io/render pair (asset))]
    (is (= :incomplete (:status (:core pair))))
    (is (str/includes? html "Archive: incomplete"))
    (is (str/includes? html "window.extensionControl = true;"))
    (is (not (str/includes? html "<script>window.extensionAttack=true</script>")))
    (is (str/includes? html "&lt;/main&gt;"))
    (is (str/includes? html (get-in pair [:sidecar :artifact])))
    (is (str/includes? html "caller-declared"))))

(deftest forged-prepared-values-refuse-without-instrumentation-help
  (is (= :extension-registration (code #(extension/observe {} (fixture/core) (fixture/request)))))
  (is (= :extension-shape (code #(extension/encode {:core (fixture/core) :sidecar {}}))))
  (is (= :extension-shape (code #(view/page {} {:path [] :budget {:rows 1 :visits 1 :bytes 1024}}))))
  (is (= :extension-shape (code #(extension-io/render {} (asset))))))

(deftest schema-shape-and-expanded-work-have-hard-limits
  (doseq [form [[:map {:closed true} 1] [:multi {:dispatch :state} 1]
                [:ref :gate.extension/natural :extra] [:tuple] [:enum :same :same]]]
    (is (= :extension-schema (code #(extension/prepare [{:contract (assoc fixture/cache-contract :payload/schema form)}])))))
  (let [contract (assoc fixture/cache-contract :payload/schema [:ref :gate.extension/natural])
        context (extension/prepare [{:contract contract}])]
    (is (= "123456789012345678901234567890" (:payload (observe context (assoc (fixture/request) :payload "123456789012345678901234567890"))))))
  (is (= :extension-registration (code #(extension/prepare (vec (repeat 65 {:contract fixture/cache-contract}))))))
  (let [ids (mapv #(keyword "example" (str "node-" %)) (range 12))
        definitions (mapv (fn [i] {:schema/id (nth ids i)
                                   :schema/form (if (zero? i) :boolean
                                                    [:tuple [:ref (nth ids (dec i))] [:ref (nth ids (dec i))]])}) (range 12))]
    (is (= :extension-limit (code #(extension/prepare [{:contract (assoc fixture/cache-contract
                                                                         :payload/schema [:ref (peek ids)]
                                                                         :payload/definitions definitions)}]))))))

(deftest payload-and-page-byte-limits-retain-no-false-progress
  (let [contract (assoc fixture/cache-contract :payload/schema
                        [:map {:closed true} [:rows [:vector {:min 0 :max 32} [:string {:min 0 :max 4096}]]]])
        context (extension/prepare [{:contract contract}])]
    (is (= :encoded-byte-limit
           (code #(observe context (assoc (fixture/request) :payload {:rows (vec (repeat 17 (apply str (repeat 4096 "a"))))}))))))
  (let [context (extension/prepare [{:contract fixture/cache-contract}]) core (fixture/core)
        observation (observe context (assoc-in (fixture/request) [:payload :label] (apply str (repeat 4096 "x"))))
        pair (extension/pair context core (extension/create context core [observation]))
        request {:path [:observations 0 :payload] :budget {:rows 10 :visits 10 :bytes 1024}}
        first-page (view/page pair request)
        stopped (view/page pair (assoc request :cursor (:next first-page)))]
    (is (= :byte-limit (:stop first-page)))
    (is (= :byte-limit (:stop stopped)))
    (is (= (:start stopped) (:end stopped)))
    (is (= :complete (:stop (view/page pair (-> request (assoc :cursor (:next stopped)) (assoc-in [:budget :bytes] 16384))))))
    (is (= :extension-limit (code #(extension/create context core (vec (repeat 4097 observation))))))))

(deftest partial-publication-and-malformed-file-never-return-an-accepted-pair
  (let [{:keys [context core pair]} (fixture/example) output (directory)
        install @#'extension-io/install!]
    (is (= :extension-output
           (code #(with-redefs-fn {#'extension-io/install!
                                   (fn [target filename content]
                                     (if (= filename "extensions.edn") (throw (java.io.IOException. "injected failure"))
                                         (install target filename content)))}
                    (fn [] (extension-io/publish! output pair (asset)))))))
    (is (= core (reader/read-archive! (str output "/run.edn") admission/default-limits)))
    (is (= :report-unreadable (code #(extension-io/read-pair! context (str output "/run.edn") (str output "/extensions.edn")))))
    (Files/write (Path/of (str output "/invalid.edn") (make-array String 0))
                 (byte-array [(unchecked-byte 255)]) (make-array java.nio.file.OpenOption 0))
    (is (= :report-utf8 (code #(extension-io/read-pair! context (str output "/run.edn") (str output "/invalid.edn")))))))

(deftest positive-observations-preserve-every-core-failure-state
  (let [context (extension/prepare (fixture/registrations)) original (fixture/core)]
    (doseq [[outcome status] [[:failed :failed] [:error :failed] [:cancelled :cancelled]]]
      (let [core (archive/create (:scope original) (assoc-in (:graph original) [:nodes 0 :outcome] outcome)
                                 fixture/unavailable fixture/unavailable)
            observation (extension/observe context core (fixture/request))
            pair (extension/pair context core (extension/create context core [observation]))]
        (is (= status (:status (:core pair))))
        (is (= outcome (get-in pair [:core :graph :nodes 0 :outcome])))))))

(defn -main [& _]
  (mi/instrument!)
  (let [{:keys [fail error pass] tests :test} (test/run-tests 'gate.extension-test)]
    (shutdown-agents)
    (System/exit (if (and (pos? tests) (pos? pass) (zero? fail) (zero? error)) 0 1))))
