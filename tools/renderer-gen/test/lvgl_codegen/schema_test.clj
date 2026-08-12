(ns lvgl-codegen.schema-test
  "Guard tests for `lvgl-codegen.schema/validate-screen-semantics`, the
   codegen-time cross-field validation. Two contracts live here:

   - the leaf-widget content-sizing check that fails a screen when a
     self-size-LESS leaf (lv_bar/lv_slider/lv_led) is content-sized and would
     silently collapse to ~0px;
   - the SUBJECT-TYPE checks, which refuse a binding whose renderer path reads
     the subject's int union member against a subject declared :string. Both the
     plain binds (:value/:checked/:mode) and the four value-conditional bindings
     are covered, because both reach the same class of silent defect.

   Hermetic: each test builds a screen map in-memory and calls
   `validate-screen-semantics` directly — no I/O, no fixtures, no sleep."
  (:require [clojure.test :refer [deftest is testing]]
            [lvgl-codegen.schema :as schema]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

;; -- leaf-widget content-sizing guard: the self-size-LESS leaves collapse --
(deftest test-validate-semantics-leaf-content-sizing-flagged
  (testing "w-content on a self-size-less parts-widget (bar/led/arc/switch/spinner/
            scale/buttonmatrix) collapses to ~0px — no GET_SELF_SIZE handler, no
            child to measure, so a hard codegen error"
    (doseq [tag [:lv_bar :lv_led :lv_arc :lv_switch :lv_spinner :lv_scale
                 :lv_buttonmatrix :lv_chart]]
      (let [screen {:type :screen
                    :subjects {:bp {:type :int}}
                    :events {}
                    :tree {:tag tag :class "w-content h-12"}}
            errors (schema/validate-screen-semantics screen)]
        (is (some #(and (= :leaf-content-sizing (:type %)) (= :width (:dimension %)))
                  errors)
            (str tag " content-sizing its width is flagged"))))))

;; -- lv_image / lv_line DEFAULT to LV_SIZE_CONTENT and answer GET_SELF_SIZE from
;; their own content (image → source dims, line → points bbox), so content-sizing
;; them is their canonical mode, NOT a collapse. They were removed from the guard
;; (were 5 tags, now 3). RED before the narrowing (they WERE flagged); GREEN after.
(deftest test-validate-semantics-self-sizing-leaf-content-ok
  (testing "content-sizing lv_image (src dims) / lv_line (points bbox) is their
            canonical LVGL mode — not a leaf-sizing error"
    (doseq [tag [:lv_image :lv_line]]
      (let [screen {:type :screen
                    :subjects {:bp {:type :int}}
                    :events {}
                    :tree {:tag tag :class "w-content h-content"}}
            errors (schema/validate-screen-semantics screen)]
        (is (not (some #(= :leaf-content-sizing (:type %)) errors))
            (str tag " content-sizing is not a leaf-sizing error (self-size widget)"))))))

;; -- slider content-sizing: an lv_slider is a parts-widget (main/knob/indicator)
;; with no CHILD to measure, so it collapses under content-sizing exactly like
;; lv_bar (already guarded) — a `w-120 h-content` slider renders 120x0. Adding
;; :lv_slider to the leaf-sizing guard catches the regression. Red→green (RED
;; before the guard entry: a slider's height is not flagged; GREEN after).
(deftest test-validate-semantics-slider-content-sizing-flagged
  (testing "an lv_slider content-sizing its height is flagged — a parts-widget with
            no child to measure collapses to 0px, like lv_bar"
    (let [screen {:type :screen
                  :subjects {:bp {:type :int}}
                  :events {}
                  :tree {:tag :lv_slider :class "w-120 h-content"}}
          errors (schema/validate-screen-semantics screen)]
      (is (some #(and (= :leaf-content-sizing (:type %)) (= :height (:dimension %)))
                errors)
          "lv_slider height content-sizing is flagged (parity with lv_bar)"))))

(deftest test-validate-semantics-leaf-h-content-bp-prefixed-flagged
  (testing "a bp-prefixed md:h-content on a leaf still collapses it at that
            breakpoint and is flagged"
    (let [screen {:type :screen
                  :subjects {:bp {:type :int}}
                  :events {}
                  :tree {:tag :lv_bar :class "w-120 md:h-content"}}
          errors (schema/validate-screen-semantics screen)]
      (is (some #(and (= :leaf-content-sizing (:type %)) (= :height (:dimension %)))
                errors)))))

(deftest test-validate-semantics-leaf-explicit-size-ok
  (testing "explicitly-sized leaves pass; a CONTAINER may still content-size (it
            has children to measure) — the guard is scoped to childless leaves"
    (let [screen {:type :screen
                  :subjects {:bp {:type :int}}
                  :events {}
                  :tree {:tag :lv_obj
                         :class "w-content h-content"
                         :children [{:tag :lv_bar :class "w-120 h-12"}
                                    {:tag :lv_led :class "w-24 h-24"}]}}]
      (is (nil? (schema/validate-screen-semantics screen))
          "explicit-sized leaves under a content-sized container produce no error"))))

;; ═══════════════════════════════════════════════════════════════════════════
;; event-def: :to NEEDS :set, because the renderer drops a valueless subject
;;
;; `{:to 5}` with no `:set` used to validate clean. The emitter then writes
;; set_value=5 with an EMPTY set_subject; renderer.c skips the mutation entirely
;; (`if (data->set_subject[0] != '\0')`) and the very next branch
;; (`set_subject[0] == '\0' || notify_host`) reclassifies the press as a HOST
;; event. So the authored intent was silently replaced by a different behaviour,
;; with no error at either end.
;;
;; NOTHING IN THE CORPUS EXERCISES THIS PATH — no authored screen uses `:to` at
;; all — so no pixel oracle, no golden and no parity lane could ever have found
;; it. Only a schema assertion can.

(defn- event-valid?
  "Does `evt` satisfy `schema/event-def`?"
  [evt]
  (m/validate schema/event-def evt))

(deftest to-without-set-is-refused
  (testing ":to alone names no subject, so it must not validate"
    (is (not (event-valid? {:to 5})))
    (is (not (event-valid? {:to 0})))
    (is (not (event-valid? {:trigger :value-changed :to 7 :notify-host true}))))
  ;; REVERT-TO-BREAK: delete the `:to needs :set` [:fn …] clause from
  ;; schema/event-def. Every assertion above must go red while the CONTROL block
  ;; below stays GREEN — that pairing is what attributes the red to this clause
  ;; rather than to the map schema or to the :set/:toggle clause beside it.
  (testing "CONTROL: the pairing is what is refused, not :to itself"
    (is (event-valid? {:set :x :to 5}))
    (is (event-valid? {:set :x :to 0}))))

(deftest the-neighbouring-clauses-still-refuse-and-still-permit
  ;; The control for the mutation above, and the guard against a fix that
  ;; over-refuses: these must be unaffected by the new clause.
  (testing ":set alone is legitimate — it writes the default 0"
    (is (event-valid? {:set :x})))
  (testing "a bare event is legitimate — 'send my name on click'"
    (is (event-valid? {})))
  (testing ":toggle alone is legitimate"
    (is (event-valid? {:toggle :x})))
  (testing ":set and :toggle together are still mutually exclusive"
    (is (not (event-valid? {:set :x :toggle :y})))))

;; ═══════════════════════════════════════════════════════════════════════════
;; A REACTIVE state binding and the create-time :states bit it competes with
;;
;; `:checked-when` + `:states #{:checked}` was rejected. `:enabled-when` +
;; `:states #{:disabled}` was NOT, even though renderer.c calls enabled_when "the
;; reactive sibling of checked_when with INVERTED polarity" and binds
;; LV_STATE_DISABLED through the same machinery.
;;
;; WHY THE PAIRING IS AN ERROR AND NOT A PRECEDENCE QUESTION: the reactive source
;; WINS by construction. Create-time states are applied while the node is built
;; (`lv_obj_add_state(obj, node->states)`); the unified apply_compare_binding
;; runs in a DEFERRED post-subjects pass, so the observer's first evaluation
;; overwrites whatever the author asked for — silently. No ordering an author could
;; rely on exists.
;;
;; The repair is the TABLE, not the second clause: two clauses hand-written from one
;; contract drift, and the missing one is invisible because nothing enumerates the
;; pair set.

(defn- semantic-error-types
  "The `:type`s `validate-screen-semantics` reports for a one-widget screen."
  [widget]
  (set (map :type (schema/validate-screen-semantics
                   {:type :screen :subjects {:s {:type :int}} :events {}
                    :tree widget}))))

(deftest reactive-state-binding-conflicts-are-total-over-the-table
  ;; TOTALITY over the abstraction. Every entry must be enforced, so an entry added
  ;; without a check — or a check deleted — fails here rather than by omission.
  (testing "every [binding state error] row is actually rejected"
    (doseq [[binding-key state-bit err-type] schema/reactive-state-bindings]
      (let [types (semantic-error-types
                   {:tag :lv_button :id "b" :class "w-12 h-12"
                    :states #{state-bit}
                    binding-key {:subject :s :value 1}})]
        (is (contains? types err-type)
            (format "%s paired with :states #{%s} must report %s"
                    binding-key state-bit err-type)))))
  (testing "the table is non-empty and covers every known reactive binding"
    ;; A floor, because a table emptied by a bad edit would make the loop above
    ;; vacuous and it would still pass.
    (is (>= (count schema/reactive-state-bindings) 3))
    (is (= #{:checked-when :enabled-when :pending-when}
           (set (map first schema/reactive-state-bindings))))))

(deftest neither-binding-alone-is-an-error
  ;; THE CRY-WOLF CONTROL. A reactive binding on its own is the whole point of the
  ;; feature; only the PAIRING with a competing create-time bit is rejected.
  (testing "a reactive binding with no competing :states bit is clean"
    (doseq [[binding-key _ err-type] schema/reactive-state-bindings]
      (is (not (contains? (semantic-error-types
                           {:tag :lv_button :id "b" :class "w-12 h-12"
                            binding-key {:subject :s :value 1}})
                          err-type))
          (str binding-key " alone must not be an error"))))
  (testing "the competing :states bit with no binding is clean"
    (doseq [[_ state-bit err-type] schema/reactive-state-bindings]
      (is (not (contains? (semantic-error-types
                           {:tag :lv_button :id "b" :class "w-12 h-12"
                            :states #{state-bit}})
                          err-type))
          (str ":states #{" state-bit "} alone must not be an error"))))
  (testing "and a binding paired with the OTHER binding's state bit is clean"
    ;; :checked-when with :disabled writes two DIFFERENT bits, so there is no
    ;; conflict — this is what stops the table's loop from over-refusing.
    (is (not (contains? (semantic-error-types
                         {:tag :lv_button :id "b" :class "w-12 h-12"
                          :states #{:disabled}
                          :checked-when {:subject :s :value 1}})
                        :checked-when-states-conflict)))))

;; ── bind-key subject TYPE ────────────────────────────────────────────────────
;; The interpreter dispatches four bind keys. Three of them read the subject's
;; INT union member, so a :string subject is a silent defect rather than a type
;; error, and the two failure modes differ: :checked's binder refuses a non-int
;; and returns NULL after a log line (the binding is simply absent), while
;; :value and :mode do not check at all and read the wrong member outright.
;; :text is exempt by measurement — lv_label_bind_text accepts INT and STRING.
(deftest bind-to-a-string-subject-is-refused-for-the-int-only-keys
  (testing ":value / :checked / :mode against a declared :string subject"
    (doseq [[tag k] [[:lv_slider :value] [:lv_switch :checked] [:lv_host_proxy :mode]]]
      (let [screen {:type :screen
                    :subjects {:s {:type :string :default ""}}
                    :events {}
                    :tree (cond-> {:tag tag :class "w-12 h-12" :bind {k :s}}
                            (= :lv_host_proxy tag)
                            (assoc :id "proxy" :host_proxy_props {:proxy_id "p0"}))}
            errors (schema/validate-screen-semantics screen)]
        (is (some #(and (= :bind-subject-not-int (:type %))
                        (= k (:key %))
                        (= :s (:subject %))
                        (= :string (:declared-type %)))
                  errors)
            (str tag " binding " k " to a :string subject is flagged"))))))

(deftest bind-text-to-a-string-subject-is-permitted
  (testing ":text is deliberately NOT int-only — a blanket rule would reject the
            string subjects lv_label_bind_text exists to render"
    (let [screen {:type :screen
                  :subjects {:s {:type :string :default ""}}
                  :events {}
                  :tree {:tag :lv_label :class "w-12 h-12" :bind {:text :s}}}
          errors (schema/validate-screen-semantics screen)]
      (is (not-any? #(= :bind-subject-not-int (:type %)) errors)
          ":text against a :string subject is the canonical case, not an error"))))

(deftest an-int-subject-satisfies-every-bind-key
  (testing "the guard fires on the subject TYPE, not on the presence of a key"
    (doseq [[tag k] [[:lv_slider :value] [:lv_switch :checked]
                     [:lv_host_proxy :mode] [:lv_label :text]]]
      (let [screen {:type :screen
                    :subjects {:s {:type :int :default 0}}
                    :events {}
                    :tree (cond-> {:tag tag :class "w-12 h-12" :bind {k :s}}
                            (= :lv_host_proxy tag)
                            (assoc :id "proxy" :host_proxy_props {:proxy_id "p0"}))}
            errors (schema/validate-screen-semantics screen)]
        (is (not-any? #(= :bind-subject-not-int (:type %)) errors)
            (str tag " binding " k " to an :int subject is clean"))))))

(deftest an-undeclared-bind-subject-reports-only-the-undeclared-error
  (testing "a missing declaration must not ALSO invent a type error — an
            undeclared subject has no declared type to disagree with"
    (let [screen {:type :screen
                  :subjects {}
                  :events {}
                  :tree {:tag :lv_slider :class "w-12 h-12" :bind {:value :nope}}}
          errors (schema/validate-screen-semantics screen)]
      (is (some #(and (= :undeclared-subject (:type %)) (= :nope (:ref %))) errors)
          "the undeclared subject is reported")
      (is (not-any? #(= :bind-subject-not-int (:type %)) errors)
          "and no type error is manufactured alongside it"))))

;; ── the four value-conditional bindings ──────────────────────────────────────
;; These checks shipped WITHOUT a test, so the mechanism this change extends had
;; never been watched to fire. Covered here for that reason, not because the
;; behaviour is new. They ride the wire as an int32 ref_value compared through
;; lv_subject_get_int, so against a :string subject the comparison reads the
;; wrong union member and NEVER matches.
(deftest conditional-bindings-refuse-a-string-subject
  (testing "each of the four reports its OWN error type, so a reader can tell
            which binding is at fault"
    (doseq [[bind-key err-type] [[:show-when :show-when-subject-not-int]
                                 [:checked-when :checked-when-subject-not-int]
                                 [:enabled-when :enabled-when-subject-not-int]
                                 [:pending-when :pending-when-subject-not-int]
                                 [:color-when :color-when-subject-not-int]]]
      (let [screen {:type :screen
                    :subjects {:s {:type :string :default ""}}
                    :events {}
                    :tree {:tag :lv_label :class "w-12 h-12" bind-key {:subject :s}}}
            errors (schema/validate-screen-semantics screen)]
        (is (some #(and (= err-type (:type %))
                        (= :s (:subject %))
                        (= :string (:declared-type %)))
                  errors)
            (str bind-key " against a :string subject is flagged as " err-type))))))

(deftest conditional-bindings-permit-an-int-subject
  (testing "an :int subject is the comparable case and must stay clean"
    (let [mismatch-types #{:show-when-subject-not-int :checked-when-subject-not-int
                           :enabled-when-subject-not-int :pending-when-subject-not-int
                           :color-when-subject-not-int}]
      (doseq [bind-key [:show-when :checked-when :enabled-when :pending-when :color-when]]
        (let [screen {:type :screen
                      :subjects {:s {:type :int :default 0}}
                      :events {}
                      :tree {:tag :lv_label :class "w-12 h-12" bind-key {:subject :s}}}
              errors (schema/validate-screen-semantics screen)]
          (is (not-any? (comp mismatch-types :type) errors)
              (str bind-key " against an :int subject is clean")))))))

(deftest conditional-bindings-report-an-undeclared-subject
  (testing "an undeclared conditional subject is its own error, distinct from a
            type mismatch"
    (doseq [[bind-key err-type] [[:show-when :undeclared-show-when-subject]
                                 [:checked-when :undeclared-checked-when-subject]
                                 [:enabled-when :undeclared-enabled-when-subject]
                                 [:pending-when :undeclared-pending-when-subject]
                                 [:color-when :undeclared-color-when-subject]]]
      (let [screen {:type :screen
                    :subjects {}
                    :events {}
                    :tree {:tag :lv_label :class "w-12 h-12" bind-key {:subject :nope}}}
            errors (schema/validate-screen-semantics screen)]
        (is (some #(and (= err-type (:type %)) (= :nope (:ref %))) errors)
            (str bind-key " with no declaration is flagged as " err-type))))))

;; event-def's inner map was OPEN, so a misspelled key validated clean and then
;; did NOTHING: every emitter reads a fixed key set, so an unrecognised key is
;; dropped without a word at the one point a mistake is still cheap to catch.
;; The :props axis has carried a closed-map guard all along; this is its twin,
;; and `gesture-step-def` two definitions above already closes its own map.
(deftest an-undeclared-event-key-is-refused
  (testing "a misspelled flag is REFUSED rather than silently ignored"
    (is (not (event-valid? {:notify-hostt true}))
        ":notify-hostt is a typo for :notify-host — an open map accepts it and the emitter drops it")
    (is (not (event-valid? {:trigger :value-changed :toggl :armed}))
        ":toggl is a typo for :toggle — accepted and dropped while the control looks authored"))
  (testing "and every DECLARED shape still validates"
    (is (event-valid? {}) "a bare {} means 'send event name on click'")
    (is (event-valid? {:trigger :value-changed :include-value true}))
    (is (event-valid? {:toggle :armed :notify-host true}))
    (is (event-valid? {:set :armed :to 1}))))
