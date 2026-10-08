(ns gate.view.dom
  "Small offline DOM helpers; untrusted strings only reach textContent."
  (:require [malli.core :as m]))

(def Element [:fn #(instance? js/Element %)])
(def Text [:string {:max 1048576}])
(def Tag [:enum "div" "span" "p" "h2" "h3" "button" "label" "input" "select" "option" "pre" "details" "summary" "dialog"])
(def Callback [:=> [:cat] :nil])

(defn el
  "Create an element with literal text and an optional fixed CSS class."
  ([tag text] (el tag text ""))
  ([tag text class-name]
   (let [node (.createElement js/document tag)]
     (set! (.-textContent node) text)
     (set! (.-className node) class-name)
     node)))
(m/=> el [:function [:=> [:cat Tag Text] Element] [:=> [:cat Tag Text Text] Element]])

(defn clear!
  "Clear a known report container."
  [id]
  (let [node (.getElementById js/document id)] (.replaceChildren node) node))
(m/=> clear! [:=> [:cat [:string {:min 1 :max 80}]] Element])

(defn button
  "Create a native keyboard and touch action."
  [text callback]
  (let [node (el "button" text)]
    (set! (.-type node) "button")
    (.addEventListener node "click" (fn [_] (callback)))
    node))
(m/=> button [:=> [:cat Text Callback] Element])

(defn raw-details
  "Keep exact EDN behind a native disclosure."
  [title text]
  (let [node (el "details" "")]
    (.append node (el "summary" title) (el "pre" text)) node))
(m/=> raw-details [:=> [:cat Text Text] Element])

(defn svg
  "Create SVG geometry from program-owned attributes; labels remain literal text."
  [tag attributes]
  (let [node (.createElementNS js/document "http://www.w3.org/2000/svg" tag)]
    (doseq [[k value] attributes] (.setAttribute node (name k) (str value))) node))
(m/=> svg [:=> [:cat [:enum "svg" "g" "path" "line" "rect" "title"]
                [:map-of {:max 20} :keyword [:or number? [:string {:max 4096}]]]] Element])
