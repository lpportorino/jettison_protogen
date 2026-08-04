(ns uigen.wire-bounds-test
  "Drives every refusal `uigen.wire-bounds` can make, and the one thing it must
  NOT refuse.

  WHY BOTH DIRECTIONS AND WHY BY CLAUSE. This namespace exists so a re-typed
  wire-bound literal can be replaced by a read, and a reader that answered every
  question with a throw would be exactly as useless as one that answered every
  question with a default — so the passing direction is asserted too. Each
  refusal is matched on its own `:clause` rather than on \"something threw\":
  a red that cannot be attributed proves nothing about the clause it was meant
  to drive, and five of these six clauses sit within one `let` of each other.

  HERMETIC EXCEPT FOR ONE ASSERTION. Every clause is driven over a fixture map,
  because `bounds-of` and `bound-in` take their input as an argument. The single
  live-classpath assertion is the one that would otherwise be untested: that the
  committed manifest really does publish the bound the generator now reads from
  it, so a green here is not green over a fixture nobody ships."
  (:require
   [clojure.test :refer [deftest is testing]]
   [uigen.wire-bounds :as wb]))

(def ^:private fixture
  "A minimal well-formed parsed manifest — keyword keys, as jsonista produces."
  {:source "proto/ui/ui_ast.options"
   :bounds {:ui.Thing.blob {:max_size 64}
            :ui.Thing.items {:max_count 4}
            :ui.Thing.kind {:type "FT_POINTER"}
            :ui.Thing.wrong {:max_size 0}}})

(defn- clause
  "The `:clause` of the refusal `f` makes, or nil when it does not refuse."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:clause (ex-data e)))))

(deftest bounds-of-accepts-a-well-formed-manifest
  (testing "the passing direction — a reader that refused everything is useless"
    (is (= (:bounds fixture) (wb/bounds-of fixture)))))

(deftest bounds-of-refuses-each-unusable-manifest-by-clause
  (testing "an absent manifest is never read as 'no bounds apply'"
    (is (= :manifest-absent (clause #(wb/bounds-of nil)))))
  (testing "a manifest with no bounds map names its own shape defect"
    (is (= :manifest-shape (clause #(wb/bounds-of {:source "x"})))))
  (testing "an empty bounds map is a wrong path, not an unbounded wire"
    (is (= :manifest-empty (clause #(wb/bounds-of {:source "x" :bounds {}}))))))

(deftest bound-in-reads-a-published-bound
  (testing "both option spellings resolve to their published integer"
    (is (= 64 (wb/bound-in (:bounds fixture) "ui.Thing.blob" "max_size")))
    (is (= 4 (wb/bound-in (:bounds fixture) "ui.Thing.items" "max_count")))))

(deftest bound-in-refuses-each-missing-or-unusable-bound-by-clause
  (let [b (:bounds fixture)]
    (testing "a field nobody published is named, never defaulted"
      (is (= :unknown-field (clause #(wb/bound-in b "ui.Thing.absent" "max_size")))))
    (testing "an option the field does not declare is its own defect"
      (is (= :unknown-option (clause #(wb/bound-in b "ui.Thing.blob" "max_count"))))
      (is (= :unknown-option (clause #(wb/bound-in b "ui.Thing.kind" "max_size")))))
    (testing "a non-positive bound is refused rather than propagated as a cap"
      (is (= :bad-option-value (clause #(wb/bound-in b "ui.Thing.wrong" "max_size")))))))

(deftest the-committed-manifest-publishes-the-bounds-the-generators-read
  (testing "the fields uigen.cmd-spec reads are really in the shipped manifest"
    ;; Asserted as a POSITIVE INTEGER, never as today's number: pinning 128 here
    ;; would make this test agree with the manifest by construction and turn a
    ;; deliberate bound change into a test failure that says nothing.
    (is (pos-int? (wb/max-size "ui.CmdSpec.root_template")))
    (is (pos-int? (wb/max-count "ui.CmdSpec.patches")))))
