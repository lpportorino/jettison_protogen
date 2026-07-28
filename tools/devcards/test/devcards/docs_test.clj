(ns devcards.docs-test
  "Emitter-side checks for the privileged descriptor-to-manifest seam, and for
   the retirement policy that seam hands to `devcards.docgen/prune-orphans!`.

   The policy half is deliberately judged against the REAL committed doc tree
   as well as against planted paths. A recogniser for `the artifacts this
   emitter writes` that is only ever shown names a test invented can pass while
   matching nothing on disk — and it would fail SILENTLY, by classifying every
   real artifact as `not ours` and pruning nothing forever."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [devcards.docs :as docs])
  (:import (java.io File)))

(defn- descriptor
  [field-name]
  {"file"
   [{"name" "ui/ui_ast.proto"
     "enumType"
     [{"name" "WidgetType" "value" [{"name" "WIDGET_OBJ"}]}]
     "messageType"
     [{"name" "WidgetNode"
       "field" [{"name" "obj_props" "typeName" ".ui.ObjProps"}]}
      {"name" "ObjProps"
       "field" [{"name" field-name
                 "number" 7
                 "type" "TYPE_UINT32"
                 "label" "LABEL_OPTIONAL"
                 "options" {"[buf.validate.field]" {"uint32" {"lte" 100}}}}]}]}]})

(def ^:private spec
  {:widgets [{:type :WIDGET_OBJ
              :tag "lv_obj"
              :cards [{:id "lv_obj/default/small"}]}]})

(defn- registry-entry
  [field-name]
  (first (docs/widget-registry (descriptor field-name)
                               spec
                               {:WIDGET_OBJ [:default]})))

(deftest descriptor-fields-cross-the-seam-as-structured-data
  (let [{:keys [message-name qualified-name fields]} (registry-entry "alpha")
        block (docs/props-block message-name qualified-name fields)]
    (is (= [{:name "alpha"
             :number 7
             :type "uint32"
             :constraints "uint32.lte=100"}]
           fields))
    (is (= :table (:section/kind block)))
    (is (= ["alpha" 7 "uint32" "uint32.lte=100"]
           (first (:table/rows block))))))

;; ── the retirement policy ───────────────────────────────────────────────
;; `orphan-disposition` answers ONE question — is this file one this emitter's
;; own naming scheme could have written for the unit directory it sits in. It
;; knows nothing about whether the file is currently claimed; that is
;; `prune-orphans!`'s totality precondition, tested in `docgen_test`. So
;; `:retired` here reads `authorised to go IF it turns out to be an orphan`.

(def ^:private disposition (docs/orphan-disposition docs/out-dir))

(defn- classify
  "Classify a tool-relative path the way the pruner will see it — canonical,
   because that is the identity `devcards.docgen/audit` reconciles on."
  [^String path]
  (disposition (.getCanonicalPath (File. path))))

(defn- unit-dirs []
  (sort-by #(.getName ^File %)
           (filterv #(.isDirectory ^File %) (.listFiles (io/file docs/out-dir)))))

(deftest every-committed-doc-artifact-is-recognised-except-the-index
  (testing "run over the REAL committed tree: the recogniser matches what the
            emitter actually wrote, and the ONE file it declines is the index
            page, which sits at the tree root and belongs to a constant unit"
    (let [files (filterv #(.isFile ^File %) (file-seq (io/file docs/audit-root)))
          by-disposition (group-by #(disposition (.getCanonicalPath ^File %)) files)]
      ;; an anti-vacuous floor, not a pinned count: a walk that found nothing
      ;; would satisfy every assertion below without judging anything.
      (is (< 100 (count files)) "the subject is the committed tree, not an empty walk")
      (is (= #{:retired :at-the-tree-root} (set (keys by-disposition)))
          "no committed artifact falls into a decline class other than the index")
      (is (= ["README.md"]
             (mapv #(.getName ^File %) (:at-the-tree-root by-disposition))))
      (is (= (dec (count files)) (count (:retired by-disposition)))))))

(deftest a-real-image-name-under-the-WRONG-unit-directory-is-NOT-ours
  (testing "the image name is one the emitter really wrote, so only the
            slug-to-directory binding can reject it here"
    (let [[dir-a dir-b] (unit-dirs)
          image (first (filterv #(str/ends-with? (.getName ^File %) ".jpg")
                                (.listFiles ^File dir-a)))
          fname (.getName ^File image)]
      (is (= :retired (classify (str (.getPath ^File dir-a) "/" fname)))
          "CONTROL: under its own unit directory the same name is authorised")
      (is (= :not-an-emitter-artifact
             (classify (str (.getPath ^File dir-b) "/" fname)))
          "the slug half of the name must match the directory it sits in"))))

(deftest orphan-disposition-NAMES-every-shape-it-declines
  (let [unit (.getPath ^File (first (unit-dirs)))
        real-image (.getName ^File (first (filterv #(str/ends-with? (.getName ^File %) ".jpg")
                                                   (.listFiles (io/file unit)))))]
    (testing "the page shape and the image shape are both authorised"
      (is (= :retired (classify (str unit "/README.md"))))
      (is (= :retired (classify (str unit "/" real-image)))))
    (testing "a file directly in the units root — where the index page lives —
              is never this emitter's to remove"
      (is (= :at-the-tree-root (classify (str docs/out-dir "/README.md")))))
    (testing "the audited root is the PARENT of the units root; the band
              between them is reported, never swept"
      (is (= :outside-the-unit-tree (classify (str docs/audit-root "/stray.md"))))
      (is (= :outside-the-unit-tree (classify "corpus/spec.edn"))))
    (testing "nothing here writes below <unit>/<file>"
      (is (= :below-a-unit-directory (classify (str unit "/sub/" real-image)))))
    (testing "right depth, wrong name"
      (is (= :not-an-emitter-artifact (classify (str unit "/notes.md"))))
      (is (= :not-an-emitter-artifact (classify (str unit "/" real-image ".bak")))))
    (testing "the render-set suffix is a CLOSED list — an image named for a
              family this emitter does not render is not one of ours"
      (is (= :not-an-emitter-artifact
             (classify (str unit "/" (.getName ^File (io/file unit))
                            "-default_small-no-such-family.jpg")))))))

(deftest required-pages-declares-one-page-per-unit-that-writes-one
  (testing "the totality precondition is resolved from the registry and the
            two open-class constants, and it names the index; the states
            legend declares no page, so it can attest to nothing"
    (let [pages (docs/required-pages
                 (docs/widget-registry (descriptor "alpha") spec {:WIDGET_OBJ [:default]}))]
      (is (= ["docs/widgets/WIDGET_OBJ/README.md"
              "docs/widgets/kitchen-sinks/README.md"
              "docs/widgets/legos/README.md"
              "docs/widgets/README.md"]
             pages))
      (is (not-any? #(str/includes? ^String % "states-legend") pages)))))

(deftest descriptor-mutation-changes-the-props-block
  (let [block (fn [field-name]
                (let [{:keys [message-name qualified-name fields]}
                      (registry-entry field-name)]
                  (docs/props-block message-name qualified-name fields)))
        before (block "before")
        after (block "after")]
    (is (not= before after))
    (is (= "before" (get-in before [:table/rows 0 0])))
    (is (= "after" (get-in after [:table/rows 0 0])))))
