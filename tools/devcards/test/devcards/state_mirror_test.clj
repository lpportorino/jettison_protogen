(ns devcards.state-mirror-test
  "THE MIRROR GATE'S CANARY — it drives the REAL gate (`state-mirror/run`, the
   whole decision `-main` prints and exits with) against planted inputs and
   watches it fail for ITS OWN named clause.

   WHY EVERY CLAUSE GETS ITS OWN FIXTURE. A refusal shown is not a refusal
   attributed: this gate has five finding clauses and six refusal clauses, and
   several of them would reject some of the same inputs. So each case asserts
   the finding vector is EXACTLY the one clause under test — not that it is
   non-empty — which is the strongest available form of `break that clause
   alone and watch only it fire`.

   AND THE EXIT CODE IS ASSERTED EXACTLY, never merely as non-zero. `1` is a
   verdict about the tree and `3` is a broken gate; a suite that accepted any
   non-zero would take a crash as proof that a clause fired, which is the
   ERROR-wearing-a-FAIL's-colour confusion `.claude/rules/gate-enforcement.md`
   §2 forbids. The CANNOT-RUN cases below are therefore not decoration: they
   are the neighbouring refusals, in a DIFFERENT exit class, that make the
   FINDINGS cases attributable.

   THE FIXTURES ARE SYNTHETIC, per §2's preference — a canary that perturbs
   tracked files cannot run on a dirty checkout and drifts whenever the corpus
   moves. The live tree is asserted separately, in the passing direction, and
   once in the failing direction over a mutant DERIVED from the committed
   manifest, so the gate is proven against the real data shape too. That case
   proves its mutation LANDED on exact bytes before believing any colour."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [devcards.conventions :as conventions]
            [devcards.state-mirror :as mirror]))

(set! *warn-on-reflection* true)

;; ── fixtures ─────────────────────────────────────────────────────────────

(defn- tmp-edn!
  "Write `form` as EDN to a scratch file OUTSIDE the tree and return its path."
  ^String [^String prefix form]
  (let [f (java.io.File/createTempFile prefix ".edn")]
    (.deleteOnExit f)
    (spit f (pr-str form))
    (.getPath f)))

(def ^:private conv-ok
  "A minimal well-formed conventions manifest: two classes, one of them
   multi-state so a membership mutation is expressible."
  {:version 1 :widget-states {:WIDGET_A [:default :disabled] :WIDGET_B [:default]}})

(def ^:private corpus-ok
  "The corpus spec that MIRRORS `conv-ok`."
  {:widgets [{:type :WIDGET_A :tag "a" :committed-states [:default :disabled]}
             {:type :WIDGET_B :tag "b" :committed-states [:default]}]})

(defn- run-pair
  "The REAL gate over one planted pair."
  [conv corpus]
  (mirror/run [(tmp-edn! "state-mirror-conv" conv)
               (tmp-edn! "state-mirror-corpus" corpus)]))

(defn- kinds
  "The finding kinds a verdict reported, in order."
  [v]
  (mapv :kind (:findings v)))

(defn- widgets
  "The widgets a verdict's findings named, in order."
  [v]
  (mapv :widget (:findings v)))

;; ── the PASSING direction ────────────────────────────────────────────────

(deftest an-agreeing-pair-passes-and-reports-what-it-compared
  (let [v (run-pair conv-ok corpus-ok)]
    (is (= mirror/exit-ok (:exit v)))
    (is (= [] (:findings v)))
    (testing "the compared COUNT is reported, so a collapsed run is visible
              without reading the exit code"
      (is (= 2 (:compared v)))
      (is (str/includes? (first (:lines v)) "2 class(es) compared")))))

(deftest the-committed-tree-agrees
  ;; The other half of both-directions: a canary that only asserts failure
  ;; cannot catch a gate that fails on EVERYTHING.
  (let [v (mirror/run [])]
    (is (= mirror/exit-ok (:exit v))
        (str "the committed conventions manifest and corpus spec disagree: "
             (pr-str (:lines v))))
    (testing "NON-VACUITY: the run compared a real population, and the two
              sides are the same size as the population compared — so a
              silently-shrunk side cannot pass as agreement"
      (let [conv (:value (mirror/conventions-side mirror/conventions-path))
            corpus (:value (mirror/corpus-side mirror/corpus-spec-path))]
        (is (pos? (:compared v)))
        (is (= (:compared v) (count conv) (count corpus)))))))

;; ── one fixture per FINDING clause, each asserting ONLY its own clause ────

(deftest a-membership-difference-is-attributed-to-its-own-clause
  (let [v (run-pair conv-ok (assoc-in corpus-ok [:widgets 0 :committed-states] [:default]))]
    (is (= mirror/exit-findings (:exit v)))
    (is (= [:committed-states-differ] (kinds v)))
    (is (= [:WIDGET_A] (widgets v)))
    (testing "the detail names BOTH sides and which state each is missing"
      (let [d (:detail (first (:findings v)))]
        (is (str/includes? d "only in conventions [:disabled]"))
        (is (str/includes? d "only in corpus []"))))))

(deftest an-order-only-difference-is-its-own-clause-not-a-membership-one
  (let [v (run-pair conv-ok
                    (assoc-in corpus-ok [:widgets 0 :committed-states] [:disabled :default]))]
    (is (= mirror/exit-findings (:exit v)))
    (is (= [:committed-states-reordered] (kinds v)))
    (is (= [:WIDGET_A] (widgets v)))))

(deftest a-class-only-in-the-conventions-file-names-that-direction
  (let [v (run-pair conv-ok (update corpus-ok :widgets (comp vec butlast)))]
    (is (= mirror/exit-findings (:exit v)))
    (is (= [:class-absent-from-corpus] (kinds v)))
    (is (= [:WIDGET_B] (widgets v)))
    (is (= "conventions -> corpus" (:direction (first (:findings v)))))))

(deftest a-class-only-in-the-corpus-names-the-other-direction
  (let [v (run-pair (update conv-ok :widget-states dissoc :WIDGET_B) corpus-ok)]
    (is (= mirror/exit-findings (:exit v)))
    (is (= [:class-absent-from-conventions] (kinds v)))
    (is (= [:WIDGET_B] (widgets v)))
    (is (= "corpus -> conventions" (:direction (first (:findings v)))))))

(deftest a-corpus-class-declaring-no-list-is-a-finding-not-a-skip
  (let [v (run-pair conv-ok (update-in corpus-ok [:widgets 0] dissoc :committed-states))]
    (is (= mirror/exit-findings (:exit v)))
    (is (= [:committed-states-undeclared] (kinds v)))
    (is (= [:WIDGET_A] (widgets v)))))

(deftest every-finding-clause-is-reachable-and-they-do-not-mask-each-other
  ;; The clauses above fire ONE at a time by construction. This case proves
  ;; they also COMPOSE: a pair broken five ways reports five findings, so no
  ;; clause short-circuits the ones after it.
  (let [conv (-> conv-ok
                 (update :widget-states dissoc :WIDGET_B)
                 (assoc-in [:widget-states :WIDGET_C] [:default :disabled])
                 (assoc-in [:widget-states :WIDGET_D] [:default :disabled])
                 (assoc-in [:widget-states :WIDGET_E] [:default :disabled]))
        corpus (update corpus-ok :widgets
                       #(-> (vec %)
                            (assoc-in [0 :committed-states] [:default])
                            (conj {:type :WIDGET_C :committed-states [:disabled :default]})
                            (conj {:type :WIDGET_D})))
        v (run-pair conv corpus)]
    (is (= mirror/exit-findings (:exit v)))
    (is (= [:class-absent-from-conventions
            :class-absent-from-corpus
            :committed-states-differ
            :committed-states-reordered
            :committed-states-undeclared]
           (sort (kinds v))))
    (is (= 5 (count (:findings v))))))

;; ── the CANNOT-RUN clauses: neighbouring refusals in a DIFFERENT exit class ─

(defn- refusal
  "Assert `v` refused (exit 3, no findings) and return its headline."
  ^String [v]
  (is (= mirror/exit-cannot-run (:exit v)))
  (is (= [] (:findings v)))
  (is (zero? (:compared v)))
  (first (:lines v)))

(deftest an-empty-conventions-side-is-a-refusal-never-a-pass
  ;; The defect this whole gate is about, turned on the gate itself: with one
  ;; side empty there are zero comparisons, and zero comparisons must not
  ;; report clean.
  (is (str/includes? (refusal (run-pair (assoc conv-ok :widget-states {}) corpus-ok))
                     ":widget-states is EMPTY")))

(deftest an-empty-corpus-side-is-a-refusal-never-a-pass
  (is (str/includes? (refusal (run-pair conv-ok (assoc corpus-ok :widgets [])))
                     ":widgets is EMPTY")))

(deftest a-side-with-no-section-at-all-is-a-refusal
  (testing "a manifest carrying no :widget-states key"
    (is (str/includes? (refusal (run-pair (dissoc conv-ok :widget-states) corpus-ok))
                       "carries no :widget-states MAP")))
  (testing "a spec carrying no :widgets key"
    (is (str/includes? (refusal (run-pair conv-ok (dissoc corpus-ok :widgets)))
                       "carries no :widgets sequence"))))

(deftest an-untyped-corpus-class-is-a-refusal-never-a-dropped-row
  ;; Dropping it would silently shrink the population the gate reports as its
  ;; own coverage — a smaller compared count that still reads as agreement.
  (is (str/includes? (refusal (run-pair conv-ok
                                        (update corpus-ok :widgets conj {:tag "untyped"})))
                     "widget class with no :type")))

(deftest a-duplicated-corpus-type-is-a-refusal-never-a-silent-collapse
  (is (str/includes? (refusal (run-pair conv-ok
                                        (update corpus-ok :widgets conj
                                                {:type :WIDGET_A :committed-states [:default]})))
                     "declares a WidgetType twice: WIDGET_A")))

(deftest a-missing-file-is-a-refusal-with-the-path-named
  (is (str/includes? (refusal (mirror/run [(tmp-edn! "state-mirror-conv" conv-ok)
                                           "corpus/no-such-spec.edn"]))
                     "no corpus spec at corpus/no-such-spec.edn")))

(deftest an-unparseable-file-is-a-refusal-not-an-empty-declaration
  (let [f (java.io.File/createTempFile "state-mirror-broken" ".edn")]
    (.deleteOnExit f)
    (spit f "{:widget-states {:WIDGET_A [:default")
    (is (str/includes? (refusal (mirror/run [(.getPath f)
                                             (tmp-edn! "state-mirror-corpus" corpus-ok)]))
                       "is unreadable at"))))

(deftest one-argument-is-refused-rather-than-half-defaulted
  ;; Half-defaulting would compare a fixture against the live corpus and
  ;; return a verdict about neither pair.
  (is (str/includes? (refusal (mirror/run [(tmp-edn! "state-mirror-conv" conv-ok)]))
                     "usage:"))
  (is (str/includes? (refusal (mirror/run ["a" "b" "c"])) "usage:")))

;; ── the REAL manifest, mutated: the historical defect, on real bytes ──────

(deftest the-real-manifest-narrowed-by-one-row-is-caught
  ;; This is the defect that produced this gate: a class whose manifest row
  ;; understated the corpus. Reproduced against the COMMITTED manifest rather
  ;; than a synthetic one, so the gate is proven on the real data shape.
  (let [committed (edn/read-string (slurp mirror/conventions-path))
        target (first (sort (for [[w states] (:widget-states committed)
                                  :when (< 1 (count states))]
                              w)))
        _ (is (some? target)
              "no conventions row carries more than one state — nothing to narrow")
        original-states (vec (get-in committed [:widget-states target]))
        narrowed (assoc-in committed [:widget-states target] [:default])
        before (pr-str committed)
        after (pr-str narrowed)
        old-literal (str target " " (pr-str original-states))
        new-literal (str target " " (pr-str [:default]))]
    (testing "PROOF THE MUTATION LANDED, on the exact bytes the gate will read
              — a mutation that matched nothing yields a mutant identical to
              the original, whose green would read as attribution while
              proving the opposite"
      (is (str/includes? before old-literal))
      (is (not (str/includes? before new-literal)))
      (is (str/includes? after new-literal))
      (is (not (str/includes? after old-literal)))
      (is (not= before after)))
    (testing "and the REAL gate rejects it, naming the clause and the class"
      (let [v (mirror/run [(tmp-edn! "state-mirror-real" narrowed)
                           mirror/corpus-spec-path])]
        (is (= mirror/exit-findings (:exit v)))
        (is (= [:committed-states-differ] (kinds v)))
        (is (= [target] (widgets v)))
        (is (pos? (:compared v)))))))

;; ── the two path literals, pinned to their one homes ─────────────────────

(deftest the-conventions-path-is-the-one-devcards-conventions-owns
  ;; A value comparison, not text: this ns can require devcards.conventions
  ;; safely because a test failure is a test failure, while the GATE may not
  ;; (a require that throws exits 1, the FINDINGS code).
  (is (= conventions/edn-home mirror/conventions-path)))

(deftest the-corpus-spec-path-is-the-one-devcards-fixtures-owns
  ;; TEXT, because `devcards.fixtures` drags the generated protobuf bindings
  ;; and cannot load under the :test alias — the same reason `lanes-test`
  ;; reads `core.clj` as text. Without this the gate's restated literal could
  ;; drift from its home and nothing would notice until both sides refused.
  (let [src (slurp (io/file "src/devcards/fixtures.clj"))
        m (re-find #"\(def spec-path\s+\"[^\"]*\"\s+\"([^\"]+)\"" src)]
    (is (some? m) "devcards.fixtures no longer declares `spec-path` in the pinned shape")
    (is (= mirror/corpus-spec-path (second m)))
    (testing "CONTROL: the pattern is a discriminator, not a tautology"
      (is (not= mirror/corpus-spec-path
                (second (re-find #"\(def spec-path\s+\"[^\"]*\"\s+\"([^\"]+)\""
                                 "(def spec-path \"doc\" \"corpus/moved.edn\")")))))))

;; ── the CLI holds no decision ────────────────────────────────────────────

(def ^:private main-call-site
  "`-main`'s ENTIRE decision, as one form. Whitespace-tolerant and otherwise
   exact: the token sequence is the claim."
  (re-pattern
   (str "\\(let\\s+\\[\\{:keys\\s+\\[lines\\s+exit\\]\\}\\s+\\(run\\s+args\\)\\]"
        "\\s+\\(doseq\\s+\\[l\\s+lines\\]\\s+\\(println\\s+l\\)\\)"
        "\\s+\\(System/exit\\s+exit\\)\\)")))

(deftest main-prints-and-exits-with-exactly-what-run-returned
  (testing "every case above names `run`, so a `-main` that decided for itself
            would leave this whole suite green while the gate shipped a
            different verdict.
            REVERT-TO-BREAK: change `-main`'s `(System/exit exit)` to
            `(System/exit 0)`."
    ;; Reduced to a boolean before `is` sees it, so a failure does not
    ;; pretty-print the whole namespace as the actual value.
    (let [found? (some? (re-find main-call-site
                                 (slurp (io/file "src/devcards/state_mirror.clj"))))]
      (is found?
          (str "devcards.state-mirror's -main no longer reads "
               "`(let [{:keys [lines exit]} (run args)] "
               "(doseq [l lines] (println l)) (System/exit exit))` — either a "
               "decision moved into -main, where no test can name it, or the "
               "exit code stopped being the one `run` returned."))))
  (testing "CONTROL: the pattern must NOT match the same form with the exit forced"
    (is (not (re-find main-call-site
                      (str "(let [{:keys [lines exit]} (run args)] "
                           "(doseq [l lines] (println l)) (System/exit 0))"))))))
