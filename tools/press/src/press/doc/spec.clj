(ns press.doc.spec
  "Malli specs for a `press.doc` document (`<doc>/doc.edn`).

   Front matter (`:title :kicker :what-this-is :audience :snapshot`, with
   optional `:cover` blocks, `:problem-title`, `:status-title` and
   `:how-to-read`) plus the house skeleton: `:problem`, `:status`, `:body`
   (parts of numbered sections), `:open-questions`, `:glossary`, `:sources`.

   BLOCKS, hiccup-like: `[:doc/lead …]`, `[:doc/p …]`, `[:doc/small …]` (a
   small grey note), `[:doc/h3 …]`, `[:doc/ul …]`, `[:doc/ol …]`,
   `[:doc/steps …]`, `[:doc/table {…}]`, `[:doc/note kind …]`, and — at a
   page's TOP level only — `[:doc/fig \"id\" caption]`,
   `[:doc/item \"title\" & blocks]` (a numbered box kept on one page) and
   `[:doc/keep & blocks]` (a run of blocks kept on one page). A figure is
   never inside a note, an item or a keep block: a container would scale
   it, and a figure prints at exactly the column width. There is no
   raw-markup block. A raster is `[:doc/image {:src \"local.png\" :alt
   \"description\" :caption run? :width-percent 1..100? :max-height-mm 1..180?}]`,
   at top level or on the cover. Its source resolves relative to doc.edn,
   inside the configured root, and must decode as PNG or JPEG. The default
   layout is 100 percent column width and at most 120 mm tall, preserving
   aspect ratio; raster captions are unnumbered.

   A SUB-HEADING is `[:doc/h3 opts? & heading-text]`. Its text is one or
   more of: a string, `[:em …]`, `[:doc/code …]`, `[:doc/pill …]` — so a
   heading can name a path and carry status marks. `opts` is `{:id …}` (an
   anchor a `[:doc/ref …]` can name) and/or `{:label \"Step 0\"}` (printed
   as `Step 0 — text` INSTEAD of the generated `N.M` number).

   Text inside a block is INLINE content: a string, or `[:b …]`, `[:em …]`,
   `[:br]`, `[:doc/code \"identifier\"]`, `[:doc/term \"word\"]`,
   `[:doc/pill :built]` or `[:doc/pill :built \"protocol\"]` (prints
   `protocol BUILT`), `[:doc/ref \"id\"]` (a section prints `§ N`, a
   sub-heading `§ N.M` or its label, a skeleton page — `page-ids` — its
   title; checked) and `[:doc/fig-ref \"figure-id\"]` (prints `Figure N`,
   checked). A RUN is one inline node or a vector of them (its first
   element is not a keyword, so it never reads as a tag) — a list item, a
   table cell and a caption are runs.

   A paragraph that ends in a colon is an INTRODUCING LINE: it is always
   kept on the page of the block after it, and the rules refuse one that
   introduces nothing.

   A GLOSSARY ENTRY is `{:term \"…\" :also [\"…\"] :code true :def run}`
   (`:also` and `:code` optional). `:code true` marks a term that is an
   identifier (`batch_id`): the entry and every `[:doc/term …]` that names it
   print in the code face.

   Every map is closed and every tag is from a fixed set: a misspelt key or an
   unknown block is an error, never silently ignored. The structural rules a
   valid document can still break (skeleton parts present, a lead first,
   table widths summing to 100, glossary use, figure freshness) live in
   `press.doc.rules`."
  (:require [malli.core :as m]))

(set! *warn-on-reflection* true)

(def ne-string
  "A non-empty string."
  [:string {:min 1}])

(defn whole
  "A string schema that holds when the WHOLE string matches `re`
   (`re-matches`). Malli's `[:re …]` is a search, and Java's `$` matches
   before a final line terminator, so `[:re #\"^x$\"]` accepts `\"x\\n\"`."
  [re]
  [:and [:string {:min 1}] [:fn {:error/message (str "must match " re " exactly")} #(boolean (re-matches re %))]])

(m/=> whole [:=> [:cat [:fn #(instance? java.util.regex.Pattern %)]] vector?])

(def any-string
  "Any string, the empty one included."
  [:string {:min 0}])

(def anything
  "Any value, nil included (the analyzer-clean spelling of `:any`)."
  [:maybe some?])

(def edn-map
  "A map read from EDN — keyword keys, any values."
  [:map-of :keyword [:maybe some?]])

(def pill-kinds
  "The status marks, in legend order, with what each one claims."
  [[:built "exists in the tree and runs"]
   [:measured "a figure taken by a measurement or a recorded experiment"]
   [:designed "specified in the tree's design docs, not yet built"]
   [:proposed "proposed by this document, not yet specified anywhere else"]
   [:open "an unresolved question"]
   [:ruled "a decision on record"]])

(def note-kinds
  "The boxed-note kinds: a plain note, a warning, a verdict."
  [:note :warn :verdict])

(def page-ids
  "The skeleton pages a `[:doc/ref …]` may name.

   Each is also the page's anchor, so no section or sub-heading may take
   one as its `:id`."
  ["problem" "status" "open-questions" "glossary" "sources"])

(defn- tag-of
  "The tag of a hiccup-like vector, `::text` for a string, or `::other`."
  [x]
  (cond (string? x) ::text
        (and (vector? x) (keyword? (first x))) (first x)
        :else ::other))

(m/=> tag-of [:=> [:cat anything] :keyword])

(defn- run-vector?
  "A non-empty vector whose first element is not a keyword — a sequence of
   inline nodes, never a tagged node."
  [x]
  (and (vector? x) (seq x) (not (keyword? (first x)))))

(m/=> run-vector? [:=> [:cat anything] :boolean])

(defn- tagged
  "A `[tag & children]` sequence schema."
  [tag & children]
  (into [:cat [:= tag]] children))

(m/=> tagged [:=> [:cat :keyword [:* anything]] vector?])

(def ^:private section-id
  (whole #"[a-z0-9][a-z0-9-]*"))

(defn- common-blocks
  "The blocks allowed everywhere, with `inner` the schema of a note's
   children."
  [inner]
  [[:doc/lead (tagged :doc/lead [:+ [:schema [:ref ::inline]]])]
   [:doc/p (tagged :doc/p [:+ [:schema [:ref ::inline]]])]
   [:doc/small (tagged :doc/small [:+ [:schema [:ref ::inline]]])]
   [:doc/h3 (tagged :doc/h3 [:? ::h3-opts] [:+ [:schema [:ref ::head-inline]]])]
   [:doc/ul (tagged :doc/ul [:+ ::run])]
   [:doc/ol (tagged :doc/ol [:+ ::run])]
   [:doc/steps (tagged :doc/steps [:+ ::run])]
   [:doc/table (tagged :doc/table ::table)]
   [:doc/note (tagged :doc/note (into [:enum] note-kinds) [:+ [:schema [:ref inner]]])]])

(m/=> common-blocks [:=> [:cat :keyword] [:vector vector?]])

(def ^:private image-block
  [:doc/image (tagged :doc/image ::image)])

(def ^:private fig-block
  [:doc/fig (tagged :doc/fig ne-string ::run)])

(def ^:private pill
  [:doc/pill (tagged :doc/pill (into [:enum] (map first) pill-kinds) [:? ne-string])])

(def registry
  "The document's schema registry (recursive: inline nodes nest, notes hold
   blocks). A `:ref` inside a sequence schema is wrapped in `:schema`, which
   tells Malli it is ONE element, not a sequence to splice in."
  {::inline [:multi {:dispatch tag-of}
             [::text :string]
             [:b (tagged :b [:* [:schema [:ref ::inline]]])]
             [:em (tagged :em [:* [:schema [:ref ::inline]]])]
             [:br (tagged :br)]
             [:doc/code (tagged :doc/code ne-string)]
             [:doc/term (tagged :doc/term ne-string)]
             [:doc/ref (tagged :doc/ref ne-string)]
             [:doc/fig-ref (tagged :doc/fig-ref ne-string)]
             pill]
   ::head-inline [:multi {:dispatch tag-of}
                  [::text :string]
                  [:em (tagged :em [:* [:schema [:ref ::head-inline]]])]
                  [:doc/code (tagged :doc/code ne-string)]
                  pill]
   ::h3-opts [:map {:closed true}
              [:id {:optional true} section-id]
              [:label {:optional true} ne-string]]
   ::run [:or [:ref ::inline] [:and [:vector [:ref ::inline]] [:fn run-vector?]]]
   ::image [:map {:closed true}
            [:src ne-string]
            [:alt (whole #"(?s).*\S.*")]
            [:caption {:optional true} ::run]
            [:width-percent {:optional true} [:int {:min 1 :max 100}]]
            [:max-height-mm {:optional true} [:int {:min 1 :max 180}]]]
   ::table [:map {:closed true}
            [:cols {:optional true} [:vector [:int {:min 1}]]]
            [:align {:optional true} [:vector [:enum :l :r]]]
            [:head [:vector {:min 1} ::run]]
            [:rows [:vector {:min 1} [:vector {:min 1} ::run]]]
            [:caption {:optional true} ::run]
            [:breakable {:optional true} :boolean]]
   ::inner (into [:multi {:dispatch tag-of}] (common-blocks ::inner))
   ::block (into [:multi {:dispatch tag-of}]
                 (conj (common-blocks ::inner)
                       fig-block
                       image-block
                       [:doc/item (tagged :doc/item ne-string [:+ [:schema [:ref ::inner]]])]
                       [:doc/keep (tagged :doc/keep [:+ [:schema [:ref ::inner]]])]))
   ::cover-block [:multi {:dispatch tag-of}
                  fig-block
                  image-block
                  [:doc/note (tagged :doc/note (into [:enum] note-kinds) [:+ [:schema [:ref ::inner]]])]]
   ::blocks [:vector [:ref ::block]]
   ::section [:map {:closed true}
              [:id section-id]
              [:title ne-string]
              [:blocks [:vector {:min 1} [:ref ::block]]]]
   ::part [:map {:closed true}
           [:title ne-string]
           [:summary {:optional true} ::run]
           [:sections [:vector {:min 1} ::section]]]
   ::gloss-entry [:map {:closed true}
                  [:term ne-string]
                  [:also {:optional true} [:vector ne-string]]
                  [:code {:optional true} :boolean]
                  [:def ::run]]
   ::doc [:map {:closed true}
          [:title ne-string]
          [:kicker ne-string]
          [:what-this-is ::run]
          [:audience ne-string]
          [:snapshot [:map {:closed true}
                      [:commit (whole #"[0-9a-f]{7,40}")]
                      [:date (whole #"\d{4}-\d{2}-\d{2}")]]]
          [:lang {:optional true} (whole #"[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")]
          [:cover {:optional true} [:vector [:ref ::cover-block]]]
          [:problem-title {:optional true} ne-string]
          [:status-title {:optional true} ne-string]
          [:how-to-read {:optional true} [:vector [:ref ::inner]]]
          [:problem {:optional true} ::blocks]
          [:status {:optional true} ::blocks]
          [:body {:optional true} [:vector ::part]]
          [:open-questions {:optional true} ::blocks]
          [:glossary {:optional true} [:vector ::gloss-entry]]
          [:sources {:optional true} ::blocks]]})

(defn- with-registry
  "`k` resolved against `registry`."
  [k]
  [:schema {:registry registry} k])

(m/=> with-registry [:=> [:cat :keyword] vector?])

(def doc-spec
  "A whole document (`doc.edn`). The skeleton parts are optional HERE so
   that a missing one is refused by name by the skeleton rule, not as a
   generic schema error."
  (with-registry ::doc))

(def block-spec
  "One block."
  (with-registry ::block))

(def run-spec
  "One run of inline content."
  (with-registry ::run))

;; ------------------------------------------------------------- violations

(def violation
  "One reason a document cannot be built: the rule, a sentence that names
   the fix site, and where it is (a path into the document or a file)."
  [:map {:closed true}
   [:rule :keyword]
   [:message ne-string]
   [:at {:optional true} any-string]])

(def fig-states
  "A figure's freshness: `:current` (its pick matches its description and
   its SVG), `:stale` (one of the two hashes differs), `:unpicked` (no pick
   record), `:missing-svg` (a pick but no SVG), `:invalid` (the description
   or the pick record cannot be read or fails its schema)."
  [:enum :current :stale :unpicked :missing-svg :invalid])

(def fig-status-entry
  "One figure's state, with the reason when it is not current."
  [:map {:closed true}
   [:id ne-string]
   [:state fig-states]
   [:reason {:optional true} ne-string]])
