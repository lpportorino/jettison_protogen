(ns devcards.docs-test
  "Emitter-side checks for the privileged descriptor-to-manifest seam."
  (:require [clojure.test :refer [deftest is]]
            [devcards.docs :as docs]))

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
