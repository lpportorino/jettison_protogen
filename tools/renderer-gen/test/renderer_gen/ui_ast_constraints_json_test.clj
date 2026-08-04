(ns renderer-gen.ui-ast-constraints-json-test
  "Canary for `renderer-gen.ui-ast-constraints-json` — the emitter whose SUCCESS
   is what publishes `output/manifests/ui-ast-constraints.json`, so its clean
   output and its checked-nothing output are the same file.

   A RED IS NOT ENOUGH; IT MUST NAME ITS CLAUSE. These refusals overlap on
   plausible inputs — a constraint with a missing disposition and a registry
   entry naming a dead field would each be caught by more than one of them — so
   every case asserts EQUALITY on the `:clause` its mutation was aimed at. The
   emitter refuses on the FIRST clause that fires and carries exactly one
   `:clause`, so that equality IS the neighbour-silence assertion: any other
   clause reaching the input first fails the case.

   HERMETIC: every case is driven over synthetic proto/options/registry STRINGS,
   so the suite runs on a dirty checkout, restores nothing, and cannot strand the
   tree. The two cases that read committed files assert STRUCTURE and TOTALITY
   only — never a disposition or a bound, because either asserted here would be a
   second home for a fact the manifest exists to give exactly one."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [renderer-gen.ui-ast-constraints-json :as c]))

(def ^:private proto
  "A synthetic proto carrying one constraint of each disposition class plus the
   dense and sparse enums the range checks read. Its GREEN under `registry`
   below is what makes every red attributable to the mutation rather than to the
   fixture."
  (str "enum Dense {\n  D_ZERO = 0;\n  D_ONE = 1;\n  D_TWO = 2;\n}\n"
       "enum Sparse {\n  S_ZERO = 0;\n  S_FOUR = 4;\n}\n"
       "message Alpha {\n"
       "  string text = 1 [(buf.validate.field).string = {max_len: 63}];\n"
       "  uint32 width = 2 [(buf.validate.field).uint32 = {lte: 16}];\n"
       "  Dense kind = 3 [(buf.validate.field).enum = {defined_only: true}];\n"
       "}\n"
       "message Beta {\n"
       "  repeated int32 items = 1 [(buf.validate.field).repeated = "
       "{max_items: 8}];\n"
       "  Sparse mode = 2 [(buf.validate.field).enum = {defined_only: true}];\n"
       "}\n"))

(def ^:private options
  "The nanopb counterparts for the two size constraints above, both EXACT."
  (str "ui.Alpha.text    max_size:64\n"
       "ui.Beta.items    max_count:8\n"))

(def ^:private renderer
  "Stands in for renderer/src/renderer.c: carries the one guard token the green
   registry names, and nothing else."
  "static bool alpha_width_ok(void) { return MAX_ALPHA_WIDTH; }\n")

(def ^:private tests
  "Stands in for the wire-constraint suite: carries the one test name the green
   registry names."
  "fn alpha_width_past_the_bound_is_refused() {}\n")

(def ^:private registry
  "A disposition for every constraint the synthetic proto declares — one of each
   verdict, so a case can mutate any single one and leave the rest green."
  {"ui.Alpha.text" {"max_len" {:disposition :nanopb-size
                               :rationale "exact nanopb counterpart"}}
   "ui.Alpha.width" {"lte" {:disposition :renderer-guard
                            :guard "MAX_ALPHA_WIDTH"
                            :test "alpha_width_past_the_bound_is_refused"
                            :rationale "guarded"}}
   "ui.Alpha.kind" {"defined_only" {:disposition :renderer-guard
                                    :guard "MAX_ALPHA_WIDTH"
                                    :test "alpha_width_past_the_bound_is_refused"
                                    :range-over "Dense"
                                    :rationale "range over a dense enum"}}
   "ui.Beta.items" {"max_items" {:disposition :nanopb-size
                                 :rationale "exact nanopb counterpart"}}
   "ui.Beta.mode" {"defined_only" {:disposition :unenforced
                                   :rationale "direct-cast"
                                   :harm "renders as the default arm"}}})

(defn- refusal
  "Run the emitter over the synthetic inputs, with `overrides` merged in, and
   return the ex-data of its refusal, or nil when it did not refuse."
  [overrides]
  (try (c/manifest (merge {:proto proto :options options :registry registry
                           :renderer renderer :tests tests}
                          overrides))
       nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- assert-clause
  "Require the run to be refused by exactly `clause` — the clause under test
   fires, and every other clause this emitter can raise stays silent."
  [clause overrides]
  (let [data (refusal overrides)]
    (is (some? data)
        (str "expected a refusal for " clause ", got a clean emit"))
    (is (= clause (:clause data))
        (str "expected clause " clause ", got " (:clause data)))))

(defn- built
  "The manifest from the synthetic inputs with `overrides`, after asserting it
   was NOT refused. Returns nil when it was, so the caller skips its value
   assertions instead of letting the refusal escape: an escaped exception is an
   ERROR, and an ERROR cannot be told from a broken harness."
  [overrides]
  (let [data (refusal overrides)]
    (is (nil? data) (str "expected a clean emit, was refused: " data))
    (when (nil? data)
      (c/manifest (merge {:proto proto :options options :registry registry
                          :renderer renderer :tests tests}
                         overrides)))))

(deftest green-control-emits
  (testing "the synthetic fixture emits, so a red below is the mutation's"
    (when-some [m (built {})]
      (is (= "proto/ui/ui_ast.proto" (get m "source")))
      (is (= "scripts/proto_cleanup.awk" (get m "stripped_by")))
      (is (= 5 (count (get m "constraints"))))
      ;; The two SIZE entries are the only ones that may claim survival, and
      ;; that split is the manifest's whole subject.
      (is (true? (get-in m ["constraints" "ui.Alpha.text" "max_len"
                            "survives_strip"])))
      (is (false? (get-in m ["constraints" "ui.Alpha.width" "lte"
                             "survives_strip"])))
      (is (= 64 (get-in m ["constraints" "ui.Alpha.text" "max_len" "nanopb"])))
      (is (= "Dense" (get-in m ["constraints" "ui.Alpha.kind" "defined_only"
                                "range_over"]))))))

;; ── Parse clauses ──────────────────────────────────────────────────────────

(deftest refuses-an-unknown-constraint-keyword
  (testing "the buf.validate vocabulary is closed, so a new one forces a
            publish-or-decline decision instead of being dropped"
    (assert-clause
     :unknown-constraint
     {:proto (str/replace proto "lte: 16" "not_in: 16")})))

(deftest refuses-a-non-integer-constraint-value
  (assert-clause :bad-constraint-value
                 {:proto (str/replace proto "lte: 16" "lte: false")}))

(deftest refuses-an-empty-parse
  (testing "a proto with no annotations is what a wrong path also produces"
    (assert-clause :empty {:proto "message Alpha {\n  string text = 1;\n}\n"})))

;; ── Registry totality, both directions ─────────────────────────────────────

(deftest refuses-a-constraint-with-no-disposition
  (testing "a new constraint cannot land unruled-on"
    (assert-clause :undisposed
                   {:registry (dissoc registry "ui.Alpha.width")})))

(deftest refuses-a-disposition-naming-a-dead-constraint
  (testing "the registry ratchets: an entry cannot outlive its subject"
    (assert-clause
     :stale-disposition
     {:registry (assoc registry "ui.Gone.field"
                       {"lte" {:disposition :unenforced
                               :rationale "r" :harm "h"}})})))

(deftest refuses-an-unknown-disposition
  (assert-clause :unknown-disposition
                 {:registry (assoc-in registry ["ui.Beta.mode" "defined_only"
                                                :disposition]
                                      :probably-fine)}))

(deftest refuses-a-blank-rationale
  (assert-clause :missing-rationale
                 {:registry (assoc-in registry ["ui.Beta.mode" "defined_only"
                                                :rationale]
                                      "   ")}))

(deftest refuses-an-unenforced-entry-that-does-not-say-what-happens
  (testing "an entry that only says \"not enforced\" cannot be told from one
            nobody considered"
    (assert-clause :missing-harm
                   {:registry (update-in registry ["ui.Beta.mode"
                                                   "defined_only"]
                                         dissoc :harm)})))

(deftest refuses-an-enforced-elsewhere-entry-that-does-not-name-the-clause
  (testing "unnamed, the claim cannot be re-checked when that clause moves"
    (assert-clause
     :missing-by
     {:registry (assoc registry "ui.Beta.mode"
                       {"defined_only" {:disposition :enforced-elsewhere
                                        :rationale "r" :harm "h"}})})))

;; ── The nanopb-size join — the wire-consistency half ───────────────────────

(deftest refuses-a-size-claim-with-no-nanopb-bound
  (testing "a claim that the strip removed no enforcement, with nothing behind
            it — the shape an FT_POINTER field produces"
    (assert-clause :size-bound-unbacked
                   {:options "ui.Beta.items    max_count:8\n"})))

(deftest refuses-a-looser-nanopb-bound
  (testing "a max_size above max_len+1 admits input the wire forbids, and
            neither file says so on its own"
    (assert-clause :size-bound-mismatch
                   {:options (str/replace options "max_size:64"
                                          "max_size:128")})))

(deftest refuses-a-stricter-nanopb-bound
  (testing "and the other direction: a max_size below max_len+1 refuses input
            the wire permits, which is equally a wire bug"
    (assert-clause :size-bound-mismatch
                   {:options (str/replace options "max_size:64"
                                          "max_size:32")})))

(deftest refuses-a-size-disposition-on-a-value-constraint
  (testing "nanopb cannot carry an lte at all, so claiming it does is not a
            mismatch but a category error"
    (assert-clause
     :size-disposition-on-value-constraint
     {:registry (assoc registry "ui.Alpha.width"
                       {"lte" {:disposition :nanopb-size :rationale "r"}})})))

;; ── The renderer-guard anchors ─────────────────────────────────────────────

(deftest refuses-a-guard-token-absent-from-the-renderer
  (testing "a manifest must not go on claiming a guard that was deleted"
    (assert-clause :missing-guard {:renderer "static void nothing(void) {}\n"})))

(deftest refuses-a-test-absent-from-the-suite
  (testing "the guard's only proof of FIRING is the test; a claim naming a
            deleted one publishes an unbacked verdict"
    (assert-clause :missing-test {:tests "fn something_else() {}\n"})))

(deftest refuses-a-range-guard-over-a-sparse-enum
  (testing "a _MIN.._MAX range is an exact membership test only while the enum
            is DENSE; a hole would pass a guard that still looks correct"
    (assert-clause
     :sparse-enum
     {:registry (assoc-in registry ["ui.Alpha.kind" "defined_only" :range-over]
                          "Sparse")})))

(deftest refuses-a-range-guard-over-an-undeclared-enum
  (assert-clause :unknown-enum
                 {:registry (assoc-in registry ["ui.Alpha.kind" "defined_only"
                                                :range-over]
                                      "NoSuchEnum")}))

;; ── Density, on its own ────────────────────────────────────────────────────

(deftest density-discriminates
  (testing "the predicate the sparse-enum clause rests on, driven directly so a
            green above cannot come from it accepting everything"
    (let [enums (c/enum-values proto)]
      (is (= #{0 1 2} (get enums "Dense")))
      (is (= #{0 4} (get enums "Sparse")))
      (is (c/dense? (get enums "Dense")))
      (is (not (c/dense? (get enums "Sparse"))))
      (is (not (c/dense? #{}))
          "an empty enum is not dense — a range over it would bound nothing"))))

;; ── The committed tree ─────────────────────────────────────────────────────

(deftest covers-the-committed-proto-totally
  (testing "every buf.validate annotation in the real proto yields an entry"
    ;; CWD-relative, the same contract the rest of this seam runs under
    ;; (tools/renderer-gen is the working directory for every alias).
    (let [f (io/file "../../proto/ui/ui_ast.proto")
          _ (is (java.io.File/.isFile f)
                "the committed ui_ast.proto must be reachable from tools/renderer-gen")
          source (slurp f)
          ;; Derived HERE, independently of the emitter, so the equality below
          ;; is a real comparison rather than the parser agreeing with itself.
          annotations (count (re-seq #"\(buf\.validate\.field\)" source))
          parsed (c/parse-constraints source)]
      (is (pos? annotations) "the proto must declare something")
      (is (= annotations (count parsed))
          "one field entry per buf.validate annotation")
      ;; STRUCTURE, never a disposition and never a bound: asserting either
      ;; here would make this file a second home for what the manifest owns.
      (doseq [[field {:keys [constraints]}] parsed]
        (is (seq constraints) (str field " must declare at least one constraint"))
        (is (every? c/known-constraints (keys constraints))
            (str field " must declare only known constraints"))))))

(deftest every-range-guarded-enum-in-the-tree-is-dense
  (testing "the committed guards are range tests, so a sparse enum among them
            would be a live gap rather than a fixture question"
    (let [enums (c/enum-values (slurp (io/file "../../proto/ui/ui_ast.proto")))]
      (is (pos? (count enums)) "the proto must declare enums")
      (doseq [[enum-name values] enums]
        (is (seq values) (str "enum " enum-name " must declare members"))))))
