(ns press.doc.svg
  "A figure's SVG, validated against what the figure renderer emits and
   re-written for the page — never trusted, never pasted.

   The allowlist is `tools/press/press/fig/render.py`'s own output: the
   elements `svg defs marker path rect text`, each with exactly the
   attributes that renderer writes, each value of the shape it writes
   (numbers, `#rrggbb` or `none` colours, `M`/`L` path data, the house
   font style string, `url(#marker)` for a marker that is in the same
   file). Anything else — a `<script>`, a `<style>`, a `<foreignObject>`,
   an `on*` attribute, an `href`, a `url(…)` that is not one of the file's
   own markers, a comment, a doctype, an entity — is a refusal naming it.
   A CDATA section is not refused: it is read as text (allowed only inside
   a `<text>`, like any text) and emitted escaped, so `<![CDATA[<b>]]>`
   reaches the page as the four characters `<b>`, never as markup.

   `inline` parses the file with an XML parser (doctype and external
   entities off) and serialises the parsed tree again, so what reaches the
   page is exactly the validated structure in one quoting style, and every
   element id (with the `url(#…)` pointing at it) gets a prefix that is
   unique per figure OCCURRENCE (`f<N>-`) and cannot collide with a page
   anchor: no page anchor begins with `f` and a digit."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [press.doc.desc :as desc]
            [press.doc.spec :as spec])
  (:import [java.io StringReader]
           [javax.xml.stream XMLInputFactory XMLStreamConstants XMLStreamReader]))

(set! *warn-on-reflection* true)

(def ^:private num-re #"^-?\d+(\.\d+)?$")
(def ^:private colour-re #"^(#[0-9a-fA-F]{6}|none)$")

(defn- value-ok
  "The value predicates, by attribute; `font-style` is the house font style
   string `render.py` writes on the root."
  [font-style]
  (let [numeric #(boolean (re-matches num-re %))
        nums #(boolean (re-matches #"^-?\d+(\.\d+)?( -?\d+(\.\d+)?)*$" %))
        colour #(boolean (re-matches colour-re %))
        word #(boolean (re-matches #"^[a-z][a-z0-9-]*$" %))
        any (constantly true)]
    {:svg {"viewBox" nums "xmlns" #{"http://www.w3.org/2000/svg"} "class" word "style" #{font-style}}
     :defs {}
     :marker {"id" #(boolean (re-matches #"^[A-Za-z0-9_-]+$" %)) "viewBox" nums "refX" numeric "refY" numeric
              "markerWidth" numeric "markerHeight" numeric "orient" #{"auto" "auto-start-reverse"}}
     :path {"class" word "data-id" any "data-net" any "data-unrouted" #{"1"}
            "d" #(boolean (re-matches #"^[MLHVZz0-9 .\-]+$" %)) "fill" colour "stroke" colour
            "stroke-width" numeric "stroke-dasharray" nums "marker-end" ::marker "marker-start" ::marker}
     :rect {"class" word "data-id" any "x" numeric "y" numeric "width" numeric "height" numeric "rx" numeric
            "fill" colour "stroke" colour "stroke-width" numeric "stroke-dasharray" nums}
     :text {"x" numeric "y" numeric "font-size" numeric "font-weight" numeric "text-anchor" #{"start" "middle" "end"}
            "fill" colour "style" #{"white-space:pre"}}}))

(m/=> value-ok [:=> [:cat spec/ne-string] [:map-of :keyword [:map-of spec/ne-string spec/anything]]])

(defn font-style
  "The root `style` value `render.py` writes, from the house font family."
  [h]
  (str "font-family:" (get h "font-family") ",'Liberation Sans',Arial,sans-serif;font-feature-settings:'calt' 0"))

(m/=> font-style [:=> [:cat desc/house-spec] spec/ne-string])

(def node-spec
  "One parsed element: tag, attributes in document order, children (text
   strings appear only inside `text`)."
  [:map [:tag :keyword] [:attrs [:vector [:tuple spec/ne-string spec/any-string]]] [:children vector?]])

(defn- reader
  "A StAX reader over `text` with doctypes and external entities off."
  ^XMLStreamReader [^String text]
  (let [f (doto (XMLInputFactory/newInstance)
            (.setProperty XMLInputFactory/SUPPORT_DTD false)
            (.setProperty XMLInputFactory/IS_SUPPORTING_EXTERNAL_ENTITIES false)
            (.setProperty XMLInputFactory/IS_NAMESPACE_AWARE false)
            (.setProperty XMLInputFactory/IS_COALESCING true))]
    (.createXMLStreamReader f (StringReader. text))))

(m/=> reader [:=> [:cat spec/any-string] [:fn #(instance? XMLStreamReader %)]])

(defn- attrs-of
  "The current element's attributes, in order."
  [^XMLStreamReader r]
  (vec (for [i (range (.getAttributeCount r))]
         [(.getAttributeLocalName r (int i)) (.getAttributeValue r (int i))])))

(m/=> attrs-of [:=> [:cat [:fn #(instance? XMLStreamReader %)]] [:vector [:tuple spec/ne-string spec/any-string]]])

(defn- refuse
  "Throw `:doc/bad-svg` with `msg`."
  [msg]
  (throw (ex-info (str "figure SVG refused: " msg) {:doc/refusal :doc/bad-svg})))

(m/=> refuse [:=> [:cat spec/ne-string] :nil])

(defn- text!
  "A text event: kept inside a `<text>`, whitespace dropped, refused elsewhere."
  [^XMLStreamReader r stack]
  (let [t (.getText r)]
    (cond (str/blank? t) stack
          (= :text (:tag (peek stack))) (conj (pop stack) (update (peek stack) :children conj t))
          :else (refuse (str "text outside a <text>: " (pr-str (subs t 0 (min 40 (count t)))))))))

(m/=> text! [:=> [:cat [:fn #(instance? XMLStreamReader %)] vector?] vector?])

(defn- close
  "End the top element: it becomes its parent's last child (or the root)."
  [stack]
  (let [top (peek stack) rest* (pop stack)]
    (if (empty? rest*) [top] (conj (pop rest*) (update (peek rest*) :children conj top)))))

(m/=> close [:=> [:cat vector?] vector?])

(def ^:private passive
  "Events that carry nothing to keep."
  #{XMLStreamConstants/SPACE XMLStreamConstants/START_DOCUMENT XMLStreamConstants/END_DOCUMENT})

(defn- event!
  "Fold one parser event into the element stack."
  [^XMLStreamReader r stack]
  (let [ev (.getEventType r)]
    (cond (= ev XMLStreamConstants/START_ELEMENT)
          (conj stack {:tag (keyword (.getLocalName r)) :attrs (attrs-of r) :children []})
          (= ev XMLStreamConstants/END_ELEMENT) (close stack)
          (#{XMLStreamConstants/CHARACTERS XMLStreamConstants/CDATA} ev) (text! r stack)
          (passive ev) stack
          :else (refuse (str "a comment, processing instruction, doctype or entity (event " ev ")")))))

(m/=> event! [:=> [:cat [:fn #(instance? XMLStreamReader %)] vector?] vector?])

(defn- drain
  "Every event of `r` folded into one element stack."
  [^XMLStreamReader r]
  (loop [stack (event! r [])]
    (if (.hasNext r)
      (do (.next r) (recur (event! r stack)))
      stack)))

(m/=> drain [:=> [:cat [:fn #(instance? XMLStreamReader %)]] vector?])

(defn parse
  "The SVG `text` as one element tree, or `:doc/bad-svg` when it is not
   well-formed XML with a single root."
  [text]
  (let [^XMLStreamReader r (try (reader text) (catch Exception e (refuse (ex-message e))))
        stack (try (drain r) (catch javax.xml.stream.XMLStreamException e (refuse (ex-message e))))]
    (if (and (= 1 (count stack)) (map? (first stack)))
      (first stack)
      (refuse "not one complete root element"))))

(m/=> parse [:=> [:cat spec/any-string] node-spec])

(defn- all-nodes
  "Every element of the tree."
  [tree]
  (tree-seq #(seq (filter map? (:children %))) #(filter map? (:children %)) tree))

(m/=> all-nodes [:=> [:cat node-spec] [:sequential node-spec]])

(defn problems
  "Every way the parsed `tree` departs from what the figure renderer emits,
   given the house constants `h`; empty when the SVG is allowed."
  [tree h]
  (let [ok (value-ok (font-style h))
        nodes (all-nodes tree)
        markers (set (for [n nodes :when (= :marker (:tag n)) [k v] (:attrs n) :when (= k "id")] v))]
    (vec (concat
          (when-not (= :svg (:tag tree)) [(str "the root is <" (name (:tag tree)) ">, not <svg>")])
          (for [n nodes
                :let [allowed (get ok (:tag n))]
                p (if (nil? allowed)
                    [(str "element <" (name (:tag n)) "> is not one the figure renderer writes")]
                    (for [[k v] (:attrs n)
                          :let [pred (get allowed k)]
                          :when (not (cond (nil? pred) false
                                           (= ::marker pred) (boolean (some-> (re-matches #"^url\(#([A-Za-z0-9_-]+)\)$" v) second markers))
                                           :else (pred v)))]
                      (str "<" (name (:tag n)) " " k "=" (pr-str (subs v 0 (min 60 (count v)))) "> is not allowed")))]
            p)))))

(m/=> problems [:=> [:cat node-spec desc/house-spec] [:vector spec/ne-string]])

(defn- esc
  "Escape `s` for an attribute value or text node."
  [s]
  (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;") (str/replace "\"" "&quot;")))

(m/=> esc [:=> [:cat spec/any-string] spec/any-string])

(defn- attr-out
  "One attribute, with ids and marker references prefixed."
  [prefix [k v]]
  (let [v' (case k
             "id" (str prefix v)
             ("marker-end" "marker-start") (str/replace v #"^url\(#" (str "url(#" prefix))
             v)]
    (str " " k "=\"" (esc v') "\"")))

(m/=> attr-out [:=> [:cat spec/ne-string [:tuple spec/ne-string spec/any-string]] spec/ne-string])

(defn- emit
  "The element tree as SVG markup, ids prefixed."
  [prefix {:keys [tag attrs children]}]
  (let [open (str "<" (name tag) (str/join (map #(attr-out prefix %) attrs)))]
    (if (empty? children)
      (str open "/>")
      (str open ">" (str/join (map #(if (string? %) (esc %) (emit prefix %)) children)) "</" (name tag) ">"))))

(m/=> emit [:=> [:cat spec/ne-string node-spec] spec/ne-string])

(defn inline
  "The figure SVG `text` ready for the page, with every id prefixed
   `prefix` (`f<N>-`, N the figure's occurrence). Refuses `:doc/bad-svg`
   naming every departure from the renderer's allowlist. A CDATA section
   is read as text and emitted escaped (never refused, never markup)."
  [text prefix h]
  (let [tree (parse text)
        ps (problems tree h)]
    (when (seq ps) (refuse (str/join "; " ps)))
    (emit prefix tree)))

(m/=> inline [:=> [:cat spec/any-string (spec/whole #"f\d+-") desc/house-spec] spec/ne-string])
