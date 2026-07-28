(ns devcards.docindex
  "The render-free CONSUMER half of the generated doc tree: unit manifests in,
   Markdown pages + the index out.

   THE SEAM. A doc UNIT is one record — a page and/or a contribution to the
   index — carried as a plain map. `devcards.docs` is the EMITTER and keeps
   every privilege: the descriptor set, the corpus spec, the composition
   inventory, the conventions manifest, GraalWasm and the JPEGs it renders.
   Everything downstream of a render crosses this seam as finished data,
   because none of it is expressible as a reference this half could resolve.
   Read this namespace for what it does NOT contain: no class name, no list of
   classes, no count of them. A class reaches the index by being IN THE SET,
   and its position, heading and contribution are values its own manifest
   carries.

   WHY THIS EXISTS. The index used to restate the same hand-kept list of
   classes in five places — the emitted sections, the output directories, the
   slug literals, the preview map, and a page count written as
   `registry + 3`. Only one of the five was visible in the output, so a new
   class took five coordinated edits and a missed one shipped green. The five
   collapse to one: a class is a manifest.

   THE MANIFESTS ARE AN IN-MEMORY INTERMEDIATE, DELIBERATELY. They are built
   and consumed inside the single `gallery` run and never written to disk. Two
   reasons, and the second is measured. First, `devcards.core`'s pipeline has
   ONE CLI; a persisted intermediate wants a second arm to mint it. Second, a
   TRACKED generated intermediate sitting between two other generated
   artifacts is a staleness surface with no oracle: when the proof-of-concept
   this was lifted from froze its manifest set, the corpus gained two cards and
   its own byte-compare went red — and nothing in the machinery could
   distinguish `the generator is broken` from `the committed intermediate is
   stale`. In memory there is no such state to go stale.

   WHAT THAT COSTS, SAID OUT LOUD. The proof-of-concept probed existence with a
   directory glob, so a unit class existed iff its file existed. Here existence
   is `the emitter put a manifest in the vector`, which a source diff shows and
   no gate enforces. Three witnesses cover most of it: the closed-set
   arithmetic below catches a dropped member of a proven-total set, and the
   two-way disk audit in `devcards.docgen` catches a dropped PAGE-OWNING class
   as an orphaned tracked artifact. Neither can see a dropped PAGE-LESS unit —
   it owns no file to orphan — so for that one class the only signal is the
   committed index page changing under CI's freshness diff, which is a review
   signal and not a gate. That blind spot is unchanged from before the split,
   not introduced by it; closing it needs a witness for `which projections
   ought to exist`, and there is no such fact today.

   Every resolution gap THROWS with a lowercase sentence naming the gap.
   Nothing degrades to a default and nothing renders as nothing."
  (:require [clojure.string :as str]
            [devcards.docgen :as docgen]))

(set! *warn-on-reflection* true)

(def manifest-version
  "The only `:manifest/version` this consumer implements. A manifest declaring
   anything else THROWS — a future manifest is never read loosely."
  1)

(def block-kinds
  "The CLOSED block vocabulary. Closed, not an open registry: an unknown kind
   must fail the same way on every classpath in the fleet. A `case` with a
   throwing default is total; a multimethod's totality is a property of what
   happens to be loaded, and two stacks required to agree byte-for-byte must
   not be able to disagree about whether the same manifests are valid. A sixth
   kind is a deliberate change to BOTH halves of the seam, never a local
   extension."
  #{:prose :bullets :table :grid :image})

;; ── Blocks ──────────────────────────────────────────────────────────────
(defn validate-block!
  "Refuse a block this renderer could only render as nothing. `where` names the
   owning unit. Checks the kind is one of the closed five, that the kind's own
   body keys are present, and — for a `:grid` — that every row carries an image
   for every DECLARED render set: a row missing one still renders its caption,
   so the grid would emit an empty image target and the page would read as
   complete."
  [where block]
  (let [kind (:section/kind block)]
    (when-not (contains? block-kinds kind)
      (throw (ex-info "block declares a kind outside the closed vocabulary"
                      {:unit where :kind kind :known block-kinds})))
    (when (and (contains? block :section/heading)
               (some? (:section/heading block))
               (str/blank? (str (:section/heading block))))
      (throw (ex-info "block carries a blank heading" {:unit where :block block})))
    (case kind
      :prose (when (str/blank? (str (:prose/text block)))
               (throw (ex-info "prose block carries no text" {:unit where :block block})))
      :bullets (when (empty? (:bullets/items block))
                 (throw (ex-info "bullets block carries no items"
                                 {:unit where :block block})))
      :table (do (when (empty? (:table/headers block))
                   (throw (ex-info "table block carries no headers"
                                   {:unit where :block block})))
                 (when (empty? (:table/rows block))
                   (throw (ex-info "table block carries no rows"
                                   {:unit where :block block}))))
      :grid (let [sets (:grid/render-sets block)
                  rows (:grid/rows block)]
              (when (empty? sets)
                (throw (ex-info "grid block declares no render sets"
                                {:unit where :block block})))
              (when (empty? rows)
                (throw (ex-info "grid block carries no rows" {:unit where :block block})))
              (doseq [row rows
                      s sets]
                (when (str/blank? (str (get (:imgs row) (:file-suffix s))))
                  (throw (ex-info "grid row has no image for a declared render set"
                                  {:unit where :label (:label row)
                                   :render-set (:file-suffix s)})))))
      :image (do (when (str/blank? (str (:image/src block)))
                   (throw (ex-info "image block has no src" {:unit where :block block})))
                 (when (str/blank? (str (:image/alt block)))
                   (throw (ex-info "image block has no alt text"
                                   {:unit where :block block})))))
    block))

(defn block-md
  "One block as Markdown: its optional `## ` heading, then its body. Bodies are
   delegated to `devcards.docgen` unchanged, which buys a SECOND independent
   check — that namespace's blank-header and row-width refusals fire on this
   side of the seam too, so the emitter cannot hand a malformed table across
   and have it cheerfully written out.

   Every namespaced key is bound to an EXPLICITLY distinct local rather than
   through `{:keys [...]}`. That is not style: `{:keys [:table/rows
   :grid/rows]}` binds ONE local named `rows` and the later key wins, so every
   table would render header-only while still looking structurally right — a
   heading, a header row, a separator, and no data. Same class as the
   clojure.core shadowing hazard `.claude/rules/lint-gates.md` flags, and
   clj-kondo stays green straight through it."
  ^String [block]
  (let [kind (:section/kind block)
        heading (:section/heading block)
        body (case kind
               :prose (:prose/text block)
               :bullets (str/join "\n" (map #(str "- " %) (:bullets/items block)))
               :table (docgen/md-table (:table/headers block) (:table/rows block))
               :grid (docgen/image-grid-md (:grid/caption-header block)
                                           (:grid/render-sets block)
                                           (:grid/rows block))
               :image (let [img (str "![" (:image/alt block) "](" (:image/src block) ")")]
                        (if-some [href (:image/href block)]
                          (str "[" img "](" href ")")
                          img))
               (throw (ex-info "block declares a kind outside the closed vocabulary"
                               {:kind kind :known block-kinds})))]
    (if heading (str "## " heading "\n\n" body) body)))

(defn blocks-md
  "An ordered block vector as one body — blank-line separated. Prose that sits
   before or after a body is its OWN headingless block, which is why there is
   no lede slot and no tail slot: a headed prose block followed by a
   headingless bullets block IS a headed list, and a headed table followed by a
   headingless prose block IS a table with a footnote."
  ^String [blocks]
  (str/join "\n\n" (map block-md blocks)))

;; ── Single-manifest refusals ────────────────────────────────────────────
(defn- validate-links!
  "Every cross-link needs both halves — a footer entry with an empty href
   renders as a live-looking dead link."
  [where links]
  (doseq [l links]
    (when (or (str/blank? (str (:link/text l))) (str/blank? (str (:link/href l))))
      (throw (ex-info "cross-link is missing its text or its href"
                      {:unit where :link l})))))

(defn- order-range?
  "A closed interval in the one global `:unit/order` space."
  [value]
  (and (vector? value)
       (= 2 (count value))
       (every? integer? value)
       (<= (first value) (second value))))

(defn validate-manifest!
  "Every refusal a SINGLE manifest owes, independent of the set it lives in
   (the set-level refusals — ordering, reachability, groups, closed sets —
   are below). Returns the manifest, so it composes.

   The load-bearing pair: `:unit/page?` is EXPLICIT and cross-checked against
   the presence of `:page/sections`, because a unit that meant to have a page
   and lost its body must not pass silently as a page-less unit; and
   `:unit/index?` marks the ONE unit whose body is folded out of every other
   manifest rather than authored, so it must carry none of its own."
  [manifest]
  (let [id (:unit/id manifest)
        page? (:unit/page? manifest)
        index? (boolean (:unit/index? manifest))
        sections (:page/sections manifest)]
    (when-not (= manifest-version (:manifest/version manifest))
      (throw (ex-info "manifest declares a version this consumer does not implement"
                      {:unit id :declared (:manifest/version manifest)
                       :implemented manifest-version})))
    (when (str/blank? (str id))
      (throw (ex-info "manifest has no :unit/id" {:manifest manifest})))
    (when-not (integer? (:unit/order manifest))
      (throw (ex-info "manifest has no integer :unit/order"
                      {:unit id :order (:unit/order manifest)})))
    (when-not (contains? manifest :unit/page?)
      (throw (ex-info "manifest does not declare :unit/page?" {:unit id})))
    (doseq [k [:provenance/regen-cmd :provenance/sources]]
      (when (str/blank? (str (get manifest k)))
        (throw (ex-info "manifest carries no provenance for its DO-NOT-EDIT banner"
                        {:unit id :key k}))))
    (cond
      index?
      (do (when-not page?
            (throw (ex-info "the index unit must declare :unit/page? true" {:unit id})))
          (when (seq sections)
            (throw (ex-info "the index unit must not author :page/sections — they are folded"
                            {:unit id})))
          (when (or (contains? manifest :index/group) (seq (:index/blocks manifest)))
            (throw (ex-info "the index unit must not contribute to itself" {:unit id}))))

      page?
      (do (when (str/blank? (str (:page/title manifest)))
            (throw (ex-info "unit has a page but no :page/title" {:unit id})))
          (when (str/blank? (str (:page/lede manifest)))
            (throw (ex-info "unit has a page but no :page/lede" {:unit id})))
          (when (empty? sections)
            (throw (ex-info "unit declares a page but authors no :page/sections"
                            {:unit id}))))

      :else
      (when (or (seq sections) (contains? manifest :page/title))
        (throw (ex-info "unit declares no page but authors page content" {:unit id}))))
    (validate-links! id (:page/cross-links manifest))
    (run! #(validate-block! id %) sections)
    (run! #(validate-block! id %) (:index/blocks manifest))
    (when-some [cs (:provenance/closed-set manifest)]
      (when-not (and (string? (:set/id cs))
                     (integer? (:set/size cs))
                     (integer? (:set/index cs)))
        (throw (ex-info "closed-set stamp is malformed" {:unit id :closed-set cs}))))
    manifest))

;; ── The page frame — the ONE layout rule, index included ────────────────
(defn page-frame-md
  "The frame every generated page shares: the DO-NOT-EDIT banner, the single
   H1, the lede, the body, and the `---` cross-link footer.

   `body` is passed in rather than read off the manifest for exactly one
   reason: an ordinary page's body is its own `:page/sections`, while the
   index's body is FOLDED out of every manifest's `:index/blocks` and grouped.
   Both go through this one function, so there is no second layout rule and the
   index cannot drift from the pages it indexes.

   The footer separator lives here, so `---` and the middot have one home. A
   unit with no cross-links simply ends after its body."
  ^String [manifest ^String body]
  (str (docgen/do-not-edit-header (:provenance/regen-cmd manifest)
                                  (:provenance/sources manifest))
       "# " (:page/title manifest) "\n\n"
       (:page/lede manifest) "\n\n"
       body
       (if-some [links (seq (:page/cross-links manifest))]
         (str "\n\n---\nCross-links: "
              (str/join " &middot; "
                        (map #(str "[" (:link/text %) "](" (:link/href %) ")") links))
              "\n")
         "\n")))

(defn page-md
  "One unit's page. Validates first — a page is never half-rendered from a
   manifest already refused. The index unit is REFUSED here on purpose: its
   body does not exist until the whole set is folded, so rendering it from its
   own manifest alone would silently produce a page with an empty body."
  ^String [manifest]
  (validate-manifest! manifest)
  (when (:unit/index? manifest)
    (throw (ex-info "the index page is rendered from the whole manifest set"
                    {:unit (:unit/id manifest)})))
  (when-not (:unit/page? manifest)
    (throw (ex-info "unit declares no page" {:unit (:unit/id manifest)})))
  (page-frame-md manifest (blocks-md (:page/sections manifest))))

;; ── Set-level refusals ──────────────────────────────────────────────────
(defn- ordered
  "Every manifest validated and sorted by the one global `:unit/order`. A
   duplicate id throws before a duplicate order does: two units sharing an id
   name one output directory, and the second page write would silently win."
  [manifests]
  (run! validate-manifest! manifests)
  (let [collide (fn [k]
                  (->> manifests
                       (group-by k)
                       (filter #(< 1 (count (val %))))
                       (mapv (fn [[v ms]] [v (mapv :unit/id ms)]))))]
    (when-some [dupes (seq (collide :unit/id))]
      (throw (ex-info "two units claim one :unit/id" {:collisions (vec dupes)})))
    (when-some [dupes (seq (collide :unit/order))]
      (throw (ex-info "two units claim one :unit/order" {:collisions (vec dupes)}))))
  (vec (sort-by :unit/order manifests)))

(defn index-unit
  "The ONE manifest declaring `:unit/index? true`. Zero throws (a headless tree
   renders no index at all); two throw, because a renderer that picks is a
   renderer that hides a conflict."
  [manifests]
  (let [idx (filterv :unit/index? manifests)]
    (when-not (= 1 (count idx))
      (throw (ex-info "the manifest set must declare exactly one :unit/index? unit"
                      {:declared (mapv :unit/id idx)})))
    (first idx)))

(defn- validate-reachable!
  "EVERY unit but the index must actually reach the index, and reaching it
   takes both halves: a `:index/group` to be placed in, and at least one
   `:index/blocks` entry to place. The index unit is exempt — it IS the index.

   Both halves are load-bearing and each was a hole. Checking only page-BEARING
   units let a PAGE-LESS unit declare no group and be dropped in silence,
   contributing nothing anywhere while passing every check — the exact
   invisibility this architecture exists to delete, one level down from the
   hand-kept list it replaced. And checking only the GROUP let a unit join a
   populated group with an empty `:index/blocks`: the group still renders
   because its other members carry blocks, so nothing links the joiner's page
   and nothing lists it.

   Necessary, not sufficient: this proves a unit contributes SOMETHING to the
   index, never that what it contributes links its own page. No value here can
   see that, because a block's body is finished Markdown by the time it
   crosses the seam."
  [manifests]
  (doseq [m manifests
          :when (not (:unit/index? m))]
    (when (nil? (:index/group m))
      (throw (ex-info "unit reaches the index through no group"
                      {:unit (:unit/id m)})))
    (when (empty? (:index/blocks m))
      (throw (ex-info "unit joins a group but contributes no index blocks"
                      {:unit (:unit/id m) :group (:index/group m)})))
    (when-not (order-range? (:index/order-range m))
      (throw (ex-info "unit joins a group without a valid :index/order-range"
                      {:unit (:unit/id m)
                       :group (:index/group m)
                       :range (:index/order-range m)})))))

(defn- validate-closed-sets!
  "A set of units may CLAIM to be closed and total. Deriving an index from
   whatever exists is exactly what makes a missing member of such a set
   invisible: drop one and the table renders a row shorter and looks perfect.
   The emitter walks an enum and throws on any gap; this half has no enum, so
   the claim crosses the seam as `:provenance/closed-set` and is checked
   arithmetically. Omitting the stamp is how a unit class stays free to come
   and go — that asymmetry IS the design.

   Four checks, and each catches something the others do not:
   (a) every claimant of one `:set/id` agrees on `:set/size`;
   (b) exactly `:set/size` of them are present;
   (c) their `:set/index` values are exactly `(range size)` — not redundant
       with (b), which a deleted member replaced by an unrelated new one would
       satisfy while hiding the gap;
   (d) sorting the claimants by `:unit/order` yields `:set/index` ASCENDING.
       Without (d) the set proves only that all members are present, never
       that they are PRESENTED in their own declared order: `:unit/order` is a
       free global integer, so two members could swap it and (a)-(c) would all
       still pass — a permutation of the rows is exactly what (c) accepts —
       while the rendered table silently reordered."
  [manifests]
  (doseq [[set-id members] (group-by #(get-in % [:provenance/closed-set :set/id])
                                     (filter :provenance/closed-set manifests))]
    (let [sizes (distinct (map #(get-in % [:provenance/closed-set :set/size]) members))]
      (when (< 1 (count sizes))
        (throw (ex-info "closed set claimants disagree on its size"
                        {:set set-id :sizes (vec sizes)
                         :claimants (mapv :unit/id members)})))
      (let [size (first sizes)
            index-of #(get-in % [:provenance/closed-set :set/index])
            indices (sort (map index-of members))
            by-order (sort-by :unit/order members)]
        (when (not= size (count members))
          (throw (ex-info "closed set is not closed"
                          {:set set-id :declared-size size :present (count members)
                           :claimants (mapv :unit/id members)})))
        (when (not= (seq indices) (seq (range size)))
          (throw (ex-info "closed set indices are not exactly (range size)"
                          {:set set-id :declared-size size :indices (vec indices)})))
        (when (not= (mapv index-of by-order) (vec (range size)))
          (throw (ex-info "closed set members are ordered against their own indices"
                          {:set set-id
                           :by-order (mapv (juxt :unit/id :unit/order index-of)
                                           by-order)})))))))

;; ── Groups ──────────────────────────────────────────────────────────────
(defn- group-heading
  "A group's heading under repeat-and-agree. The key must be PRESENT on every
   member — an absent heading is an oversight, an explicit nil is a claim that
   the group opens bare, and the two must never be confused. The copies are
   emitter output, so they cannot drift in practice; the check is what makes a
   copy a CHECKED copy."
  [group-id members]
  (doseq [m members]
    (when-not (contains? m :index/heading)
      (throw (ex-info "unit joins a group without declaring its :index/heading"
                      {:unit (:unit/id m) :group group-id}))))
  (let [headings (distinct (map :index/heading members))]
    (when (< 1 (count headings))
      (throw (ex-info "group members disagree about the group heading"
                      {:group group-id
                       :declared (mapv (juxt :unit/id :index/heading) members)})))
    (first headings)))

(defn- group-order-range
  "Return a group's repeated-and-agreed exact span in global unit order.

   Contiguity says members with one group id form one run, but it does not say
   where that run belongs. Repeating the range on every member makes position a
   checked declaration without introducing a separate group registry."
  [group-id members]
  (let [ranges (distinct (map :index/order-range members))]
    (when (< 1 (count ranges))
      (throw (ex-info "group members disagree about the group order range"
                      {:group group-id
                       :declared (mapv (juxt :unit/id :index/order-range) members)})))
    (let [declared (first ranges)
          orders (mapv :unit/order members)
          actual [(apply min orders) (apply max orders)]]
      (doseq [member members
              :let [order (:unit/order member)]]
        (when-not (<= (first declared) order (second declared))
          (throw (ex-info "unit order falls outside its group's declared :index/order-range"
                          {:unit (:unit/id member)
                           :group group-id
                           :order order
                           :range declared}))))
      (when-not (= declared actual)
        (throw (ex-info "group order range is not its exact member span"
                        {:group group-id
                         :declared declared
                         :actual actual
                         :members (mapv :unit/id members)})))
      declared)))

(defn- fold-run
  "Fold one maximal run of adjacent blocks sharing a non-nil `:section/merge`
   into a single block. Kind, heading and the kind's own frame must agree — two
   tables that disagree about their headers are two tables, and silently
   keeping one set would publish rows under the wrong columns."
  [group-id run]
  (let [kinds (distinct (map :section/kind run))
        headings (distinct (map :section/heading run))
        kind (first kinds)]
    (when (< 1 (count kinds))
      (throw (ex-info "blocks sharing a merge key disagree about their kind"
                      {:group group-id :merge (:section/merge (first run))
                       :kinds (vec kinds)})))
    (when (< 1 (count headings))
      (throw (ex-info "blocks sharing a merge key disagree about their heading"
                      {:group group-id :merge (:section/merge (first run))
                       :headings (vec headings)})))
    (case kind
      :table (let [headers (distinct (map :table/headers run))]
               (when (< 1 (count headers))
                 (throw (ex-info "merged tables disagree about their headers"
                                 {:group group-id :headers (vec headers)})))
               (assoc (first run) :table/rows (vec (mapcat :table/rows run))))
      :grid (let [sets (distinct (map :grid/render-sets run))]
              (when (< 1 (count sets))
                (throw (ex-info "merged grids disagree about their render sets"
                                {:group group-id :render-sets (vec sets)})))
              (assoc (first run) :grid/rows (vec (mapcat :grid/rows run))))
      (throw (ex-info "block kind refuses to merge"
                      {:group group-id :kind kind
                       :merge (:section/merge (first run))})))))

(defn- merged-blocks
  "A group's members' `:index/blocks`, concatenated in (unit order, declaration
   position), with each maximal run of a shared non-nil `:section/merge`
   folded. MERGE IS DECLARED, NEVER INFERRED: inferring the fold from shape
   adjacency would silently absorb a new class's table into another class's. A
   merge key spanning two runs throws — a fold that skipped over an intervening
   block would reorder the page."
  [group-id members]
  (let [blocks (vec (mapcat :index/blocks members))
        runs (partition-by :section/merge blocks)
        run-keys (remove nil? (map #(:section/merge (first %)) runs))
        split (->> run-keys frequencies (filter #(< 1 (val %))) (mapv key))]
    (when (seq split)
      (throw (ex-info "merge run is not contiguous" {:group group-id :merge-keys split})))
    (vec (mapcat (fn [run]
                   (if (nil? (:section/merge (first run)))
                     run
                     [(fold-run group-id run)]))
                 runs))))

(defn groups
  "Every index group, in order: CONTIGUOUS RUNS of equal `:index/group` over
   the ordered manifests. Two runs sharing an id throw. That is the whole
   grouping mechanism — no group registry to keep in step, because a registry
   is the hand-maintained list this architecture exists to delete, one level
   up. Returns `[{:group/id :group/heading :group/blocks :group/members}]` with
   each group's blocks already folded."
  [ordered-manifests]
  (let [members (filterv :index/group ordered-manifests)
        runs (partition-by :index/group members)
        ids (map #(:index/group (first %)) runs)
        split (->> ids frequencies (filter #(< 1 (val %))) (mapv key))]
    (when (seq split)
      (throw (ex-info "group members are not contiguous in :unit/order"
                      {:groups split})))
    (mapv (fn [run]
            (let [id (:index/group (first run))]
              {:group/id id
               :group/heading (group-heading id run)
               :group/order-range (group-order-range id run)
               :group/blocks (merged-blocks id run)
               :group/members (vec run)}))
          runs)))

(defn group-md
  "One group as Markdown: its optional `## ` heading, then its folded blocks.
   Heading and blocks are independently optional slots and that is the whole
   layout engine — every section of the committed index falls out of this one
   expression, with no per-group layout switch.

   A group with zero blocks THROWS: a heading over nothing is a section that
   claims content it does not have."
  ^String [group]
  (let [blocks (:group/blocks group)
        heading (:group/heading group)]
    (when (empty? blocks)
      (throw (ex-info "index group contributes no blocks" {:group (:group/id group)})))
    (str/join "\n\n" (cond->> (map block-md blocks)
                       (some? heading) (cons (str "## " heading))))))

;; ── The index page ──────────────────────────────────────────────────────
(defn index-md
  "The index page, rendered through the SAME `page-frame-md` every other page
   uses — only its body comes from the fold instead of from its own
   `:page/sections`.

   Determinism is total by construction: `:unit/order` (ties throw) orders
   pages, groups and rows, and declaration position orders a unit's own blocks.
   No map iteration order reaches any output."
  ^String [manifests]
  (let [ms (ordered manifests)]
    (validate-reachable! ms)
    (validate-closed-sets! ms)
    (page-frame-md (index-unit ms)
                   (str/join "\n\n" (map group-md (groups ms))))))

(defn page-count
  "How many pages this manifest set writes, the index included. Retires a page
   count written as `registry + 3`, whose literal was counted from memory."
  [manifests]
  (count (filter :unit/page? manifests)))

(defn page-path
  "Where a unit's page is written, relative to a doc-tree root. The index sits
   at the root; every other page owns the directory its images are colocated
   in. This is the only place the index's position is special, and it keys off
   `:unit/index?` — a first-class flag, not a name."
  ^String [^String root manifest]
  (if (:unit/index? manifest)
    (str root "/README.md")
    (str root "/" (:unit/id manifest) "/README.md")))

(defn pages
  "Every page this manifest set produces, as `[path text]` in manifest order
   with the index LAST. The index is RENDERED first, before any per-unit page:
   every set-level refusal — duplicate ids, duplicate orders, reachability,
   closed sets, group contiguity — therefore fires before the caller is handed
   a single page, so a tree is never half-written from a set that was about to
   be refused."
  [^String root manifests]
  (let [idx (index-unit manifests)
        idx-md (index-md manifests)]
    (conj (mapv (fn [m] [(page-path root m) (page-md m)])
                (filter #(and (:unit/page? %) (not (:unit/index? %))) manifests))
          [(page-path root idx) idx-md])))

(def ^:private image-ref
  "Every colocated markdown image reference on a rendered page."
  #"!\[[^\]]*\]\(\./([^)]+)\)")

(defn validate-image-refs!
  "Every image a rendered page points at must be a path the emitter WROTE.

   The two-way disk audit reconciles the emitter's CLAIMED paths against disk,
   which catches a write that did not land and a tracked artifact nothing
   claims. It cannot catch the third case: a page REFERENCING an artifact that
   was never written at all. Nothing claims that path, so it is not orphaned;
   nothing wrote it, so it is not missing; the page renders, the link is dead,
   and every lane is green. Read off the EMITTED BYTES and resolved against
   each page's own directory, so this sees exactly what a reader's browser will
   fetch."
  [page-pairs claimed]
  (let [written (set claimed)]
    (doseq [[^String path ^String text] page-pairs
            :let [dir (subs path 0 (str/last-index-of path "/"))]
            [_ rel] (re-seq image-ref text)]
      (let [target (str dir "/" rel)]
        (when-not (contains? written target)
          (throw (ex-info "page references an artifact the emitter did not write"
                          {:page path :artifact target})))))))
