(ns devcards.lvgl-classes-test
  "Anti-rot gate for the starter classification table.

   A hand-listed table silently rots as widgets are added: the corpus
   grows a class, nobody updates the table, and every consumer's first run
   gains an :unclassified-type finding that looks like THEIR mistake. This
   suite is what makes that a red gate here instead.

   It cross-checks the table against `corpus/spec.edn` — the spec is
   authored independently of the table, so the two can only agree by
   actually being kept in step. Pure: no wasm, no render."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [devcards.classify :as classify]
            [devcards.lvgl-classes :as lvgl]))

(def ^:private spec
  (edn/read-string (slurp (io/file "corpus/spec.edn"))))

(def ^:private spec-tags
  (into (sorted-set) (map :tag) (:widgets spec)))

(def ^:private tags-not-emitted-as-classes
  "Corpus tags that never appear as a dump `type`. lv_host_proxy is a
   ui_ast WidgetType the renderer realises as a plain lv_obj surface, so
   no LVGL class carries the name. Measured by the class-census probe —
   the census found no lv_host_proxy node across the whole corpus."
  #{"lv_host_proxy"})

(def ^:private renderer-internal-classes
  "Classes the renderer builds itself, so they appear in dumps but in no
   spec tag. Note the INCONSISTENT spelling — underscore for the roller's
   label, hyphen for the dropdown's list. Both are LVGL's own class names."
  #{"lv_roller_label" "lv_dropdown-list"})

(deftest the-table-validates
  (is (classify/validate-table! lvgl/starter-table)))

(deftest every-corpus-widget-is-classified
  (testing "a widget class in the corpus with no table row would make every
            consumer's first run report an :unclassified-type that is
            protogen's omission, not theirs"
    (let [needed (set/difference spec-tags tags-not-emitted-as-classes)
          missing (remove #(classify/classify lvgl/starter-table %) needed)]
      (is (empty? missing)
          (str "corpus widget classes missing from the starter table: "
               (vec (sort missing)))))))

(deftest renderer-internal-classes-are-classified
  (testing "these appear in dumps but in NO spec tag, so only an explicit
            check keeps them covered"
    (doseq [c renderer-internal-classes]
      (is (some? (classify/classify lvgl/starter-table c))
          (str c " is emitted by the renderer but unclassified")))))

(deftest the-table-claims-no-class-the-corpus-cannot-produce
  (testing "the reverse direction: a table row for a class that no longer
            exists is dead weight that outlives the widget it described"
    (let [known (set/union spec-tags renderer-internal-classes)
          extra (remove known (keys (:types lvgl/starter-table)))]
      (is (empty? extra)
          (str "table rows with no corpus widget or known internal class: "
               (vec (sort extra)))))))

;; ── the mechanism axis is MEASURED, not assumed ──────────────────────────

(deftest only-the-emitted-classes-that-clear-clickable-are-non-interactive
  (testing "lv_obj_constructor sets LV_OBJ_FLAG_CLICKABLE on every object
            (lv_obj.c:584). Scoped to the classes THIS renderer emits, five
            clear it: lv_image, lv_label, lv_line, lv_spinner, and
            lv_roller_label via its lv_label base. Everything else emitted
            CAN take a press. The scope matters — lv_arclabel also clears it
            and lv_menu adds/removes it per instance, but neither is emitted
            here, so neither belongs in this table."
    (let [non-interactive (into (sorted-set)
                                (for [[c e] (:types lvgl/starter-table)
                                      :when (not (:interactive? e))]
                                  c))]
      (is (= #{"lv_image" "lv_label" "lv_line" "lv_spinner" "lv_roller_label"}
             non-interactive)))))

(deftest a-read-only-indicator-is-still-in-the-pointer-path
  (testing "lv_bar does NOT clear CLICKABLE, so it is :interactive? true
            even though it is a display widget by intent. Encoding intent
            here would silence the case where a bar stacked ON TOP of a
            control kills it — the hazard the overlap rule exists for."
    (is (:interactive? (classify/classify lvgl/starter-table "lv_bar")))
    (testing "and its ROLE still records the intent"
      (is (= :text (:role (classify/classify lvgl/starter-table "lv_bar")))))))

(deftest totality-is-enforced-no-default
  (testing "no :default — a class the table does not name is REPORTED, not
            assumed harmless"
    (is (nil? (:default lvgl/starter-table)))
    (is (nil? (classify/classify lvgl/starter-table "lv_not_a_widget")))))

;; ── consumer merge ───────────────────────────────────────────────────────

(deftest a-consumer-inherits-the-lvgl-set
  (let [merged (lvgl/merge-consumer {:types {"fx_dock" {:interactive? true
                                                        :role :interactive}}})]
    (testing "its own widget is classified"
      (is (= {:interactive? true :role :interactive}
             (classify/classify merged "fx_dock"))))
    (testing "and it did not have to restate the LVGL classes"
      (is (= {:interactive? true :role :interactive}
             (classify/classify merged "lv_button"))))))

(deftest a-consumer-can-correct-an-entry
  (testing "override, not fork — a consumer whose bar really is inert can
            say so without copying the whole table"
    (let [merged (lvgl/merge-consumer {:types {"lv_bar" {:interactive? false
                                                         :role :structural}}})]
      (is (= {:interactive? false :role :structural}
             (classify/classify merged "lv_bar"))))))

(deftest a-merge-producing-a-contradiction-fails-at-merge-time
  (testing "not at judgment time, where it would surface as a confusing
            finding on some unrelated card"
    (is (thrown? Exception
                 (lvgl/merge-consumer {:types {"fx_dock" {:interactive? false
                                                          :role :interactive}}})))))
