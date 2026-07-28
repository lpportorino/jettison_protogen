(ns devcards.docindex-test
  "THE POINT OF THE SPLIT, proved by mutation: a new unit class reaches the
   index by BEING IN THE SET, and every way of reaching it badly is refused by
   name.

   The emitter half (`devcards.docs`) cannot be loaded here — it pulls
   GraalWasm and the generated bindings, neither of which is on the `:test`
   alias's path. That is not a gap in coverage, it is the seam working: every
   layout decision, every ordering rule and every refusal lives in the
   render-free half, so all of it is reachable from a plain unit test with no
   wasm, no corpus and no descriptor.

   EACH REFUSAL BELOW IS A CANARY FOR ITS OWN CLAUSE. Where a fixture could
   trip a NEIGHBOURING clause instead — the classic false gate that goes red
   for the wrong reason — the fixture is built to keep the neighbour satisfied,
   and the test says so."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [devcards.docindex :as docindex]))

;; ── A minimal, synthetic manifest set ───────────────────────────────────
;; Deliberately not a copy of the emitter's real one: these tests are about
;; the vocabulary, not about any particular corpus.

(def ^:private prov
  {:provenance/regen-cmd "`make docs`"
   :provenance/sources "corpus/spec.edn"})

(defn- member
  "A page-bearing member of the closed set `S`, at `order` == its set index."
  [id order]
  (merge prov
         {:manifest/version 1
          :unit/id id
          :unit/order order
          :unit/page? true
          :unit/index? false
          :page/title id
          :page/lede "lede"
          :page/sections [{:section/kind :prose :prose/text "body"}]
          :index/group "members"
          :index/heading nil
          :index/blocks [{:section/kind :table
                          :section/merge :member-rows
                          :table/headers ["id" "what it proves"]
                          :table/rows [[id "it is in the set"]]}]
          :provenance/closed-set {:set/id "S" :set/size 2 :set/index order}}))

(def ^:private legend
  "A unit with index blocks and NO page — the class the reachability check
   used to skip entirely."
  (merge prov
         {:manifest/version 1
          :unit/id "legend"
          :unit/order 900
          :unit/page? false
          :unit/index? false
          :index/group "legend"
          :index/heading "Legend"
          :index/blocks [{:section/kind :table
                          :table/headers ["token" "bit"]
                          :table/rows [["`a`" 1]]}]}))

(def ^:private index-unit
  (merge prov
         {:manifest/version 1
          :unit/id "index"
          :unit/order -1
          :unit/page? true
          :unit/index? true
          :page/title "The index"
          :page/lede "everything below is folded out of the set."}))

(def ^:private baseline
  [(member "alpha" 0) (member "beta" 1) legend index-unit])

(def ^:private newcomer
  "A brand-new unit class: its own group, heading, table and page — and NO
   closed-set stamp, because a unit class is open."
  (merge prov
         {:manifest/version 1
          :unit/id "newcomer"
          :unit/order 100
          :unit/page? true
          :unit/index? false
          :page/title "Newcomer"
          :page/lede "a unit class nobody had thought of when the consumer was written."
          :page/sections [{:section/kind :prose
                           :section/heading "The cards"
                           :prose/text "one card, for the proof."}]
          :page/cross-links [{:link/text "index" :link/href "../README.md"}]
          :index/group "newcomer"
          :index/heading "Newcomer"
          :index/blocks [{:section/kind :table
                          :table/headers ["card" "what it proves"]
                          :table/rows [["`newcomer/basic`" "being in the set is the only registration."]]}]}))

;; ── The mutation proof ──────────────────────────────────────────────────
(deftest a-new-unit-class-appears-by-being-in-the-set
  (let [before (docindex/index-md baseline)
        after (docindex/index-md (conj baseline newcomer))]
    (testing "the new class gets its own index section, unasked"
      (is (not (str/includes? before "## Newcomer")))
      (is (str/includes? after "## Newcomer\n\n| card | what it proves |"))
      (is (str/includes? after "| `newcomer/basic` | being in the set is the only registration. |")))
    (testing "it lands at its declared :unit/order — after the closed set (0,1)
              and before the legend (900), MID-SEQUENCE rather than appended"
      (let [at (fn [^String h] (str/index-of after h))]
        (is (< (at "| alpha | it is in the set |")
               (at "## Newcomer")
               (at "## Legend")))))
    (testing "it also gets a page, at the path its own id names"
      (is (some #(= "root/newcomer/README.md" (first %))
                (docindex/pages "root" (conj baseline newcomer)))))
    (testing "the page count follows the set, not a literal"
      (is (= 3 (docindex/page-count baseline)))
      (is (= 4 (docindex/page-count (conj baseline newcomer)))))
    (testing "removing it returns the index BYTE FOR BYTE to baseline, with no
              line of the consumer edited between the two runs"
      (is (= before (docindex/index-md (remove #(= "newcomer" (:unit/id %))
                                               (conj baseline newcomer))))))))

(deftest the-closed-set-members-merge-into-one-table
  (testing "merge is DECLARED: two members sharing :section/merge fold into a
            single table rather than rendering two"
    (let [md (docindex/index-md baseline)]
      (is (= 1 (count (re-seq #"\| id \| what it proves \|" md))))
      (is (str/includes? md "| alpha | it is in the set |\n| beta | it is in the set |")))))

;; ── The consumer names no unit class ────────────────────────────────────
(defn- read-forms
  "Every top-level form of a source file, read (never evaluated)."
  [^String path]
  (binding [*read-eval* false]
    (with-open [r (java.io.PushbackReader. (io/reader path))]
      (vec (take-while #(not= ::eof %)
                       (repeatedly #(read {:eof ::eof :read-cond :allow} r)))))))

(defn- strip-docstrings
  "Drop the docstring from every ns/def/defn form, recursively. A docstring MAY
   discuss the classes — explaining the design is its job. Executable code may
   not name one, and a grep over raw source text cannot tell the two apart, so
   it would pass by luck."
  [form]
  (letfn [(doc-carrier? [f]
            (and (seq? f) (symbol? (first f))
                 (contains? '#{ns def defn defn- defmacro} (symbol (name (first f))))))]
    (cond
      (doc-carrier? form)
      (let [[op nm & more] form
            more (if (and (string? (first more)) (next more)) (rest more) more)]
        (cons op (cons nm (map strip-docstrings more))))

      (seq? form) (map strip-docstrings form)
      (vector? form) (mapv strip-docstrings form)
      (set? form) (into #{} (map strip-docstrings) form)
      (map? form) (into {} (map (fn [[k v]] [(strip-docstrings k) (strip-docstrings v)])) form)
      :else form)))

(deftest the-consumer-names-no-unit-class
  (testing "no STRING LITERAL in the render-free half's executable code names a
            unit class — read as forms with docstrings stripped, so the
            assertion cannot pass on prose. This is what makes `a new class
            appears by being in the set` a property rather than a coincidence:
            there is no per-class branch left to forget to extend."
    (let [src "src/devcards/docindex.clj"
          literals (->> (read-forms src)
                        (map strip-docstrings)
                        (mapcat #(filter string? (tree-seq coll? seq %))))]
      (is (seq literals) (str src " yielded no string literals — the test is vacuous"))
      (doseq [s literals
              banned ["widget" "sink" "lego" "legend" "newcomer"]]
        (is (not (str/includes? (str/lower-case s) banned))
            (str src " names a unit class in a string literal: " (pr-str s)))))))

;; ── Per-manifest refusals ───────────────────────────────────────────────
(def ^:private minimal (member "alpha" 0))

(deftest refuses-an-unknown-block-kind
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"kind outside the closed vocabulary"
       (docindex/page-md (assoc-in minimal [:page/sections 0 :section/kind] :callout)))))

(deftest refuses-a-missing-required-key
  (testing "the version"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"version this consumer does not implement"
                          (docindex/page-md (assoc minimal :manifest/version 2)))))
  (testing "the ordering key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no integer :unit/order"
                          (docindex/page-md (dissoc minimal :unit/order)))))
  (testing "the provenance the banner is built from"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"no provenance for its DO-NOT-EDIT banner"
                          (docindex/page-md (dissoc minimal :provenance/sources)))))
  (testing "a body the block kind needs"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"prose block carries no text"
                          (docindex/page-md (assoc-in minimal [:page/sections 0]
                                                      {:section/kind :prose}))))))

(deftest refuses-a-page-declaration-that-disagrees-with-the-body
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"authors no :page/sections"
                        (docindex/validate-manifest! (dissoc minimal :page/sections))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"declares no page but authors page content"
                        (docindex/validate-manifest! (assoc minimal :unit/page? false)))))

(deftest refuses-a-grid-row-missing-an-image-for-a-declared-render-set
  (testing "the grid would emit an empty image target and the page would still
            read as complete"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"no image for a declared render set"
         (docindex/page-md
          (assoc minimal :page/sections
                 [{:section/kind :grid
                   :grid/caption-header "state"
                   :grid/render-sets [{:title "dark" :file-suffix "dark"}
                                      {:title "light" :file-suffix "light"}]
                   :grid/rows [{:label "default" :imgs {"dark" "u-default-dark.jpg"}}]}]))))))

;; ── Set-level refusals ──────────────────────────────────────────────────
(deftest refuses-a-unit-that-reaches-the-index-through-no-group
  (testing "THE PAGE-LESS BLIND SPOT. Reachability used to be checked only on
            page-BEARING units, so a page-less unit could declare no group,
            contribute nothing anywhere, and pass every check in silence — the
            exact invisibility this architecture exists to delete.
            CANARY SCOPE: the set is otherwise whole, so nothing else can fire.
            REVERT-TO-BREAK: restore the `(:unit/page? m)` guard on the
            reachability check."
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"reaches the index through no group"
         (docindex/index-md (conj [(member "alpha" 0) (member "beta" 1) index-unit]
                                  (dissoc legend :index/group))))))
  (testing "and the page-bearing case it always covered still fires"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"reaches the index through no group"
         (docindex/index-md (conj baseline (dissoc newcomer :index/group)))))))

(deftest refuses-a-unit-that-joins-a-group-and-contributes-nothing
  (testing "declaring a group is NOT reaching the index. A unit joining a
            POPULATED group with empty :index/blocks renders nothing, and the
            group still renders because its other members carry blocks — so
            nothing links the joiner's page and nothing lists it.
            CANARY SCOPE: the joiner sits inside the existing `members` run
            (order 2, contiguous) and that group keeps two block-bearing
            members, so neither the contiguity clause nor `index group
            contributes no blocks` can be what fires."
    (let [joiner (-> (member "gamma" 2)
                     (dissoc :provenance/closed-set)
                     (assoc :index/blocks []))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"contributes no index blocks"
           (docindex/index-md (conj baseline joiner))))))
  (testing "CONTROL: the same group with that unit REMOVED renders fine, so the
            refusal is about the joiner and not about the group"
    (is (string? (docindex/index-md baseline)))))

(deftest refuses-a-closed-set-ordered-against-its-own-indices
  (testing "POSITION, not just membership. `:unit/order` is a free global
            integer and `:set/index` is a separate claim; nothing tied them
            together, so two members could swap their order and the rendered
            rows would silently swap while every other check passed.
            CANARY SCOPE: sizes still agree, the count still matches, and the
            indices are still exactly (range 2) — the three older clauses are
            all satisfied, so only the ordering clause can fire.
            REVERT-TO-BREAK: delete the `by-order` comparison."
    (let [swapped [(assoc (member "alpha" 0) :unit/order 1)
                   (assoc (member "beta" 1) :unit/order 0)
                   legend index-unit]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"ordered against their own indices"
           (docindex/index-md swapped)))))
  (testing "the older closed-set clauses still fire on their own cases"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed set is not closed"
                          (docindex/index-md [(member "alpha" 0) legend index-unit])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"indices are not exactly \(range size\)"
         (docindex/index-md
          [(member "alpha" 0)
           (assoc-in (member "beta" 1) [:provenance/closed-set :set/index] 7)
           legend index-unit])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disagree on its size"
                          (docindex/index-md
                           [(member "alpha" 0)
                            (assoc-in (member "beta" 1) [:provenance/closed-set :set/size] 3)
                            legend index-unit])))))

(deftest refuses-two-units-claiming-one-identity-or-one-position
  (testing "one id names one output directory, so the second page write would
            silently win"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"two units claim one :unit/id"
                          (docindex/index-md (conj baseline (assoc newcomer :unit/id "alpha"))))))
  (testing "an arbitrary tiebreak is a layout decision made by accident"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"two units claim one :unit/order"
                          (docindex/index-md (conj baseline (assoc newcomer :unit/order 0)))))))

(deftest refuses-a-group-that-is-not-one-contiguous-run
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"not contiguous in :unit/order"
       (docindex/index-md (conj baseline (assoc newcomer
                                                :index/group "members"
                                                :index/heading nil
                                                :unit/order 950))))))

(deftest refuses-a-group-whose-members-disagree-about-its-heading
  (testing "an absent heading is an oversight and an explicit nil is a claim;
            the two must never be confused"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"without declaring its :index/heading"
         (docindex/index-md [(member "alpha" 0) (dissoc (member "beta" 1) :index/heading)
                             legend index-unit]))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"disagree about the group heading"
       (docindex/index-md [(member "alpha" 0) (assoc (member "beta" 1) :index/heading "Other")
                           legend index-unit]))))

(deftest refuses-exactly-one-index-unit-neither-more-nor-fewer
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one :unit/index\? unit"
                        (docindex/index-md [(member "alpha" 0) (member "beta" 1) legend])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one :unit/index\? unit"
                        (docindex/index-md (conj baseline
                                                 (assoc index-unit :unit/id "second-index"
                                                        :unit/order -2))))))

(deftest refuses-a-merge-that-cannot-be-a-merge
  (testing "a merge key spanning two runs would reorder the page"
    (let [m3 (fn [id order merge?]
               (-> (member id order)
                   (assoc-in [:provenance/closed-set :set/size] 3)
                   (assoc-in [:index/blocks 0 :section/merge]
                             (when merge? :member-rows))))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"merge run is not contiguous"
           (docindex/index-md [(m3 "alpha" 0 true) (m3 "beta" 1 false) (m3 "gamma" 2 true)
                               legend index-unit])))))
  (testing "two tables sharing a merge key but not their headers are two tables"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"merged tables disagree about their headers"
         (docindex/index-md
          [(member "alpha" 0)
           (assoc-in (member "beta" 1) [:index/blocks 0 :table/headers] ["id" "other"])
           legend index-unit]))))
  (testing "a kind with no defined fold refuses outright rather than dropping rows"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"block kind refuses to merge"
         (docindex/index-md
          [(assoc-in (member "alpha" 0) [:index/blocks 0]
                     {:section/kind :prose :section/merge :member-rows :prose/text "a"})
           (assoc-in (member "beta" 1) [:index/blocks 0]
                     {:section/kind :prose :section/merge :member-rows :prose/text "b"})
           legend index-unit])))))

(deftest refuses-rendering-the-index-page-from-its-own-manifest
  (testing "its body does not exist until the whole set is folded, so this
            would silently produce a page with an empty body"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"rendered from the whole manifest set"
                          (docindex/page-md index-unit)))))

;; ── Emitted-bytes link integrity ────────────────────────────────────────
(deftest refuses-a-page-that-points-at-an-artifact-nobody-wrote
  (testing "THE THIRD CASE THE DISK AUDIT CANNOT SEE. Claimed-vs-disk catches a
            write that did not land and a tracked file nothing claims; a page
            referencing an artifact that was never written is neither — the
            page renders, the link is dead, and every lane is green."
    (let [pairs [["root/u/README.md" "text ![cap](./u-1-dark.jpg) more"]]]
      (is (nil? (docindex/validate-image-refs! pairs ["root/u/u-1-dark.jpg"])))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"artifact the emitter did not write"
           (docindex/validate-image-refs! pairs ["root/u/u-2-dark.jpg"])))))
  (testing "and it resolves against each page's OWN directory, so an index
            reference one level up is checked at the path it really names"
    (is (nil? (docindex/validate-image-refs!
               [["root/README.md" "![p](./u/u-1-dark.jpg)"]]
               ["root/u/u-1-dark.jpg"])))))
