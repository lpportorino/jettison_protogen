(ns press.doc.rules
  "The structural rules a schema-valid `press.doc` document can still break.
   Each returns violations (`spec/violation`: `:rule`, a sentence naming the
   fix, `:at` the place); an empty vector means the rule holds. `build!`
   refuses a document with any violation.

     :doc/skeleton        a skeleton part is missing or empty — every
                          document carries :problem, :status, :body,
                          :open-questions, :glossary and :sources
     :doc/lead-first      a page section (each skeleton page and each
                          numbered section) does not open with :doc/lead
     :doc/table-cols      a table without :cols, with a width count that
                          differs from its column count, or widths that do
                          not sum to 100
     :doc/table-align     an :align whose count differs from the columns
     :doc/table-ragged    a header or row whose cell count differs
     :doc/duplicate-id    an :id used twice among the sections and the
                          sub-headings, or one that is a skeleton page's
                          name (`spec/page-ids`)
     :doc/subhead-unnumbered  a sub-heading with an :id that has neither a
                          number (it is not in a numbered section) nor a
                          :label — a reference to it would print nothing
     :doc/ref-undefined   a [:doc/ref id] naming no section, sub-heading or
                          skeleton page
     :doc/fig-ref-undefined  a [:doc/fig-ref id] naming a figure no
                          numbered page places
     :doc/intro-dangling  a paragraph ending in a colon that is the last
                          block of its page or container, or is followed
                          by running text — it introduces nothing
     :doc/fig-twice       a figure id used by more than one :doc/fig
     :doc/duplicate-term  two glossary entries answer to the same spelling
     :doc/term-undefined  a :doc/term with no glossary entry
     :doc/term-unused     a glossary entry the document never uses (a use
                          inside another entry's definition does not count)

   The figure rules need the files; `press.doc/check` adds them. The page
   rules (`html-violations`) run over the rendered page as a BACKSTOP: no
   block emits raw markup and figure SVG is re-serialised from an
   allowlist, so they should never fire — they exist so that a future
   block that leaks markup is caught rather than shipped."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [press.deck :as deck]
            [press.doc.render :as render]
            [press.doc.spec :as spec]))

(set! *warn-on-reflection* true)

(def ^:private violations [:vector spec/violation])

(def skeleton
  "The skeleton parts after the cover, in reading order (the contents page
   between `:status` and `:body` is generated)."
  [:problem :status :body :open-questions :glossary :sources])

(defn skeleton-rule
  "Every skeleton part present and non-empty."
  [doc]
  (vec (for [k skeleton :when (empty? (get doc k))]
         {:rule :doc/skeleton :at (str k)
          :message (str "the skeleton part " k " is missing or empty; a document carries, in order, "
                        (str/join " → " skeleton))})))

(m/=> skeleton-rule [:=> [:cat spec/edn-map] violations])

(defn page-sections
  "Every block sequence that opens a page, as `[where blocks]`."
  [doc]
  (concat (for [k [:problem :status :open-questions :sources] :when (seq (get doc k))]
            [(str k) (vec (get doc k))])
          (for [{:keys [n section]} (render/numbered-sections doc)]
            [(str "section " n " (" (:id section) ")") (:blocks section)])))

(m/=> page-sections [:=> [:cat spec/edn-map] [:sequential [:tuple spec/ne-string [:vector vector?]]]])

(defn lead-rule
  "Each page section opens with `:doc/lead` — the plain statement of what it
   is about, before any detail."
  [doc]
  (vec (for [[where bs] (page-sections doc)
             :when (not= :doc/lead (ffirst bs))]
         {:rule :doc/lead-first :at where
          :message (str where " opens with " (ffirst bs) ", not :doc/lead — say in plain words what it is first")})))

(m/=> lead-rule [:=> [:cat spec/edn-map] violations])

(defn located-blocks
  "Every block in the document with where it is — the cover and the
   how-to-read slot included, and the children of notes, items and keep
   blocks."
  [doc]
  (letfn [(expand [[at b]]
            (cons [at b] (mapcat expand (map-indexed (fn [j c] [(str at " > " (inc j)) c])
                                                     (render/block-children b)))))]
    (mapcat (fn [[where bs]] (mapcat expand (map-indexed (fn [i b] [(str where " block " (inc i)) b]) bs)))
            (concat [[":cover" (vec (:cover doc))] [":how-to-read" (vec (:how-to-read doc))]]
                    (page-sections doc)))))

(m/=> located-blocks [:=> [:cat spec/edn-map] [:sequential [:tuple spec/ne-string vector?]]])

(defn- cols-problem
  "Why a table's `:cols` is wrong, or nil (each width is a positive integer
   by schema)."
  [{:keys [cols head]}]
  (cond (nil? cols) "has no :cols — give every column a width in percent"
        (not= (count cols) (count head)) (str "has " (count cols) " widths for " (count head) " columns")
        (not= 100 (reduce + cols)) (str "widths sum to " (reduce + cols) ", not 100")
        :else nil))

(m/=> cols-problem [:=> [:cat spec/edn-map] [:maybe spec/ne-string]])

(defn table-rules
  "Column widths, alignments and row shapes of every table."
  [doc]
  (vec (for [[at [tag t]] (located-blocks doc)
             :when (= tag :doc/table)
             :let [n (count (:head t))]
             v (concat (when-let [p (cols-problem t)] [{:rule :doc/table-cols :at at :message (str "table " p)}])
                       (when (and (:align t) (not= (count (:align t)) n))
                         [{:rule :doc/table-align :at at
                           :message (str "table has " (count (:align t)) " alignments for " n " columns")}])
                       (for [[i r] (map-indexed vector (:rows t)) :when (not= (count r) n)]
                         {:rule :doc/table-ragged :at at
                          :message (str "table row " (inc i) " has " (count r) " cells for " n " columns")}))]
         v)))

(m/=> table-rules [:=> [:cat spec/edn-map] violations])

(defn id-rule
  "Ids are unique across sections, sub-headings and skeleton pages.

   They are the page's anchors and what a reference names; a skeleton
   page's own name counts as taken."
  [doc]
  (vec (for [[id n] (frequencies (map first (render/ref-entries doc))) :when (> n 1)]
         {:rule :doc/duplicate-id :at (str "id " id)
          :message (str "the id " id " is used " n " times among the sections, the sub-headings and the "
                        "skeleton page names (" (str/join " " spec/page-ids) ") — give each its own")})))

(m/=> id-rule [:=> [:cat spec/edn-map] violations])

(defn subhead-rule
  "A sub-heading with an `:id` prints something when referenced.

   That is a number (it sits in a numbered section) or its `:label`."
  [doc]
  (vec (for [[id {:keys [text]}] (render/ref-entries doc) :when (nil? text)]
         {:rule :doc/subhead-unnumbered :at (str "sub-heading " id)
          :message (str "the sub-heading with :id " id " is on a page without section numbers and has no "
                        ":label — give it a :label, or drop the :id")})))

(m/=> subhead-rule [:=> [:cat spec/edn-map] violations])

(defn- tagged-in
  "Every `[tag x]` second element inside `x`."
  [tag x]
  (for [n (tree-seq coll? seq x)
        :when (and (vector? n) (= tag (first n)))]
    (second n)))

(m/=> tagged-in [:=> [:cat :keyword spec/anything] [:sequential spec/anything]])

(defn ref-rule
  "Every `[:doc/ref id]` names something that exists.

   That is a numbered section, a sub-heading with that `:id`, or a skeleton
   page."
  [doc]
  (let [ids (set (map first (render/ref-entries doc)))]
    (vec (for [r (distinct (tagged-in :doc/ref doc)) :when (not (ids r))]
           {:rule :doc/ref-undefined :at (str "ref " r)
            :message (str "[:doc/ref \"" r "\"] names nothing — use a section :id, a sub-heading :id, or one of "
                          (str/join " " spec/page-ids))}))))

(m/=> ref-rule [:=> [:cat spec/edn-map] violations])

(defn fig-ref-rule
  "Every `[:doc/fig-ref id]` names a figure a numbered page places."
  [doc]
  (let [placed (render/fig-numbers doc)]
    (vec (for [r (distinct (tagged-in :doc/fig-ref doc)) :when (not (contains? placed r))]
           {:rule :doc/fig-ref-undefined :at (str "fig-ref " r)
            :message (str "[:doc/fig-ref \"" r "\"] names a figure no page places with a number — place it with "
                          "[:doc/fig \"" r "\" …] outside the cover")}))))

(m/=> fig-ref-rule [:=> [:cat spec/edn-map] violations])

(defn- block-runs
  "Every block sequence in the document — each page's and each container's
   — as `[where blocks]`."
  [doc]
  (letfn [(nested [[where bs]]
            (cons [where bs]
                  (mapcat nested (for [[i b] (map-indexed vector bs)
                                       :let [kids (render/block-children b)] :when kids]
                                   [(str where " block " (inc i)) (vec kids)]))))]
    (mapcat nested (concat [[":cover" (vec (:cover doc))] [":how-to-read" (vec (:how-to-read doc))]]
                           (page-sections doc)))))

(m/=> block-runs [:=> [:cat spec/edn-map] [:sequential [:tuple spec/ne-string [:vector vector?]]]])

(defn intro-rule
  "An introducing line is followed by the block it introduces.

   An introducing line is a paragraph ending in a colon. What follows it is
   a list, a table, a figure, a note, an item or a keep block — never
   running text, and never nothing."
  [doc]
  (vec (for [[where bs] (block-runs doc)
             [i b nxt] (map vector (range) bs (concat (rest bs) [nil]))
             :when (and (render/intro? b) (or (nil? nxt) (render/prose-tags (first nxt))))]
         {:rule :doc/intro-dangling :at (str where " block " (inc i))
          :message (str where " block " (inc i) " ends in a colon but "
                        (if nxt "running text follows it" "nothing follows it")
                        " — put the list, table or box it introduces directly after it, or end it with a full stop")})))

(m/=> intro-rule [:=> [:cat spec/edn-map] violations])

(defn fig-refs
  "Every figure id a `:doc/fig` block names, in reading order, with where."
  [doc]
  (vec (for [[at [tag id]] (located-blocks doc) :when (= tag :doc/fig)] [at id])))

(m/=> fig-refs [:=> [:cat spec/edn-map] [:vector [:tuple spec/ne-string spec/ne-string]]])

(defn fig-once-rule
  "A figure id is referenced once: a figure is one numbered thing in one
   place, and its anchor must be unique."
  [doc]
  (vec (for [[id n] (frequencies (map second (fig-refs doc))) :when (> n 1)]
         {:rule :doc/fig-twice :at (str "figure " id)
          :message (str "figure " id " is placed " n " times — place it once and refer to it in words")})))

(m/=> fig-once-rule [:=> [:cat spec/edn-map] violations])

(defn glossary-rules
  "Every :doc/term has an entry, every entry is used outside the glossary,
   and no two entries answer to one spelling (anchors are an injective
   encoding of the term, so distinct spellings never share one)."
  [doc]
  (let [entries (:glossary doc)
        index (render/gloss-index entries)
        spellings (frequencies (mapcat render/gloss-keys entries))
        outside (tagged-in :doc/term (dissoc doc :glossary))
        used (set (map render/norm-term outside))
        all-uses (concat outside (tagged-in :doc/term (map :def entries)))]
    (vec (concat
          (for [[k n] spellings :when (> n 1)]
            {:rule :doc/duplicate-term :at ":glossary" :message (str "two glossary entries answer to \"" k "\"")})
          (for [t (distinct all-uses) :when (not (contains? index (render/norm-term t)))]
            {:rule :doc/term-undefined :at (str "term " t)
             :message (str "[:doc/term \"" t "\"] has no glossary entry — add one to :glossary")})
          (for [e entries :when (not-any? used (render/gloss-keys e))]
            {:rule :doc/term-unused :at (str "glossary " (:term e))
             :message (str "glossary entry \"" (:term e) "\" is never used as a [:doc/term] in the document — use it or drop it")})))))

(m/=> glossary-rules [:=> [:cat spec/edn-map] violations])

(defn data-violations
  "Every data-level rule over a schema-valid document."
  [doc]
  (vec (concat (skeleton-rule doc) (lead-rule doc) (table-rules doc) (id-rule doc) (subhead-rule doc)
               (ref-rule doc) (fig-ref-rule doc) (intro-rule doc) (fig-once-rule doc) (glossary-rules doc))))

(m/=> data-violations [:=> [:cat spec/edn-map] violations])

;; ------------------------------------------------------------- backstop

(def ^:private placeholder-re
  #"\{\{[^{}]*\}\}|@@[A-Z0-9_]+@@")

(def ^:private page-hazards
  "Markup no document page carries, each with what it is. Matched inside
   TAGS only: text is escaped by the renderer, so a `<` in prose can never
   open one."
  [[#"(?i)<(script|iframe|object|embed|link|base|form|frame|frameset|applet|portal)\b"
    "an element that loads or runs something"]
   [#"(?i)<meta\b[^>]*http-equiv" "a <meta http-equiv> (a refresh or a policy)"]
   [#"(?i)<[a-z][^>]*\son[a-z]+\s*=" "an on* event-handler attribute"]
   [#"(?i)<[a-z][^>]*javascript:" "a javascript: URL"]
   [#"(?i)<[a-z][^>]*\ssrcdoc\s*=" "an srcdoc attribute"]
   [#"(?i)<[a-z][^>]*image-set\s*\(|<style\b[^>]*>[^<]*image-set\s*\(" "a CSS image-set()"]
   [#"(?i)<[a-z][^>]*\s(?:src|href|data|poster|srcset|action|formaction)\s*=\s*(?![\"'#])[^\s>]+"
    "an unquoted source attribute"]])

(defn html-violations
  "The backstop over the rendered page: every external reference and
   `@import` `press.deck/check` finds, every `page-hazards` match (scripts,
   frames, objects, refresh metas, event handlers, `javascript:` URLs,
   `srcdoc`, `image-set()`, unquoted sources) is one `:doc/external`; every
   unfilled `{{…}}` / `@@…@@` placeholder is one `:doc/placeholder`.
   deck's network-token scan is NOT used: it reads prose too, and a
   sentence saying \"the import (and export)\" would trip it."
  [html]
  (let [v (deck/check html)]
    (vec (concat
          (for [x (concat (:external v) (:imports v))]
            {:rule :doc/external :at "page" :message (str "the page reaches outside itself: " x)})
          (for [[re what] page-hazards :when (re-find re html)]
            {:rule :doc/external :at "page" :message (str "the page carries " what)})
          (for [p (distinct (re-seq placeholder-re html))]
            {:rule :doc/placeholder :at "page" :message (str "unfilled placeholder " p)})))))

(m/=> html-violations [:=> [:cat spec/any-string] violations])
