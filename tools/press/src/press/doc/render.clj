(ns press.doc.render
  "A valid `press.doc` document → ONE self-contained HTML page.

   The page is the house skeleton, each part opening its own printed page:
   the cover (kicker, title, what this is, who it is for, the snapshot line,
   then any `:cover` blocks — a verdict box, an unnumbered cover figure) →
   the problem → where this stands (both titles overridable) → Contents
   (GENERATED from the parts and sections, then the `:how-to-read` blocks
   and the status-mark legend when any mark is used) → the parts, each a
   banner plus its numbered sections → Open questions → Appendix A, the
   glossary (GENERATED, alphabetical) → Appendix B, the sources.

   Generated, never authored: section numbers (1.. across all parts),
   sub-heading numbers inside a numbered section (`2.1`; a sub-heading
   with a `:label` prints the label and takes no number), item numbers per
   page, figure numbers (`Figure N — ` before the caption, cover figures
   excluded), the text of every `[:doc/ref id]` (`§ N`, `§ N.M`, a label
   or a skeleton page's title) and `[:doc/fig-ref id]` (`Figure N`), the
   contents page, the glossary and its anchors, the legend, the build
   stamp. A paragraph directly before a table, a list or a figure is kept
   on its page, and so is an introducing line (a paragraph ending in a
   colon) before whatever block follows it.

   Page anchors: `problem status contents open-questions glossary sources`,
   `part-N`, `s-<section-id>`, `s-<sub-heading-id>`, `g-<encoded term>`,
   `fig-<figure-id>`. None
   begins with `f` and a digit, the prefix every inlined figure's own ids
   get (`press.doc.svg/inline`), so a figure cannot capture a page anchor or
   another figure's marker. Rendering does not judge the document —
   `press.doc.rules` and `press.doc/check` do — so a missing skeleton part
   renders as an empty page, never a throw."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup2.core :as h]
            [malli.core :as m]
            [press.deck :as deck]
            [press.doc.desc :as desc]
            [press.doc.image :as image]
            [press.doc.spec :as spec]
            [press.doc.svg :as svg])
  (:import [java.lang StringBuilder]))

(set! *warn-on-reflection* true)

(def figs-arg
  "Each referenced figure's SVG text as read from its file, or nil when it
   cannot be shown (only `check` renders with a nil — `build!` refuses
   first)."
  [:map-of spec/ne-string [:maybe spec/any-string]])

(def ^:private ctx-spec
  [:map
   [:figs figs-arg]
   [:images image/images-spec]
   [:house desc/house-spec]
   [:gloss [:map-of spec/any-string spec/ne-string]]
   [:gloss-code [:set spec/any-string]]
   [:refs [:map-of spec/ne-string [:map [:href spec/ne-string] [:text spec/ne-string]]]]
   [:fig-nos [:map-of spec/ne-string :int]]
   [:occ [:fn volatile?]]
   [:h3-no [:fn volatile?]]
   [:item-no [:fn volatile?]]
   [:sec-no [:maybe :int]]
   [:table? :boolean]])

;; ------------------------------------------------------------- glossary

(defn- map-code-points
  "`s` with `f` applied to every code point."
  [f ^String s]
  (let [sb (StringBuilder.)]
    (doseq [cp (.toArray (.codePoints s))]
      (.appendCodePoint sb (int (f cp))))
    (str sb)))

(m/=> map-code-points [:=> [:cat ifn? spec/any-string] spec/any-string])

(defn lower
  "`s` lower-cased by the Unicode character tables — the same in every JVM
   locale (never the default locale's rules, which turn `I` into a dotless
   `ı` under a Turkish one)."
  [s]
  (map-code-points #(Character/toLowerCase (int %)) s))

(m/=> lower [:=> [:cat spec/any-string] spec/any-string])

(defn upper
  "`s` upper-cased by the Unicode character tables, locale-independent."
  [s]
  (map-code-points #(Character/toUpperCase (int %)) s))

(m/=> upper [:=> [:cat spec/any-string] spec/any-string])

(defn norm-term
  "The form a term is matched in: trimmed, lower-case (root locale)."
  [s]
  (lower (str/trim s)))

(m/=> norm-term [:=> [:cat spec/any-string] spec/any-string])

(defn anchor
  "An injective, anchor-safe encoding of term `s` after `norm-term`: `a-z`
   and `0-9` stay, a space becomes `-`, every other code point becomes
   `_<hex>_`. Two different normalised terms never share an anchor,
   whatever script they are written in."
  [s]
  (let [t (norm-term s)]
    (str/join (map (fn [cp] (cond (or (<= 97 cp 122) (<= 48 cp 57)) (str (char cp))
                                  (= cp 32) "-"
                                  :else (str "_" (Integer/toHexString cp) "_")))
                   (.toArray (.codePoints ^String t))))))

(m/=> anchor [:=> [:cat spec/any-string] spec/any-string])

(defn gloss-keys
  "Every spelling an entry answers to: its term and each `:also`, normalised."
  [entry]
  (mapv norm-term (cons (:term entry) (:also entry))))

(m/=> gloss-keys [:=> [:cat [:map [:term spec/ne-string]]] [:vector spec/any-string]])

(defn gloss-index
  "normalised spelling → anchor of its entry, over `entries`."
  [entries]
  (into {} (for [e entries k (gloss-keys e)] [k (anchor (:term e))])))

(m/=> gloss-index [:=> [:cat [:maybe [:sequential [:map [:term spec/ne-string]]]]]
                   [:map-of spec/any-string spec/any-string]])

(defn- code-spellings
  "Every normalised spelling of an entry marked `:code true`, over `entries`."
  [entries]
  (set (for [e entries :when (:code e) k (gloss-keys e)] k)))

(m/=> code-spellings [:=> [:cat [:maybe [:sequential [:map [:term spec/ne-string]]]]]
                      [:set spec/any-string]])

;; ---------------------------------------------------------------- inline

(defn slash-segments
  "`tok` cut after every `/`, never leaving a lone leading slash:
   \"/dev/tty\" → [\"/dev/\" \"tty\"], \"a/b\" → [\"a/\" \"b\"]."
  [tok]
  (let [segs (vec (re-seq #"[^/]*/|[^/]+$" tok))]
    (if (and (> (count segs) 1) (= "/" (first segs)))
      (into [(str (first segs) (second segs))] (subvec segs 2))
      segs)))

(m/=> slash-segments [:=> [:cat spec/ne-string] [:vector spec/ne-string]])

(def ^:private table-piece-max
  "The longest code piece a table cell never cuts: 12 characters, about
   22 mm of table code, narrower than any column a code token sits in."
  12)

(defn- hyphen-pieces
  "Code piece `seg` cut before each `-` when longer than `table-piece-max`.

   \"relay-controller/\" → [\"relay\" \"-controller/\"]; \"break-on!\" stays whole."
  [seg]
  (if (<= (count seg) table-piece-max) [seg] (vec (re-seq #".[^-]*" seg))))

(m/=> hyphen-pieces [:=> [:cat spec/ne-string] [:vector spec/ne-string]])

(defn- code-span
  "A code token: breaks after a `/` (in a table, also before a `-`) only.

   Every piece between those points is unbreakable, so a token never
   breaks mid-word or after a hyphen (\"break-|on!\" reads as a word the
   typesetter hyphenated). In a table cell, where columns are narrow, a
   piece longer than `table-piece-max` may also break BEFORE a hyphen,
   the convention that leaves no line ending in one."
  [ctx s]
  (let [cut (if (:table? ctx) #(into [] (mapcat hyphen-pieces) (slash-segments %)) slash-segments)]
    [:span.mono (interpose " " (for [tok (remove str/blank? (str/split s #" "))]
                                 (interpose [:wbr] (map (fn [x] [:span.nb x]) (cut tok)))))]))

(m/=> code-span [:=> [:cat ctx-spec spec/ne-string] vector?])

(defn- term-html
  "A glossary term: a link to its entry, in the code face when the entry
   is `:code true` (an undefined term is refused by the rules before a
   build, and rendered unlinked by `check`)."
  [ctx s]
  (let [cls (if (contains? (:gloss-code ctx) (norm-term s)) "term mono" "term")]
    (if-let [g (get (:gloss ctx) (norm-term s))]
      [:a {:class cls :href (str "#g-" g)} s]
      [:span {:class cls} s])))

(m/=> term-html [:=> [:cat ctx-spec spec/ne-string] vector?])

(defn- ref-html
  "The text of what `id` names (`§ N`, `§ N.M`, a label, a page title),
   linked to it (an unknown id is refused by the rules)."
  [ctx id]
  (if-let [{:keys [href text]} (get (:refs ctx) id)]
    [:a.ref {:href href} text]
    [:span.ref "§ ?"]))

(m/=> ref-html [:=> [:cat ctx-spec spec/ne-string] vector?])

(defn- fig-ref-html
  "`Figure N` for figure `id`, linked (a figure nobody places is refused by
   the rules)."
  [ctx id]
  (if-let [n (get (:fig-nos ctx) id)]
    [:a.ref {:href (str "#fig-" id)} (str "Figure " n)]
    [:span.ref "Figure ?"]))

(m/=> fig-ref-html [:=> [:cat ctx-spec spec/ne-string] vector?])

(defn- pill-html
  "A status mark: its kind in capitals, after its qualifier when it has one
   (`protocol BUILT`)."
  [kind qualifier]
  [:span {:class (str "pill " (name kind))} (str (when qualifier (str qualifier " ")) (upper (name kind)))])

(m/=> pill-html [:=> [:cat :keyword [:maybe spec/ne-string]] vector?])

(defn- leaf-html
  "An inline node that holds no inline children → hiccup."
  [ctx [tag a b]]
  (case tag
    :br [:br]
    :doc/code (code-span ctx a)
    :doc/term (term-html ctx a)
    :doc/ref (ref-html ctx a)
    :doc/fig-ref (fig-ref-html ctx a)
    :doc/pill (pill-html a b)))

(m/=> leaf-html [:=> [:cat ctx-spec vector?] vector?])

(def ^:private unit-gap
  "A number, one plain space, then a unit — the space a line must not break."
  #"(\d) (ms|µs|us|ns|s|Hz|kHz|MHz|KiB|MiB|GiB|kB|MB|GB|B|bits|bytes|mm|cm|m|%|°C)(?![\p{L}\p{N}_])")

(defn- bind-units
  "Text `s` with a no-break space between each number and its unit.

   \"a 100 ms scan\" never prints \"100\" at a line's end and \"ms\" on
   the next; \"5 seconds\" and \"3 modules\" are left alone."
  [s]
  (str/replace s unit-gap "$1\u00a0$2"))

(m/=> bind-units [:=> [:cat [:string {:min 0}]] [:string {:min 0}]])

(defn- inline
  "One inline node → hiccup."
  [ctx x]
  (cond (string? x) (bind-units x)
        (#{:b :em} (first x)) (into [(first x)] (map #(inline ctx %)) (rest x))
        :else (leaf-html ctx x)))

(m/=> inline [:=> [:cat ctx-spec spec/anything] spec/anything])

(defn- run
  "A run (one inline node or a vector of them) → a vector of hiccup."
  [ctx r]
  (if (and (vector? r) (not (keyword? (first r))))
    (mapv #(inline ctx %) r)
    [(inline ctx r)]))

(m/=> run [:=> [:cat ctx-spec spec/anything] vector?])

;; ---------------------------------------------------------------- blocks

(defn- table-html
  "A `:doc/table`: fixed layout from `:cols`, right-aligned columns from
   `:align`; six or more columns print in the smaller `wide` size. A table
   is never split across pages unless its author marks it `:breakable`: it
   then breaks between whole rows and repeats its header row. A breakable
   table's caption is a `keep-next` paragraph BEFORE the table rather than a
   `<caption>` inside it: Chromium's fragmenter ignores break-after: avoid on
   a caption and will end a page on the caption alone, while it honours the
   rule between block siblings (the same rule that keeps a heading with its
   body)."
  [ctx {:keys [cols align head rows caption breakable]}]
  (let [tctx (assoc ctx :table? true)
        cell (fn [tag i c] (into [tag (if (= :r (get align i)) {:class "r"} {})] (run tctx c)))
        line (fn [tag cs] (into [:tr] (map-indexed #(cell tag %1 %2)) cs))
        cls (str/join " " (remove nil? [(when (>= (count head) 6) "wide") (when breakable "breakable")]))
        table [:table (if (seq cls) {:class cls} {})
               (when (and caption (not breakable)) (into [:caption] (run ctx caption)))
               (into [:colgroup] (map (fn [w] [:col {:style (str "width:" w "%")}])) cols)
               [:thead (line :th head)]
               (into [:tbody] (map #(line :td %)) rows)]]
    (if (and caption breakable)
      [:div {:class "tblock"} (into [:p {:class "keep-next tcap"}] (run ctx caption)) table]
      table)))

(m/=> table-html [:=> [:cat ctx-spec spec/edn-map] vector?])

(defn- figure-html
  "A figure: its SVG inlined with ids prefixed for this occurrence, under
   its caption — numbered unless it is on the cover."
  [ctx [_ id caption] cover?]
  (let [occ (vswap! (:occ ctx) inc)
        text (get (:figs ctx) id)
        lead (when-not cover? [:b (str "Figure " (get (:fig-nos ctx) id "?") " — ")])]
    [:figure {:id (str "fig-" id)}
     (if text
       (h/raw (svg/inline text (str "f" occ "-") (:house ctx)))
       [:div.fig-absent (str "figure " id " is not current — the build refuses it")])
     (into (if lead [:figcaption lead] [:figcaption]) (run ctx caption))]))

(m/=> figure-html [:=> [:cat ctx-spec vector? :boolean] vector?])

(defn- image-html
  "An unnumbered, accessible raster and optional caption, kept together.
   Authored layout is bounded by schema; height and width retain aspect ratio."
  [ctx [_ {:keys [src alt caption width-percent max-height-mm]
           :or {width-percent 100 max-height-mm 120}}]]
  (let [raster (get (:images ctx) src)]
    [:figure {:class "raster"}
     (if raster
       [:img {:src (:uri raster) :alt alt :width (:width raster) :height (:height raster)
              :style (str "width:" width-percent "%;max-height:" max-height-mm "mm")}]
       [:div.fig-absent "raster input refused — the build refuses it"])
     (when caption (into [:figcaption] (run ctx caption)))]))

(m/=> image-html [:=> [:cat ctx-spec vector?] vector?])

(defn block-children
  "The blocks nested inside container block `b`, or nil.

   A container is a note, an item or a keep block; any other block has no
   children."
  [b]
  (case (first b)
    (:doc/note :doc/item) (drop 2 b)
    :doc/keep (rest b)
    nil))

(m/=> block-children [:=> [:cat vector?] [:maybe [:sequential spec/anything]]])

(def subhead
  "A sub-heading as `h3-parts` gives it: its title, and its options."
  [:map {:closed true}
   [:title [:vector spec/anything]]
   [:id {:optional true} spec/ne-string]
   [:label {:optional true} spec/ne-string]])

(defn h3-parts
  "A `:doc/h3` block as a map of its title and its options.

   `{:title [inline …]}`, plus its `:id` and `:label` when it has them."
  [[_ a & more]]
  (if (map? a)
    (assoc a :title (vec more))
    {:title (vec (cons a more))}))

(m/=> h3-parts [:=> [:cat vector?] subhead])

(defn subheads
  "Every sub-heading of block sequence `bs`, in reading order.

   Containers are entered; each sub-heading is what `h3-parts` gives."
  [bs]
  (mapcat (fn [b] (if (= :doc/h3 (first b)) [(h3-parts b)] (subheads (block-children b)))) bs))

(m/=> subheads [:=> [:cat [:maybe [:sequential spec/anything]]] [:sequential subhead]])

(defn- mark-tail?
  "Whether title part `x` belongs to a heading's trailing status marks."
  [x]
  (or (and (vector? x) (= :doc/pill (first x))) (and (string? x) (str/blank? x))))

(m/=> mark-tail? [:=> [:cat spec/anything] :boolean])

(defn- bind-marks
  "Rendered title `parts`, the last word kept on the line of its marks.

   `n` is how many trailing parts are status marks (and the spaces between
   them). The word before them and the marks become one unbreakable span,
   so a long title never leaves a mark alone on a line of its own."
  [parts n]
  (let [head (vec (drop-last n parts))
        s (peek head)
        i (when (and (pos? n) (string? s)) (str/last-index-of (str/trimr s) " "))]
    (if i
      (conj (pop head) (subs s 0 (inc i)) (into [:span.nb (subs s (inc i))] (take-last n parts)))
      parts)))

(m/=> bind-marks [:=> [:cat [:vector spec/anything] :int] [:vector spec/anything]])

(defn- h3-html
  "A sub-heading: `Label — text` when it has a label, else numbered `N.M`
   inside a numbered section; its `:id` becomes the anchor `s-<id>`. Its
   last word and its trailing status marks never part (`bind-marks`)."
  [ctx b]
  (let [{:keys [id label title]} (h3-parts b)
        prefix (cond label (str label " — ")
                     (:sec-no ctx) (str (:sec-no ctx) "." (vswap! (:h3-no ctx) inc) " ")
                     :else "")
        n (count (take-while mark-tail? (rseq title)))]
    (into [:h3 (if id {:id (str "s-" id)} {}) prefix] (bind-marks (mapv #(inline ctx %) title) n))))

(m/=> h3-html [:=> [:cat ctx-spec vector?] vector?])

(defn- items
  "List items under `tag`."
  [ctx tag xs]
  (into [tag] (map (fn [i] (into [:li] (run ctx i)))) xs))

(m/=> items [:=> [:cat ctx-spec :keyword [:sequential spec/anything]] vector?])

(declare blocks)

(defn- prose-block
  "A paragraph-like block → hiccup; `keep?` keeps it on the page of what
   follows."
  [ctx b keep?]
  (let [tag (first b)
        k (when keep? " keep-next")]
    (case tag
      :doc/lead (into [:p {:class (str "lead" k)}] (mapv #(inline ctx %) (rest b)))
      :doc/p (into [:p (if keep? {:class "keep-next"} {})] (mapv #(inline ctx %) (rest b)))
      :doc/small (into [:p {:class (str "small muted" k)}] (mapv #(inline ctx %) (rest b)))
      :doc/h3 (h3-html ctx b))))

(m/=> prose-block [:=> [:cat ctx-spec vector? :boolean] vector?])

(defn- item-html
  "A numbered box kept on one page: `N. title`, then its blocks."
  [ctx [_ title & body]]
  (into [:div.keep.item [:h4 (str (vswap! (:item-no ctx) inc) ". " title)]] (blocks ctx (vec body))))

(m/=> item-html [:=> [:cat ctx-spec vector?] vector?])

(defn- structure-block
  "A list, table, figure, note or item block → hiccup."
  [ctx b]
  (let [[tag a] b]
    (case tag
      :doc/ul (items ctx :ul (rest b))
      :doc/ol (items ctx :ol (rest b))
      :doc/steps (items ctx :ol.steps (rest b))
      :doc/table (table-html ctx a)
      :doc/fig (figure-html ctx b false)
      :doc/image (image-html ctx b)
      :doc/note (into [:div {:class (name a)}] (blocks ctx (vec (drop 2 b))))
      :doc/item (item-html ctx b)
      :doc/keep (into [:div.keep] (blocks ctx (vec (rest b)))))))

(m/=> structure-block [:=> [:cat ctx-spec vector?] vector?])

(def prose-tags
  "The blocks that are running text: paragraphs and sub-headings."
  #{:doc/lead :doc/p :doc/small :doc/h3})

(def ^:private para-tags #{:doc/lead :doc/p :doc/small})

(def ^:private kept-with
  "Blocks a paragraph directly before them is kept on the page with."
  #{:doc/table :doc/ul :doc/ol :doc/steps :doc/fig :doc/image})

(defn intro?
  "Whether block `b` is an introducing line.

   An introducing line is a paragraph whose text ends in a colon (its last
   string, wherever it nests)."
  [b]
  (boolean (and (para-tags (first b))
                (some-> (last (filter string? (tree-seq vector? seq b))) str/trimr (str/ends-with? ":")))))

(m/=> intro? [:=> [:cat vector?] :boolean])

(defn- keep-next?
  "Whether paragraph-like block `b` stays on the page of `nxt`, the block
   after it: always before a table, a list or a figure, and before ANY
   block when `b` is an introducing line."
  [b nxt]
  (boolean (and nxt (para-tags (first b)) (or (kept-with (first nxt)) (intro? b)))))

(m/=> keep-next? [:=> [:cat vector? [:maybe vector?]] :boolean])

(defn- blocks
  "A block sequence → hiccup, eagerly and in order (item and sub-heading
   numbers are counted as blocks render)."
  [ctx bs]
  (mapv (fn [b nxt]
          (if (prose-tags (first b))
            (prose-block ctx b (keep-next? b nxt))
            (structure-block ctx b)))
        bs (concat (rest bs) [nil])))

(m/=> blocks [:=> [:cat ctx-spec [:vector vector?]] vector?])

;; ------------------------------------------------------------- the pages

(def ^:private romans
  ["I" "II" "III" "IV" "V" "VI" "VII" "VIII" "IX" "X" "XI" "XII"])

(defn roman
  "Part number `n` (1-based) as a Roman numeral."
  [n]
  (or (get romans (dec n)) (str n)))

(m/=> roman [:=> [:cat [:int {:min 1}]] spec/ne-string])

(defn numbered-sections
  "Every body section with its generated number and part index:
   `[{:n 1 :part 1 :section {…}} …]`."
  [doc]
  (vec (map-indexed (fn [i [p s]] {:n (inc i) :part p :section s})
                    (for [[pi part] (map-indexed vector (:body doc)) s (:sections part)] [(inc pi) s]))))

(m/=> numbered-sections [:=> [:cat spec/edn-map]
                         [:vector [:map [:n :int] [:part :int] [:section spec/edn-map]]]])

(defn page-titles
  "Each skeleton page a `[:doc/ref …]` may name, with the text it prints.

   The pages are `spec/page-ids`; the text is the page's own title, or
   `Appendix A` / `Appendix B`."
  [doc]
  (zipmap spec/page-ids
          [(or (:problem-title doc) "The problem") (or (:status-title doc) "Where this stands")
           "Open questions" "Appendix A" "Appendix B"]))

(m/=> page-titles [:=> [:cat spec/edn-map] [:map-of spec/ne-string spec/ne-string]])

(defn- numbered
  "Sub-headings `hs` of section `n`, each with the `:text` a reference to
   it prints: its label, else `§ n.m` counting only the unlabelled ones."
  [n hs]
  (second (reduce (fn [[m acc] h]
                    (if (:label h)
                      [m (conj acc (assoc h :text (:label h)))]
                      [(inc m) (conj acc (assoc h :text (str "§ " n "." (inc m))))]))
                  [0 []] hs)))

(m/=> numbered [:=> [:cat :int [:sequential spec/edn-map]] [:vector spec/edn-map]])

(defn ref-entries
  "Everything a `[:doc/ref …]` could name, in page order.

   Each is `[id {:href … :text …}]`: the skeleton pages, the numbered
   sections, and every sub-heading with an `:id`. `:text` is nil for a
   sub-heading that has neither a number nor a label (the rules refuse
   it); an id listed twice is a duplicate (the rules refuse that too)."
  [doc]
  (let [secs (numbered-sections doc)
        at (fn [id text] [id {:href (str "#s-" id) :text text}])]
    (vec (concat
          (for [[id title] (page-titles doc)] [id {:href (str "#" id) :text title}])
          (for [{:keys [n section]} secs] (at (:id section) (str "§ " n)))
          (for [{:keys [n section]} secs
                h (numbered n (subheads (:blocks section))) :when (:id h)]
            (at (:id h) (:text h)))
          (for [k [:problem :status :how-to-read :open-questions :sources]
                h (subheads (get doc k)) :when (:id h)]
            (at (:id h) (:label h)))))))

(m/=> ref-entries [:=> [:cat spec/edn-map]
                   [:vector [:tuple spec/ne-string [:map [:href spec/ne-string] [:text [:maybe spec/ne-string]]]]]])

(defn fig-numbers
  "Figure id → its number, counted in page order.

   The `:doc/fig` blocks of the problem page, the status page, the numbered
   sections, the open questions and the sources count; a cover figure has
   no number."
  [doc]
  (let [pages (concat [(:problem doc) (:status doc)]
                      (map (comp :blocks :section) (numbered-sections doc))
                      [(:open-questions doc) (:sources doc)])
        ids (for [bs pages b bs :when (= :doc/fig (first b))] (second b))]
    (into {} (map-indexed (fn [i id] [id (inc i)])) (distinct ids))))

(m/=> fig-numbers [:=> [:cat spec/edn-map] [:map-of spec/ne-string :int]])

(defn- page
  "An unnumbered skeleton page; item numbers restart on it."
  [ctx id title bs]
  (into [:div.section {:id id} [:h2 title]] (blocks (assoc ctx :item-no (volatile! 0)) bs)))

(m/=> page [:=> [:cat ctx-spec spec/ne-string spec/ne-string [:vector vector?]] vector?])

(defn- cover
  "The cover: kicker, title, what this is, the meta lines, the cover blocks."
  [ctx {:keys [title kicker what-this-is audience snapshot] :as doc}]
  (into [:div.cover
         [:div.title [:div.kicker kicker] [:h1 title] (into [:p.lead] (run ctx what-this-is))]]
        (concat
         (map (fn [b] (if (= :doc/fig (first b)) (figure-html ctx b true) (structure-block ctx b))) (:cover doc))
         [[:div.meta
           [:p [:b "Who it is for: "] audience]
           [:p [:b "Snapshot of the tree at commit " [:span.mono (:commit snapshot)] ", " (:date snapshot) "."]]]])))

(m/=> cover [:=> [:cat ctx-spec spec/edn-map] vector?])

(defn pills-used
  "The status marks the document uses."
  [doc]
  (set (for [x (tree-seq coll? seq doc)
             :when (and (vector? x) (= :doc/pill (first x)))]
         (second x))))

(m/=> pills-used [:=> [:cat spec/edn-map] [:set :keyword]])

(defn- legend
  "The status-mark legend, for the marks the document uses."
  [used]
  (when (seq used)
    [:div.keep.legend
     [:p.keep-next [:b "Status marks."] " Every claim that is not plain description carries one of these:"]
     (into [:p] (for [[k meaning] spec/pill-kinds :when (used k)]
                  [:span.lg [:span {:class (str "pill " (name k))} (upper (name k))] " " meaning]))]))

(m/=> legend [:=> [:cat [:set :keyword]] [:maybe vector?]])

(defn- toc
  "The generated list of pages, parts and sections."
  [doc]
  (let [secs (numbered-sections doc)]
    (into [:div.toc
           [:p [:a {:href "#problem"} [:b (or (:problem-title doc) "The problem")]]]
           [:p [:a {:href "#status"} [:b (or (:status-title doc) "Where this stands")]]]]
          (concat
           (for [[pi part] (map-indexed vector (:body doc))
                 :let [mine (filter #(= (inc pi) (:part %)) secs)]
                 x [[:p [:a {:href (str "#part-" (inc pi))} [:b (str "Part " (roman (inc pi)) " — " (:title part))]]]
                    (into [:ol {:start (:n (first mine))}]
                          (map (fn [{:keys [section]}] [:li [:a {:href (str "#s-" (:id section))} (:title section)]]))
                          mine)]]
             x)
           [[:p [:a {:href "#open-questions"} [:b "Open questions"]]]
            [:p [:a {:href "#glossary"} "Appendix A — glossary"] " · "
             [:a {:href "#sources"} "Appendix B — where each fact lives"]]]))))

(m/=> toc [:=> [:cat spec/edn-map] vector?])

(defn- contents
  "The contents page: the generated list, how to read it, the legend."
  [ctx doc]
  (-> [:div.section {:id "contents"} [:h2 "Contents"] (toc doc)]
      (into (when (seq (:how-to-read doc))
              (into [[:h3 "How to read this"]] (blocks ctx (vec (:how-to-read doc))))))
      (conj (legend (pills-used doc)))))

(m/=> contents [:=> [:cat ctx-spec spec/edn-map] vector?])

(defn- part-html
  "A part: its banner, then its sections — the first shares the banner's
   page, every later one opens a new page."
  [ctx pi part secs]
  (into [:div.part {:id (str "part-" pi)}
         [:div.part-banner [:div.kicker (str "Part " (roman pi))] [:h2 (:title part)]
          (when (:summary part) (into [:p] (run ctx (:summary part))))]]
        (mapcat (fn [i {:keys [n section]}]
                  (let [sctx (assoc ctx :sec-no n :h3-no (volatile! 0) :item-no (volatile! 0))]
                    [(into [:div {:id (str "s-" (:id section)) :class (if (zero? i) "sec" "flow")}
                            [:h2 (str n ". " (:title section))]]
                           (blocks sctx (:blocks section)))]))
                (range) secs)))

(m/=> part-html [:=> [:cat ctx-spec :int spec/edn-map [:sequential spec/edn-map]] vector?])

(defn- first-code-point
  "The first character of `s` as a string — a whole code point, so an
   astral character (a surrogate pair) is never split."
  [^String s]
  (String. (Character/toChars (.codePointAt s 0))))

(m/=> first-code-point [:=> [:cat spec/ne-string] spec/ne-string])

(defn- glossary-html
  "Appendix A: every entry, alphabetical, grouped under its first letter."
  [ctx entries]
  (let [sorted (sort-by (comp norm-term :term) entries)
        letter #(upper (first-code-point (:term %)))
        groups (partition-by letter sorted)]
    (into [:div.section {:id "glossary"}
           [:h2 "Appendix A — glossary"]
           [:p.lead "Terms linked from this document, in plain words, in alphabetical order."]]
          (mapcat (fn [g]
                    [[:p.keep-next [:b (letter (first g))]]
                     (into [:dl.gloss]
                           (map (fn [e] [:div.gl {:id (str "g-" (anchor (:term e)))}
                                         [:dt (if (:code e) [:span.mono (:term e)] (:term e))]
                                         (into [:dd] (run ctx (:def e)))]))
                           g)]))
          groups)))

(m/=> glossary-html [:=> [:cat ctx-spec [:sequential spec/edn-map]] vector?])

(defn- stylesheet
  "The house stylesheet resource; a missing one is a packaging fault."
  []
  (if-let [r (io/resource "press/doc/house-print.css")]
    (slurp r)
    (throw (ex-info "press/doc/house-print.css resource missing" {:doc/refusal :doc/no-style}))))

(m/=> stylesheet [:=> [:cat] spec/ne-string])

(defn- body
  "Every page of the document, in skeleton order — built EAGERLY, one page
   after the other, because figure and item numbers are counted as pages
   render."
  [ctx doc]
  (let [secs (numbered-sections doc)
        front [(cover ctx doc)
               (page ctx "problem" (or (:problem-title doc) "The problem") (vec (:problem doc)))
               (page ctx "status" (or (:status-title doc) "Where this stands") (vec (:status doc)))
               (contents ctx doc)]
        with-parts (into front (map-indexed (fn [i part] (part-html ctx (inc i) part (filter #(= (inc i) (:part %)) secs))))
                         (:body doc))
        with-open-q (conj with-parts (page ctx "open-questions" "Open questions" (vec (:open-questions doc))))
        with-gloss (conj with-open-q (glossary-html ctx (vec (:glossary doc))))]
    (conj with-gloss (page ctx "sources" "Appendix B — where each fact lives" (vec (:sources doc))))))

(m/=> body [:=> [:cat ctx-spec spec/edn-map] [:vector vector?]])

(defn render
  "`doc` (valid against `spec/doc-spec`), `figs` (figure id → its SVG text),
   the house constants `h` and the build `stamp` → ONE HTML string: house
   stylesheet, Inter, figures and validated raster `images` inlined, the stamp in
   `<meta name=\"press-doc-stamp\">`. Pages render eagerly in reading order,
   so the generated numbers follow the page."
  ([doc figs h stamp] (render doc figs {} h stamp))
  ([doc figs images h stamp]
   (let [ctx {:figs figs :images images :house h :gloss (gloss-index (:glossary doc)) :gloss-code (code-spellings (:glossary doc))
              :refs (into {} (filter (comp :text second)) (ref-entries doc))
              :fig-nos (fig-numbers doc)
              :occ (volatile! 0) :h3-no (volatile! 0) :item-no (volatile! 0)
              :sec-no nil :table? false}
         page-tree [:html {:lang (or (:lang doc) "en")}
                    [:head
                     [:meta {:charset "utf-8"}]
                     [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
                     [:meta {:name "press-doc-stamp" :content stamp}]
                     [:title (:title doc)]
                     [:style (h/raw (deck/font-css :inter))]
                     [:style (h/raw (stylesheet))]]
                    (into [:body] (body ctx doc))]]
    ;; the tree is passed as a VALUE: given a literal, `h/html` compiles it
    ;; at macro time into code the page never needs
     (str "<!DOCTYPE html>\n" (h/html {:mode :html} page-tree)))))

(m/=> render [:function
              [:=> [:cat spec/edn-map figs-arg desc/house-spec spec/ne-string] spec/ne-string]
              [:=> [:cat spec/edn-map figs-arg image/images-spec desc/house-spec spec/ne-string] spec/ne-string]])
