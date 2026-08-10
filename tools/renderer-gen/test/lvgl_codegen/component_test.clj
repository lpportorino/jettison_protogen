(ns lvgl-codegen.component-test
  "Canary for the POST-EXPANSION RESIDUE FIREWALL in `lvgl-codegen.component`.

  WHAT THE FIREWALL IS FOR. A component template carries `$param` references that
  `resolve-component-usage` substitutes from the usage's `:props`. Anything left
  unsubstituted after expansion means a substitution silently missed, and the
  literal `\"$icon\"` then ships into a `.pb` as a string — a texture path that
  resolves nowhere, a class token that parses as garbage, with no error at either
  end. `scan-residue!` exists to make that a hard throw.

  THE BUG IT HAD: it listed exactly two keys, `:text` and `:class` — the same two
  keys `resolve-tree` substitutes. So the firewall was exactly as wide as the thing
  it guards, and every key substitution SKIPS was also a key the firewall could not
  see. `:style` is one of those: it sits in `substitute-widget-props`'
  `structural-keys` and `resolve-tree`'s `cond->` chain has no clause for it.

  WHY IT WAS INVISIBLE. The `:$param` KEYWORD spelling was always caught, by the
  postwalk clause that rejects any keyword starting with `$` ANYWHERE in the tree.
  Only the STRING spelling leaked — and only outside `:text`/`:class`. Nothing in
  the shipped `edn/components.edn` uses that combination — derive the split from
  the file rather than a count here (a tally of its `$`-strings stood in this
  docstring and went stale twice as components were added); what is load-bearing
  is only that no `$`-string sits in an UNSUBSTITUTED key, so no authored screen
  could reach the leak and no pixel oracle, golden or parity lane could ever
  have found it."
  (:require
   [clojure.test :refer [deftest is testing]]
   [lvgl-codegen.component :as component]))

(set! *warn-on-reflection* true)

(def ^:private scan-residue!
  "The firewall under test. Private, so reached through its var — this is a unit
  contract, and driving it through `resolve-components` would need a whole
  component registry to assert one throw."
  #'component/scan-residue!)

(deftest residue-in-style-is-refused
  ;; THE REGRESSION. `:style` is never substituted, so a `$` string there is always
  ;; unresolved by construction — the firewall is the only thing that can say so.
  ;; THE REPORTED KEY IS THE IMMEDIATE ONE, not the outer `:style`. `postwalk` is
  ;; bottom-up, so the inner prop map `{:bg-image-src "$icon"}` is visited first and
  ;; the throw names `:bg-image-src`. That is the MORE useful diagnostic — it points
  ;; at the exact style property rather than at the container — and it is asserted
  ;; here so a later refactor to a top-down walk cannot silently coarsen it.
  (testing "a $param string under a nested :style prop throws, naming that prop"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"left unresolved \$icon in :bg-image-src"
                          (scan-residue! {:tag :lv_image :style {:bg-image-src "$icon"}}
                                         "card"))))
  (testing "and the throw names the component, the param and the exact key"
    (let [d (try (scan-residue! {:tag :lv_image :style {:bg-image-src "$icon"}} "card")
                 (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= "card" (:component d)))
      (is (= :icon (:param d)))
      (is (= :bg-image-src (:key d)))
      (is (= "$icon" (:value d))))))

(deftest residue-in-any-other-key-is-refused
  ;; TOTALITY over keys, which is the actual repair. Each of these was previously
  ;; unreachable by the firewall for the same reason `:style` was.
  (testing "keys that are neither :text nor :class"
    (doseq [k [:style :placeholder :src :options :bind-fmt]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"left unresolved \$p"
                            (scan-residue! {:tag :lv_label k "$p"} "c"))
          (str "a $param string under " k " must throw")))))

(deftest the-original-two-keys-still-refuse
  ;; CONTROL. A fix that widened the scan must not have broken the pair it started
  ;; with — that would be a regression wearing a fix's clothes.
  (testing ":text and :class still throw"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"left unresolved \$title in :text"
                          (scan-residue! {:tag :lv_label :text "$title"} "c")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"left unresolved \$muted in :class"
                          (scan-residue! {:tag :lv_label :class "$muted"} "c")))))

(deftest a-residual-keyword-is-refused-anywhere
  ;; The clause that ALWAYS worked, pinned so a future edit to the map clause cannot
  ;; quietly take it with it.
  (testing "a :$param keyword throws wherever it sits"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"left unresolved :\$w"
                          (scan-residue! {:tag :lv_obj :width :$w} "c")))))

(deftest it-does-NOT-cry-wolf
  ;; The control that keeps this from being widened into uselessness. Every case
  ;; here must PASS — `scan-residue!` returns nil on a clean tree.
  (testing "a fully substituted tree is clean"
    (is (nil? (scan-residue! {:tag :lv_label :text "Fuel" :style {:bg-image-src "P:i.png"}}
                             "c"))))
  (testing "CURRENCY is not a param reference — param names start with a LETTER"
    (is (nil? (scan-residue! {:tag :lv_label :text "$722"} "c")))
    (is (nil? (scan-residue! {:tag :lv_label :text "Cost: $99.50"} "c"))))
  (testing "a bare $ with no name is not a reference"
    (is (nil? (scan-residue! {:tag :lv_label :text "100%$"} "c")))))

;; ============================================================
;; optional-param pruning — :class/:event fed by an ABSENT param
;; ============================================================
(deftest test-optional-event-param-prunes-event-key
  (testing "an :$param :event whose param is not provided leaves NO :event key
            (the key is param-fed, so an absent param means an event-less node,
            not a nil-valued :event no authored screen could carry)"
    (let [components {"btn" {:params {:label {:type :string :required true}
                                      :event {:type :keyword}}
                             :tree {:tag :lv_button
                                    :event :$event
                                    :children [{:tag :lv_label :text "$label"}]}}}
          result (component/resolve-components components
                                               {:tree {:tag :btn :props {:label "Idle"}}})]
      (is (not (contains? (:tree result) :event)))
      (is (= "Idle" (get-in result [:tree :children 0 :text]))))))

(deftest test-provided-optional-event-param-substitutes
  (testing "the same optional :event param, when provided, still substitutes"
    (let [components {"btn" {:params {:label {:type :string :required true}
                                      :event {:type :keyword}}
                             :tree {:tag :lv_button
                                    :event :$event
                                    :children [{:tag :lv_label :text "$label"}]}}}
          result (component/resolve-components
                  components
                  {:tree {:tag :btn :props {:label "Go" :event :fire}}})]
      (is (= :fire (get-in result [:tree :event]))))))

(deftest test-blank-class-param-prunes-class-key
  (testing "a $param-only :class substituting to blank leaves NO :class key —
            an optional class param means 'no class', never an empty class
            string the token parser would reject"
    (let [components {"chip" {:params {:class {:type :string :default ""}}
                              :tree {:tag :lv_label :class "$class" :text "x"}}}
          result (component/resolve-components components {:tree {:tag :chip}})]
      (is (not (contains? (:tree result) :class))))))

(deftest test-static-blank-class-is-not-pruned
  (testing "a template's STATIC empty :class is not absorbed — pruning is scoped
            to param-fed values, so a static authoring error keeps failing
            loudly at the class parser instead of vanishing here"
    (let [components {"bad" {:params {} :tree {:tag :lv_label :class "" :text "x"}}}
          result (component/resolve-components components {:tree {:tag :bad}})]
      (is (= "" (get-in result [:tree :class]))))))

(deftest test-blank-param-inside-wider-class-keeps-class
  (testing "a blank param embedded in a WIDER class string leaves the class in
            place (only a class that is entirely param-fed and blank prunes)"
    (let [components {"card2" {:params {:extra {:type :string :default ""}}
                               :tree {:tag :lv_obj :class "@card $extra"}}}
          result (component/resolve-components components {:tree {:tag :card2}})]
      (is (= "@card " (get-in result [:tree :class]))))))
