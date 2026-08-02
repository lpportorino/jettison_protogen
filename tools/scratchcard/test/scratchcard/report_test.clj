(ns scratchcard.report-test
  "Pins the VERDICT LINE, which is the one line the docs tell a reader to read
  first — `report.md`'s own header says a report burying its verdict gets
  skimmed, and the skill's step 3 is \"read report.md FIRST, the verdict is the
  line under the title\".

  So a verdict that renders empty is not a cosmetic defect: it is the one
  sentence the whole artifact exists to deliver, and its absence is silent."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [scratchcard.report :as report]))

(defn- manifest
  "A manifest skeleton. `run` and `renders` are what each case varies; the
  remaining keys only have to be present for the digest to render."
  [run renders findings]
  {:run (merge {:id "0001" :utc "20260101T000000Z" :card "probe"
                :elapsed-ms 1.0}
               run)
   :protogen {:short-sha "abc123" :dirty? false}
   :wasm {:sha256 (apply str (repeat 64 "a")) :abi 4}
   :toolchain {} :protocol {} :host {} :matrix {}
   :input {:path "/tmp/probe.edn" :sha256 (apply str (repeat 64 "b"))}
   :lanes {:producers [] :thresholds {} :caps {}
           :classes-sha "deadbeef" :declined []}
   :renders renders
   :findings findings})

(defn- cell
  [status]
  (cond-> {:cell "asgard-dark-800x480" :family 0 :dark 1 :w 800 :h 480
           :bp 0 :disp-tier "DISP_LARGE" :status status}
    (= :ok status) (assoc :fb-sha256 (apply str (repeat 64 "c")))))

(defn- verdict
  "The verdict line — the first non-blank line BELOW the title.

  Sections are joined with a blank line, so `second` on the split lands on
  that blank and every assertion then runs against `\"\"` — which fails, but
  for the wrong reason and identically no matter what the verdict says."
  [m]
  (->> (str/split-lines (report/report-md m))
       rest
       (map str/trim)
       (remove str/blank?)
       first))

(deftest every-cell-failing-still-states-a-verdict
  "A run whose cells ALL fail carries `:status :error` and NO run-level
  `:error` map — that key is written only for a run-level refusal such as
  INPUT_MISSING. Keying the failure branch on the status alone therefore
  produced `**FAILED — **: `, an empty reason and a dangling colon, while the
  branch that would have said `0/1 cells rendered; 1 FAILED` sat unreachable
  below it.

  Observed on a real run: deleting renderer/output/controls.wasm and
  regenerating wrote exactly that line."
  (let [v (verdict (manifest {:status :error}
                             [(cell :error)]
                             {:count 0 :unjudged 0 :clean? true :live-sample []}))]
    (testing "it names the counts rather than trailing off"
      (is (str/includes? v "1 FAILED"))
      (is (str/includes? v "0/1")))
    (testing "and carries no empty error slot"
      (is (not (str/includes? v "— **")))
      (is (not (str/ends-with? v ": "))))))

(deftest a-run-level-refusal-still-names-its-code-and-message
  "The other direction. A genuine run-level error must keep the code and the
  message; a fix that merely stopped consulting `:error` would pass the case
  above and lose the only diagnosis this line ever carries."
  (let [v (verdict (manifest {:status :error
                              :error {:code "INPUT_MISSING"
                                      :message "no screen file at /tmp/probe.edn"}}
                             []
                             {:count 0 :unjudged 0 :clean? true :live-sample []}))]
    (is (str/includes? v "INPUT_MISSING"))
    (is (str/includes? v "no screen file"))))

(deftest a-clean-run-and-a-findings-run-keep-their-verdicts
  (testing "clean"
    (let [v (verdict (manifest {:status :ok} [(cell :ok)]
                               {:count 0 :unjudged 0 :clean? true :live-sample []}))]
      (is (str/includes? v "CLEAN"))))
  (testing "rendered, with findings"
    (let [v (verdict (manifest {:status :ok} [(cell :ok)]
                               {:count 2 :unjudged 0 :clean? false
                                :live-sample [{:invariant :clipped :node "n"
                                               :cell "asgard-dark-800x480"}]}))]
      (is (str/includes? v "WITH FINDINGS"))
      (is (str/includes? v "2 finding(s)")))))
