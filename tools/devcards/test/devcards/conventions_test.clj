(ns devcards.conventions-test
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [devcards.conventions :as conventions]
            [lvgl-codegen.generated.enums :as enums]))

(set! *warn-on-reflection* true)

(defn- authored-edn
  "The AUTHORED manifest as it sits on disk, read WITHOUT the loader — so an
   assertion about what the file itself carries cannot be satisfied by the
   loader folding a derived section over it."
  []
  (edn/read-string (slurp conventions/edn-home)))

(defn- scratch-edn!
  "Write `form` to a scratch EDN file outside the tree and return its path."
  ^String [form]
  (let [f (java.io.File/createTempFile "conventions-test" ".edn")]
    (.deleteOnExit f)
    (spit f (pr-str form))
    (.getPath f)))

(defn- thrown-data
  "The ex-data of whatever `f` throws, or `::none` when it returns.

   Catches Throwable rather than Exception: a guard converted from `ex-info` to
   `assert` throws an Error, and a leg that caught only Exception would stop
   catching without stopping passing. `ex-data` on a non-ex-info Throwable is
   nil, so a throw of the WRONG kind fails the assertion instead of satisfying
   it."
  [f]
  (try (f) ::none (catch Throwable t (ex-data t))))

(deftest derived-sections-are-the-generated-lvgl-tables
  (let [m (conventions/load-conventions)]
    (testing "every derived section IS its generated binding, value for value"
      (doseq [[k table] conventions/lvgl-derived-sections]
        (is (= table (get m k))
            (str k " must be the generated table verbatim"))))
    (testing "and the generated tables are the LVGL ones, not empty stand-ins"
      (is (= (:state-selectors m) enums/state-keyword->int))
      (is (= (:obj-flags m) enums/obj-flag-keyword->int))
      (is (pos? (count (:state-selectors m))))
      (is (pos? (count (:obj-flags m)))))))

(deftest the-authored-edn-hand-carries-no-derived-section
  ;; THE regression leg. It reds the moment a derived table is re-typed into the
  ;; authored manifest — the exact revert this derivation replaced — and it reds
  ;; by its OWN assertion over the file's bytes, not because the loader threw.
  (let [authored (authored-edn)
        derived-keys (set (keys conventions/lvgl-derived-sections))]
    (testing "non-vacuity: the manifest was actually read and parsed"
      (is (map? authored))
      (doseq [k conventions/required-authored-sections]
        (is (contains? authored k)
            (str "authored manifest lost its own section " k))))
    (testing "no LVGL-derived section is hand-carried in the authored manifest"
      (is (= #{} (set (filter derived-keys (keys authored))))))))

(deftest a-hand-carried-derived-section-is-refused
  ;; The guard's committed negative leg: a manifest that re-adds a derived
  ;; table must be REFUSED, not silently merged over.
  ;; Poison a manifest with EVERY derived key stripped first, so the leg
  ;; asserts exactly one name however the on-disk file happens to look — a leg
  ;; whose expected value moves with the tree cannot attribute its own red.
  (doseq [k (keys conventions/lvgl-derived-sections)]
    (let [clean (apply dissoc (authored-edn) (keys conventions/lvgl-derived-sections))
          poisoned (assoc clean k {:bogus 1})
          path (scratch-edn! poisoned)
          data (with-redefs [conventions/edn-home path]
                 (thrown-data #(conventions/load-conventions)))]
      (is (= [k] (:hand-carried data))
          (str "load-conventions must refuse a hand-carried " k
               ", naming it; got " (pr-str data))))))

(deftest an-empty-derived-table-is-refused
  ;; A derived section that arrives empty would export an empty vocabulary to
  ;; every downstream producer, which reads exactly like a vocabulary that does
  ;; not exist.
  (let [data (with-redefs [conventions/lvgl-derived-sections {:obj-flags {}}]
               (thrown-data #(conventions/load-conventions)))]
    (is (= [:obj-flags] (:empty data))
        (str "load-conventions must refuse an empty derived table; got "
             (pr-str data)))))

(deftest the-committed-json-projection-carries-the-loaded-manifest
  ;; The committed export is what a consumer vendors. `make -f renderer.mk
  ;; conventions-projection` holds it to the generator BYTE for byte; this leg
  ;; holds it to the manifest's DATA, and runs wherever the unit suite runs.
  (let [committed (json/read-str (slurp conventions/json-out))
        expected (json/read-str
                  (with-out-str (json/pprint (conventions/load-conventions)
                                             :key-fn name)))]
    (is (= expected committed))
    (testing "non-vacuity: the export names the derived sections"
      (doseq [k (keys conventions/lvgl-derived-sections)]
        (is (seq (get committed (name k)))
            (str "committed projection is missing section " (name k)))))))
