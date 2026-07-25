(ns lvgl-codegen.expand
  "Class string parser and style expander.
   Parses Tailwind-like class strings, resolves tokens, and expands
   into 8 composite variants per LVGL state selector."
  (:require [clojure.string :as str]
            [lvgl-codegen.generated.enums :as gen-enums]
            [lvgl-codegen.schema :as schema]
            [lvgl-codegen.style-props :as style-props]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

;; -- File-local argument schemas for arrow-spec tightening --
(def ^:private class-defs-schema
  "Class-macro definitions map (tokens :class-defs): macro name -> class string."
  [:map-of keyword? string?])

(def ^:private events-schema
  "Screen events map: event name -> event definition map (shape varies by source)."
  [:map-of keyword? map?])

(def ^:private parsed-token
  "A single parsed token (output of parse-class-token or
   style-map->tokens). Open map: always carries :prop, plus either :ref
   (a token reference) or :value (a literal — int, hex/font string,
   themed/shadow map, or an emit-xform keyword like :align's), and
   optional :bp / :state breakpoint+state scopes (may be nil)."
  [:map [:prop keyword?] [:ref {:optional true} keyword?]
   [:value {:optional true} [:or int? [:string {:min 1}] :keyword [:map-of :keyword some?]]]
   [:bp {:optional true} [:maybe keyword?]] [:state {:optional true} [:maybe keyword?]]
   [:part {:optional true} [:maybe keyword?]]])

(def ^:private resolved-value
  "A resolved property value: pixel int, hex/font string, a themed/shadow
   map, or an emit-xform keyword literal (e.g. :align's :right-mid, which
   emit-proto translates); may be nil when a token does not resolve."
  [:maybe [:or int? [:string {:min 1}] :keyword [:map-of :keyword some?]]])

;; Special size value matching LVGL 9's LV_SIZE_CONTENT.
;; LV_COORD_TYPE_SHIFT=29, LV_COORD_MAX=(1<<29)-1, LV_SIZE_CONTENT=LV_COORD_SET_SPEC(LV_COORD_MAX)
(def ^:const lv-size-content 0x3FFFFFFF)

(defn lv-pct
  "Encode a percentage value for LVGL dimension properties.
   LV_PCT(x) = x | (1 << 29). Fits in both uint32 and int32."
  [pct]
  (bit-or pct (bit-shift-left 1 29)))

(def ^:private layout-directives
  "Class tokens that select a flex flow rather than a style property."
  #{"flex" "flex-col" "flex-row"})

(def ^:private exact-base-tokens
  "Whole-token shorthands that carry a fixed prop+value (value syntax, not
   registry prefixes): content sizing + flex alignment shortcuts."
  {"w-content" {:prop :width :value lv-size-content}
   "h-content" {:prop :height :value lv-size-content}
   "items-center" {:prop :flex-cross-place :value 2}
   "justify-center" {:prop :flex-main-place :value 2}})

(defn- parse-token-int
  "Parse the numeric tail of a :pct / :int prefix token; throws naming the
   offending token."
  [s token-str what]
  (or (parse-long s)
      (throw (ex-info (str "Non-numeric " what " in class token \"" token-str "\"")
                      {:token token-str}))))

(defn parse-class-token
  "Parse a single class token string into a structured map.
   Returns {:prop keyword :ref keyword-or-int :bp nil-or-keyword :state nil-or-keyword}
   or nil if the token is a layout directive (flex, flex-col, flex-row).
   Base parsing is a longest-prefix-first lookup over the style-props
   registry's prefix table."
  [token-str]
  (let [;; Extract prefix modifiers
        parts (str/split token-str #":")
        [prefixes base]
        (if (> (count parts) 1) [(butlast parts) (last parts)] [[] (first parts)])
        bp-prefix
        (some (fn [p] (when (contains? style-props/bp-min-index (keyword p)) (keyword p)))
              prefixes)
        state-prefix
        (some (fn [p] (when (contains? style-props/state-selector (keyword p)) (keyword p)))
              prefixes)
        ;; Every prefix must be a known breakpoint or state — a typo'd
        ;; prefix ("mdd:", "presed:", web-habit "hover:") would otherwise
        ;; silently apply the token always / in the default state.
        _ (doseq [p prefixes
                  :let [kw (keyword p)]]
            (when-not (or (contains? style-props/bp-min-index kw)
                          (contains? style-props/state-selector kw))
              (throw (ex-info (str "Unknown class prefix \"" p
                                   "\" in token \"" token-str
                                   "\" — breakpoints: "
                                   (str/join " " (map name (keys style-props/bp-min-index)))
                                   "; states: "
                                   (str/join " "
                                             (map name (keys style-props/state-selector))))
                              {:token token-str :prefix p}))))
        _ (when (> (count (filter #(contains? style-props/bp-min-index (keyword %))
                                  prefixes))
                   1)
            (throw (ex-info (str "Multiple breakpoint prefixes in token \"" token-str "\"")
                            {:token token-str})))]
    ;; Parse the base: layout directive → exact shorthand → registry prefix.
    (when-not (layout-directives base)
      (if-let [exact (get exact-base-tokens base)]
        (assoc exact :bp bp-prefix :state state-prefix)
        (if-let [[prop parse-kind prefix] (style-props/match-prefix base)]
          (let [tail (subs base (count prefix))
                parsed (case parse-kind
                         :ref {:prop prop :ref (keyword tail)}
                         :pct {:prop prop
                               :value (lv-pct (parse-token-int tail token-str "percent"))}
                         :int {:prop prop
                               :value (parse-token-int tail token-str (name prop))})]
            (assoc parsed :bp bp-prefix :state state-prefix))
          (throw (ex-info (str "Unknown class token: " token-str) {:token token-str})))))))

(defn expand-class-macros
  "Expand @-prefixed class macros in a class string.
   Replaces each @name token with the corresponding value from class-defs.
   Macros must not reference other macros (no nesting)."
  [class-defs class-str]
  (if (or (nil? class-str) (nil? class-defs) (not (str/includes? class-str "@")))
    class-str
    (let [tokens (str/split (str/trim class-str) #"\s+")
          expanded
          (mapcat (fn [token]
                    (if (str/starts-with? token "@")
                      (let [macro-key (keyword (subs token 1))
                            expansion (get class-defs macro-key)]
                        (when-not expansion
                          (throw (ex-info (str "Unknown class macro: " token)
                                          {:macro token :available (keys class-defs)})))
                        (when (str/includes? expansion "@")
                          (throw (ex-info (str "Nested class macros not allowed in: " token)
                                          {:macro token :expansion expansion})))
                        (str/split (str/trim expansion) #"\s+"))
                      [token]))
                  tokens)]
      (str/join " " expanded))))

(defn parse-class-string
  "Parse a class string into a sequence of parsed tokens.
   Splits on whitespace, filters layout directives, returns parsed tokens."
  [class-str]
  (when class-str
    (->> (str/split (str/trim class-str) #"\s+")
         (keep parse-class-token))))

(defn extract-layout
  "Extract layout flow from a class string. Returns :flex-row, :flex-col, or nil."
  [class-str]
  (when class-str
    (let [tokens (str/split (str/trim class-str) #"\s+")]
      (condp some tokens #{"flex-col"} :flex-col #{"flex-row" "flex"} :flex-row nil))))

(defn resolve-token-ref
  "Resolve a token ref against the resolved token set (:tokens) — the pinned
   design-tokens manifest with the private overlay merged over it: the entry
   must exist AND its :kind must equal the prop's
   registry :resolve kind — a color prop referencing a spacing token is an
   authoring error, not a coercion. Returns {:dark v :light v} (both
   concrete; equal when the token is mode-invariant)."
  [tokens prop want ref-key]
  (let [entry (get-in tokens [:tokens ref-key])]
    (when-not entry
      (throw (ex-info (str "Unknown design token: " (name ref-key)
                           " — in neither the pinned design-tokens manifest nor"
                           " the private overlay (edn/tokens_private.edn)")
                      {:prop prop :ref ref-key})))
    (when-not (= want (:kind entry))
      (throw (ex-info (str "Token kind mismatch: " (name ref-key)
                           " is a " (name (:kind entry))
                           " token but " (name prop)
                           " resolves " (name want))
                      {:prop prop :ref ref-key :want want :actual (:kind entry)})))
    (select-keys entry [:dark :light])))

(defn resolve-prop-value
  "Resolve a parsed token's ref to a concrete value (resolution kind from
   the style-props registry; literal :value entries pass through). Token
   refs resolve against the resolved token set (:tokens — the pinned
   manifest, protogen's resolver emitting both modes concrete, with the
   private overlay merged over it).
   Returns a themed map {:dark v :light v} or a bare value."
  [tokens parsed]
  (let [resolved (if (contains? parsed :value)
                   (:value parsed)
                   (let [ref-key (:ref parsed)
                         prop (:prop parsed)
                         kind (:resolve (get style-props/props prop))]
                     (case kind
                       (:color :font :spacing :radius :shadow :opacity :border-width)
                       (resolve-token-ref tokens prop kind ref-key)
                       :size (let [s (name ref-key)]
                               ;; Sizes are numeric literals (w-48); the :sizes
                               ;; token layer was deleted as a pure numeric mirror.
                               (when (re-matches #"\d+" s) (Integer/parseInt s)))
                       (throw (ex-info (str "Cannot resolve property: " prop)
                                       {:prop prop :ref ref-key})))))]
    (when (nil? resolved)
      ;; A nil here would ride the wire as a nil prop value and crash in
      ;; pronto with zero EDN context — fail at the token instead.
      (throw (ex-info (str "Class token does not resolve: " (name (:prop parsed))
                           " ref \"" (some-> (:ref parsed)
                                             name)
                           "\"" " is neither a token nor a literal")
                      {:parsed parsed})))
    resolved))

(defn- themed-value
  "Extract the value for a specific theme from a potentially themed value.
   If the value is a map with :dark/:light, picks the theme variant.
   Otherwise returns the value as-is."
  [value theme]
  (if (and (map? value) (contains? value :dark)) (get value theme) value))

(defn expand->variants
  "Expand parsed+resolved tokens into 8 composite variant property maps.
   Groups by LVGL state selector. Returns a map of
   {state-selector [{:props {prop-key value}} ...8 variants...]}."
  [tokens parsed-tokens]
  (let [;; Group tokens by [state part] — each combination is one style
        ;; group whose selector ORs the lv_state_t bits with the lv_part_t
        ;; bits (LV_PART_MAIN = 0 when no part is scoped).
        by-state (group-by (juxt :state :part) parsed-tokens)
        ;; For each state+part group, build 8 variants
        expand-state
        (fn [[state-key part-key] state-tokens]
          (let [selector (bit-or (if state-key
                                   (get style-props/state-selector
                                        state-key
                                        style-props/lv-state-default)
                                   style-props/lv-state-default)
                                 (if part-key (get style-props/part-selector part-key 0) 0))
                ;; Media-query precedence: the HIGHEST matching
                ;; tier wins regardless of class-string order
                ;; ("xl:w-160 md:w-96 w-64" at xl is 160, not
                ;; 64). Stable sort by tier puts higher tiers
                ;; later in the reduce so they override; tokens
                ;; in the SAME tier keep declaration order
                ;; (last one wins, CSS-style).
                tier-sorted (sort-by (fn [parsed]
                                       (if-let [bp (:bp parsed)]
                                         (get style-props/bp-min-index bp 0)
                                         0))
                                     state-tokens)]
            {:state-selector selector
             :variants
             (mapv (fn [idx]
                     (let [theme (if (even? idx) :light :dark)]
                       {:props
                        (reduce
                         (fn [props parsed]
                           (let [resolved (resolve-prop-value tokens parsed)
                                 bp (:bp parsed)
                                 min-idx (if bp (get style-props/bp-min-index bp 0) 0)]
                             (if (>= idx min-idx)
                               ;; This token applies at this composite index
                               (assoc props (:prop parsed) (themed-value resolved theme))
                               props)))
                         {}
                         tier-sorted)}))
                   (range 8))}))]
    (mapv (fn [[state-part state-tokens]] (expand-state state-part state-tokens))
          by-state)))

(defn- resolve-cell-align
  "Resolve a :cell alignment keyword to its lv_grid_align_t value via the
   factory-generated map."
  [kw]
  (or (gen-enums/grid-align-keyword->int kw)
      (throw (ex-info (str "Unknown :cell alignment keyword: " kw
                           ". Valid: " (sort (keys gen-enums/grid-align-keyword->int)))
                      {:value kw}))))

(defn- cell->style-props
  "Desugar a :cell grid-placement map into the grid-cell-* style props the
   raw-:style pipeline already carries (spans default to 1; aligns resolved
   to lv_grid_align_t ints)."
  [cell]
  (cond-> {:grid-cell-column-pos (:col cell)
           :grid-cell-row-pos (:row cell)
           :grid-cell-column-span (:col-span cell 1)
           :grid-cell-row-span (:row-span cell 1)}
    (:x-align cell) (assoc :grid-cell-x-align (resolve-cell-align (:x-align cell)))
    (:y-align cell) (assoc :grid-cell-y-align (resolve-cell-align (:y-align cell)))))

(defn- style-entry->token
  "Convert one [prop value] :style entry into a parsed-token map (the same
   shape `parse-class-token` produces). A keyword value on a prop with a
   registry :resolve kind is a TOKEN REF (rides the same resolution as the
   class DSL); any other value — including keywords on xform-only props
   like :align — is a literal."
  [prop value bp state part]
  (let [entry (or (get style-props/props prop)
                  (throw (ex-info (str "Unknown :style prop: "
                                       prop
                                       " — not in the style-props registry")
                                  {:prop prop})))]
    (if (and (keyword? value) (:resolve entry))
      {:prop prop :ref value :bp bp :state state :part part}
      {:prop prop :value value :bp bp :state state :part part})))

(defn style-map->tokens
  "Desugar a :style map into parsed-token maps so :style and the class DSL
   ride ONE resolution + variant-expansion stream. Nested maps under a
   breakpoint key (:sm/:md/:lg/:xl) or state key (:pressed/:focused/
   :disabled) scope their props, mirroring the DSL's `md:` / `pressed:`
   prefixes; the two axes may combine once, in either order. A widget-part
   key (:indicator/:knob/:items/…, see style-props/part-selector) scopes
   its props to that LVGL part (a leaf map — parts do not nest)."
  ([style] (style-map->tokens style nil nil nil))
  ([style bp state part]
   (vec
    (mapcat
     (fn [[k v]]
       (condp contains? k
         style-props/bp-min-index
         (do (when bp
               (throw (ex-info
                       (str "Nested breakpoint " k " inside " "breakpoint " bp " in :style")
                       {:outer bp :inner k})))
             (style-map->tokens v k state part))
         style-props/state-selector
         (do (when state
               (throw (ex-info (str "Nested state " k " inside state " state " in :style")
                               {:outer state :inner k})))
             (style-map->tokens v bp k part))
         style-props/part-selector
         (do (when part
               (throw (ex-info (str "Nested part " k " inside part " part " in :style")
                               {:outer part :inner k})))
             (style-map->tokens v bp state k))
         [(style-entry->token k v bp state part)]))
     style))))

(defn resolve-color-when
  "Resolve a :color-when binding's :color design token to a single baked
   \"#RRGGBB\". The renderer's ColorBinding carries ONE Color (no dark/light
   variant) baked into the .pb, so the token MUST be mode-invariant (dark ==
   light) — a mode-variant color cannot be represented and fails loud here. The
   :subject/:value comparison keys pass through untouched."
  [tokens color-when]
  (let [ref-key (:color color-when)
        {:keys [dark light]} (resolve-token-ref tokens :color-when :color ref-key)]
    (when-not (= dark light)
      (throw (ex-info (str ":color-when token " (name ref-key) " is mode-variant (dark "
                           dark " != light " light ") — a color_when binding bakes ONE "
                           "Color into the .pb and cannot theme; use a mode-invariant token")
                      {:ref ref-key :dark dark :light light})))
    (assoc color-when :color dark)))

(defn expand-widget
  "Expand a single widget node: parse class string, desugar :cell/:style
   into the same token stream, resolve tokens, produce style groups.
   Returns the widget with :style-groups and :layout added."
  [tokens events widget]
  (let [class-str (expand-class-macros (:class-defs tokens) (:class widget))
        parsed (parse-class-string class-str)
        layout (extract-layout class-str)
        ;; Grid placement sugar — desugars into the same token stream.
        cell-style (some-> (:cell widget)
                           cell->style-props)
        _ (when-let [overlap (and cell-style
                                  (seq (filter cell-style (keys (:style widget)))))]
            (throw (ex-info (str ":cell and :style set the same grid-cell "
                                 "prop(s): "
                                 (vec overlap)
                                 " — author one or the other")
                            {:widget-tag (:tag widget) :overlap (vec overlap)})))
        ;; :style desugars into parsed tokens AFTER the class tokens, so a
        ;; :style prop overrides a same-tier class token (declaration-order
        ;; precedence inside expand->variants).
        style-map (merge cell-style (:style widget))
        style-tokens (if (seq style-map) (style-map->tokens style-map) [])
        all-tokens (concat parsed style-tokens)
        style-groups (when (seq all-tokens) (expand->variants tokens all-tokens))
        event-key (:event widget)
        _ (when (and event-key (nil? (get events event-key)))
            (throw (ex-info
                    (str "Widget references undeclared event: " (name event-key))
                    {:event event-key :widget-tag (:tag widget) :available (keys events)})))
        resolved-event
        (when event-key (when-let [evt (get events event-key)] (assoc evt :name event-key)))
        children (mapv (partial expand-widget tokens events) (:children widget))]
    (cond-> (dissoc widget :class :event :style :cell)
      layout (assoc :layout layout)
      (seq style-groups) (assoc :style-groups style-groups)
      resolved-event (assoc :event resolved-event)
      ;; Bake the :color-when design token to a concrete "#RRGGBB" (checked_when
      ;; / enabled_when carry no token and pass through untouched above).
      (:color-when widget) (assoc :color-when (resolve-color-when tokens (:color-when widget)))
      (seq children) (assoc :children children))))

(defn expand-screen
  "Expand a full screen definition: resolve all tokens and events,
   expand all widgets recursively."
  [tokens screen]
  (let [events (:events screen)]
    (-> screen
        (assoc :tree (expand-widget tokens events (:tree screen)))
        (dissoc :events))))

;; -- Function schema registrations --
(m/=> lv-pct [:=> [:cat int?] int?])

(m/=> parse-token-int
      [:=> [:cat [:string {:min 1}] [:string {:min 1}] [:string {:min 1}]] int?])

(m/=> expand-class-macros
      [:=> [:cat [:maybe class-defs-schema] [:maybe string?]] [:maybe string?]])

(m/=> parse-class-token [:=> [:cat string?] [:maybe :map]])

(m/=> parse-class-string [:=> [:cat [:maybe string?]] [:maybe [:sequential :map]]])

(m/=> extract-layout [:=> [:cat [:maybe string?]] [:maybe keyword?]])

(m/=> resolve-cell-align [:=> [:cat :keyword] nat-int?])

(m/=> cell->style-props
      [:=> [:cat [:map [:col nat-int?] [:row nat-int?]]] [:map-of :keyword nat-int?]])

(m/=> style-entry->token
      [:=> [:cat :keyword some? [:maybe :keyword] [:maybe :keyword] [:maybe :keyword]]
       parsed-token])

(m/=> style-map->tokens
      [:function [:=> [:cat [:map-of :keyword some?]] [:vector parsed-token]]
       [:=>
        [:cat [:map-of :keyword some?] [:maybe :keyword] [:maybe :keyword]
         [:maybe :keyword]] [:vector parsed-token]]])

(m/=> resolve-token-ref
      [:=>
       [:cat schema/tokens-schema :keyword
        [:enum :color :font :spacing :radius :shadow :opacity :border-width] :keyword]
       [:map [:dark some?] [:light some?]]])

(m/=> resolve-prop-value [:=> [:cat schema/tokens-schema parsed-token] :any])

(m/=> resolve-color-when
      [:=> [:cat schema/tokens-schema [:map [:color :keyword]]]
       [:map [:color [:string {:min 1}]]]])

(m/=> expand->variants
      [:=> [:cat schema/tokens-schema [:sequential parsed-token]] [:sequential :map]])

(m/=> expand-widget [:=> [:cat schema/tokens-schema events-schema schema/widget-node] :map])

(m/=> expand-screen
      [:=>
       [:cat schema/tokens-schema
        [:map [:tree schema/widget-node] [:events {:optional true} events-schema]]] :map])

(m/=> themed-value [:=> [:cat resolved-value keyword?] :any])