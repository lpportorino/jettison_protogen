(ns press.doc.figure-test
  "The figure description against the browser half, both directions and
   unconditionally: every `tools/press/fixtures/figs/*.fig.json` validates
   here and is written back byte-identical; every negative case (one per
   refusal class of `desc.py`) is refused here. Canonical JSON is stable
   under key order, sorts by code point and refuses what has no form; a
   figure's state follows its two hashes; the house constants come from the
   browser half's one file; the SVG allowlist refuses everything the
   figure renderer does not write."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [press.config :as config]
            [press.doc :as doc]
            [press.doc.desc :as desc]
            [press.doc.figure :as fig]
            [press.doc.fixture :as fx]
            [press.doc.negatives :as neg]
            [press.doc.svg :as svg]))

(use-fixtures :each fx/cleanup)

(defn- press-fixtures
  "Every `*.fig.json` the browser half ships as a fixture."
  []
  (sort (filter #(str/ends-with? (.getName ^java.io.File %) ".fig.json")
                (.listFiles (io/file (config/from-root "tools/press/fixtures/figs"))))))

(defn- ->edn
  "A fixture tree as an author writes it in EDN: keyword keys, `:kind` a keyword."
  [t]
  (update (json/parse-string t true) :kind keyword))

(deftest every-press-fixture-is-accepted-and-written-back-byte-identical
  (let [fs (press-fixtures)
        h (fig/house)]
    (is (<= 3 (count fs)) "the browser half's fixtures are there — a missing set fails, it never skips")
    (doseq [^java.io.File f fs
            :let [raw (slurp f)
                  id (subs (.getName f) 0 (- (count (.getName f)) (count ".fig.json")))
                  d (fx/doc-dir! (doc/example))]]
      (testing (.getName f)
        (is (= [] (desc/problems (json/parse-string raw) h)))
        (io/make-parents (fig/fig-file d id ".edn"))
        (spit (fig/fig-file d id ".edn") (pr-str (->edn raw)))
        (is (= :unpicked (:state (first (doc/fig-status d)))) "valid, and not yet picked")
        (let [r (doc/fig-json! d id (str (io/file d "j")))]
          (is (= raw (slurp (:path r))) "the same bytes press accepts")
          (is (= (fig/sha256 raw) (:problem-hash r))))))))

(defn- corpus-on-disk
  "File name → bytes of every file in root-relative directory `dir`."
  [dir]
  (into (sorted-map) (for [^java.io.File f (.listFiles (io/file (config/from-root dir)))]
                       [(.getName f) (slurp f)])))

(deftest the-tracked-corpora-are-what-the-generator-writes
  (testing "a drift between tools/press/fixtures/desc_{negative,positive}/ and press.doc.negatives is red"
    (is (= (neg/negative-corpus) (corpus-on-disk neg/negative-dir)))
    (is (= (neg/positive-corpus) (corpus-on-disk neg/positive-dir)))))

(defn- rules-of-file
  "The rules `desc/problems` refuses corpus file `f` with."
  [h ^java.io.File f]
  (set (map :rule (desc/problems (json/parse-string (slurp f)) h))))

(deftest every-negative-file-is-refused-naming-its-rule
  (let [h (fig/house)
        m (json/parse-string (slurp (config/from-root (str neg/negative-dir "/manifest.json"))))
        cases (get m "cases")]
    (is (= (count neg/cases) (count cases)) "one manifest entry per case")
    (doseq [{:strs [file rule]} cases]
      (testing file
        (let [rs (rules-of-file h (io/file (config/from-root neg/negative-dir) file))]
          (is (seq rs) (str file " was ACCEPTED"))
          (is (contains? rs (keyword rule)) (str file " refused with " rs ", not " rule)))))))

(deftest every-positive-file-is-accepted-and-canonical
  (let [h (fig/house)]
    (doseq [[nm _] neg/positives
            :let [f (io/file (config/from-root neg/positive-dir) (str nm ".fig.json"))]]
      (testing nm
        (is (= [] (desc/problems (json/parse-string (slurp f)) h)))
        (is (= (slurp f) (fig/canonical-json (json/parse-string (slurp f)))) "written back byte-identical")))))

(deftest the-chart-base-is-accepted
  (testing "positive control for the chart-envelope cases: their unmutated base passes"
    (is (= [] (desc/problems (json/parse-string (fig/canonical-json neg/chart-base)) (fig/house))))))

(deftest every-negative-case-is-refused-through-fig-status
  (doseq [[nm :as c] neg/cases]
    (testing nm
      (let [d (fx/doc-dir! (doc/example))]
        (io/make-parents (fig/fig-file d "fig1" ".edn"))
        (spit (fig/fig-file d "fig1" ".edn") (neg/edn-text c))
        (is (= :invalid (:state (first (doc/fig-status d)))) (str nm " was not refused"))))))

(deftest canonical-json-is-stable
  (testing "the same data in a different key order is the same bytes and the same hash"
    (let [a (array-map :id "f" :kind :diagram :nodes [(array-map :rows [["t" "x"]] :id "n")])
          b (array-map :nodes [(array-map :id "n" :rows [["t" "x"]])] :kind :diagram :id "f")]
      (is (= (fig/canonical-json a) (fig/canonical-json b)))
      (is (= "{\"id\":\"f\",\"kind\":\"diagram\",\"nodes\":[{\"id\":\"n\",\"rows\":[[\"t\",\"x\"]]}]}\n"
             (fig/canonical-json a)))))
  (testing "keys sort by code point, as Python does: U+FF5E before U+1F600 (UTF-16 order is the reverse)"
    (is (= "{\"～\":1,\"😀\":2}\n" (fig/canonical-json {"😀" 2 "～" 1})))
    (is (neg? (fig/code-point-compare "～" "😀")))
    (is (pos? (compare "～" "😀")) "the control: Java's own order disagrees"))
  (testing "keywords become strings (namespace kept), kebab keys stay kebab"
    (is (= "{\"a/b\":\"c/d\",\"from-side\":\"s\"}\n" (fig/canonical-json {:from-side "s" :a/b :c/d}))))
  (testing "numbers: integers only"
    (is (= "[5,-3,12,0]\n" (fig/canonical-json [5 -3 12N 0])))
    (doseq [x [5.0 2.5 1e-5 1.50M]]
      (is (= :doc/not-json (fx/refusal #(fig/canonical-json [x]))) (str x))))
  (testing "strings: quotes, backslashes and control characters escaped; the rest as itself"
    (is (= "\"a\\\"b\\\\c\\nd\\te\\u0001 — ü\"\n" (fig/canonical-json "a\"b\\c\nd\te\u0001 — ü"))))
  (testing "empty containers, booleans, nil"
    (is (= "{\"a\":{},\"b\":[],\"c\":true,\"d\":null}\n" (fig/canonical-json {:a {} :b [] :c true :d nil}))))
  (testing "what has no JSON form is refused, never guessed"
    (doseq [x [{:a 'sym} {:a 1 "a" 2} {1 2} #{1} [(str (char 0xD800))]]]
      (is (= :doc/not-json (fx/refusal #(fig/canonical-json x))) (pr-str x)))))

(deftest sha256-of-bytes-and-text-agree
  (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" (fig/sha256 "")))
  (is (= (fig/sha256 "héllo") (fig/sha256 (.getBytes "héllo" "UTF-8")))))

(defn- state-of
  "The state of `fig1` in `d`."
  [d]
  (first (filter #(= "fig1" (:id %)) (doc/fig-status d))))

(deftest fig-status-follows-the-hashes
  (let [d (fx/doc-dir! (fx/with-figure) true)]
    (testing "a fresh pick is current"
      (is (= {:id "fig1" :state :current} (state-of d))))
    (testing "an edited description is stale, and says which hash"
      (spit (io/file d "figs" "fig1.edn") (pr-str (assoc fx/diagram :soft {:height 11})))
      (is (= :stale (:state (state-of d))))
      (is (str/includes? (:reason (state-of d)) "problem_hash"))
      (spit (io/file d "figs" "fig1.edn") (pr-str fx/diagram)))
    (testing "an edited SVG is stale too"
      (spit (io/file d "figs" "fig1.svg") (str fx/svg " "))
      (is (= :stale (:state (state-of d))))
      (is (str/includes? (:reason (state-of d)) "svg_sha256"))
      (spit (io/file d "figs" "fig1.svg") fx/svg)
      (is (= :current (:state (state-of d)))))
    (testing "no SVG, a broken pick, no pick"
      (io/delete-file (io/file d "figs" "fig1.svg"))
      (is (= :missing-svg (:state (state-of d))))
      (spit (io/file d "figs" "fig1.svg") fx/svg)
      (spit (io/file d "figs" "fig1.pick.json") (json/generate-string {"problem_hash" "abc"}))
      (is (= :invalid (:state (state-of d))))
      (io/delete-file (io/file d "figs" "fig1.pick.json"))
      (is (= :unpicked (:state (state-of d))))))
  (testing "a description file whose stem is not an id, or whose id is not its stem"
    (let [d (fx/doc-dir! (doc/example))]
      (fx/write-fig! d (assoc fx/diagram :id "fig2"))
      (io/make-parents (io/file d "figs" "x"))
      (spit (io/file d "figs" "fig3.edn") (pr-str fx/diagram))
      (spit (io/file d "figs" "bad name.edn") (pr-str fx/diagram))
      (let [by (into {} (map (juxt :id identity)) (doc/fig-status d))]
        (is (= :current (:state (by "fig2"))))
        (is (str/includes? (:reason (by "fig3")) "not its file stem"))
        (is (= :invalid (:state (by "bad name"))))))))

(deftest a-chart-is-current-by-its-record
  (testing "a chart description with its press chart record reads current, and a fraction must be a string"
    (let [d (fx/doc-dir! (doc/example))
          chart {:id "c1" :kind :chart :chart :bar :data {:series [{:name "a" :values [1 2 "19.5"]}]}}]
      (fx/write-fig! d chart)
      (spit (fig/fig-file d "c1" ".pick.json")
            (json/generate-string {"id" "c1" "problem_hash" (fig/sha256 (fig/canonical-json chart))
                                   "svg_sha256" (fig/sha256 fx/svg) "renderer" "chart"}))
      (is (= {:id "c1" :state :current} (first (doc/fig-status d))))
      (spit (fig/fig-file d "c1" ".edn") (pr-str (assoc-in chart [:data :series 0 :values 2] 19.5)))
      (is (str/includes? (:reason (first (doc/fig-status d))) "integers only"))
      (fx/write-fig! d (assoc chart :chart :pie))
      (is (= :invalid (:state (first (doc/fig-status d)))))
      (testing "a chart integer beyond ±100000 is refused like a diagram's"
        (fx/write-fig! d (assoc-in chart [:data :series 0 :values 0] 1000000000))
        (is (str/includes? (:reason (first (doc/fig-status d))) "out of range"))))))

(deftest house-constants-come-from-the-browser-half
  (let [h (fig/house)]
    (is (= (json/parse-string (slurp (config/from-root "tools/press/press/house.json"))) h)
        "the ONE file under the configured root, not a copy")
    (is (nil? (io/resource "press/doc/house.json")) "no resource copy exists")
    (is (= 476 (get h "canvas-width")))
    (is (every? #(>= (double (first %)) (double (get h "text-floor"))) (vals (get h "text"))))))

(deftest the-svg-allowlist
  (let [h (fig/house)
        ok (svg/inline fx/svg "f3-" h)]
    (testing "the renderer's own vocabulary passes, re-serialised with ids scoped to the occurrence"
      (is (str/includes? ok "<marker id=\"f3-arr\""))
      (is (str/includes? ok "marker-end=\"url(#f3-arr)\""))
      (is (str/includes? ok ">hub &amp; co</text>"))
      (is (str/starts-with? ok "<svg ")))
    (testing "each thing the renderer never writes is refused"
      (doseq [bad ["<svg><script>1</script></svg>"
                   "<svg><style>text{fill:red}</style></svg>"
                   "<svg onload=\"x\"/>"
                   "<svg><!-- c --></svg>"
                   "<!DOCTYPE svg [<!ENTITY x \"y\">]><svg>&x;</svg>"
                   "<svg><rect fill=\"url(https://x.invalid/y)\"/></svg>"
                   "<svg><foreignObject/></svg>"
                   "<svg><image href=\"https://x.invalid/a.png\"/></svg>"
                   "<svg><path marker-end=\"url(#nowhere)\"/></svg>"
                   "<svg>loose text</svg>"
                   "<svg><text style=\"fill:url(#a)\">x</text></svg>"
                   "<div/>"
                   "<svg>"]]
        (is (= :doc/bad-svg (fx/refusal #(svg/inline bad "f1-" h))) bad)))
    (testing "a CDATA section is read as text and emitted escaped, never as markup"
      (let [s (svg/inline (str/replace fx/svg "hub &amp; co" "<![CDATA[<script>x</script>]]>") "f2-" h)]
        (is (str/includes? s ">&lt;script&gt;x&lt;/script&gt;</text>"))
        (is (not (str/includes? s "<script")))))
    (testing "the svg docstring says so (it once claimed CDATA was refused)"
      (is (re-find #"CDATA[^.]*read as text" (:doc (meta #'svg/inline)))))))
