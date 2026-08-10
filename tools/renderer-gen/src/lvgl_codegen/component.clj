(ns lvgl-codegen.component
  "Codegen-time component expansion.
   Components are EDN templates with typed parameters and optional slots.
   Resolution is purely compile-time — the renderer always receives
   fully-expanded LVGL widget trees. No proto changes needed.

   Widgets the default theme styles by CLASS dispatch (e.g. tabview) are
   NOT components — they ride real proto arms (:lv_tabview), because a
   composite of plain widgets can never receive those theme styles.

   Expressiveness constraints (deliberate, demand-gated — revisit only
   when a real screen needs one; docs/lvgl-factory/11-EDN-SECOND-AUDIT.md):
   - NO per-child params: one flat :params map substitutes across the
     whole template; children cannot each take their own bindings.
   - NO conditional subtrees: a template's tree shape is fixed; params
     substitute values, never the presence of a node.
   - NO multi-slot routing: every {:slot …} placeholder receives the SAME
     usage-children list; slot names do not route different content."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [lvgl-codegen.schema :as schema]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

;; -- File-local schemas for arrow-spec arg tightening --
(def ^:private props-schema
  "Resolved component props: keyword param names -> arbitrary values.
   Values are heterogeneous (strings, ints, maps, keywords) and may be nil
   (a param with no :default that is not provided). [:maybe some?] allows any
   value including nil without using the gate-banned bare :any."
  [:map-of :keyword [:maybe some?]])

(def ^:private params-decl-schema
  "Declared component :params: param name -> definition map (open beyond the
   known keys; :default values are heterogeneous)."
  [:map-of :keyword
   [:map [:type {:optional true} :keyword] [:required {:optional true} :boolean]
    [:default {:optional true} [:maybe some?]]]])

(def ^:private widget-schema
  "A widget/tree node template (EDN). Maps carry a :tag and may nest :children.
   resolve-tree also receives non-map leaves and slot maps ({:slot ...}) so the
   value shapes are intentionally loose, but every real node here is a
   keyword-keyed map."
  [:map-of :keyword [:maybe some?]])

(def ^:private component-schema
  "A loaded component definition: a template :tree plus typed :params.
   :params is optional pre-load (load-components defaults it to {})."
  [:map [:tree {:optional true} widget-schema]
   [:params {:optional true} [:map-of :keyword [:maybe some?]]]])

(def ^:private components-schema
  "Component registry: component-name -> component definition."
  [:map-of [:string {:min 1}] component-schema])

;; -- Parameter substitution --
(def ^:private param-ref-pattern
  "A $param reference in a template string. Param names start with a
   LETTER — a `$` followed by digits is a currency literal (\"$722\"),
   not a reference, so substituted values never re-read as refs and the
   residue firewall doesn't reject them. `[\\w-]*` is greedy, so the
   LONGEST name wins — `$title-suffix` can never half-match as `$title`."
  #"\$([a-zA-Z][\w-]*)")

(def ^:private param-type-preds
  "Closed set of declarable param :type values → validating predicate."
  {:string string? :int int? :map map? :keyword keyword?})

(defn- substitute-string
  "Replace $param references in a string with values from props, in a single
   pass (a substituted VALUE is never re-scanned as a template). Throws on a
   $reference that names no prop — $ is reserved in component templates."
  [s props]
  (if (or (nil? s) (not (str/includes? s "$")))
    s
    (str/replace
     s
     param-ref-pattern
     (fn [[_ pname]]
       (let [k (keyword pname)]
         (if (contains? props k)
           (str (get props k))
           (throw (ex-info
                   (str "Unknown $" pname " reference in \"" s "\" — not a component param")
                   {:param k :string s :available (sort (keys props))}))))))))

(defn- substitute-value
  "Substitute a value: if it's a :$param keyword, look up in props.
   Otherwise return as-is."
  [v props]
  (if (and (keyword? v) (str/starts-with? (name v) "$"))
    (let [param-key (keyword (subs (name v) 1))] (get props param-key v))
    v))

(defn- substitute-map-vals
  "Substitute $param references in all values of a map."
  [m props]
  (when m (reduce-kv (fn [result k v] (assoc result k (substitute-value v props))) {} m)))

(defn- substitute-widget-props
  "Walk all key-value pairs in a widget map, substituting :$param values
   in widget-specific prop maps (e.g. :led_props, :slider_props).
   Skips known structural keys that are handled separately."
  [widget props]
  (let [structural-keys #{:tag :class :text :children :bind :bind-fmt :event :slot :x :y
                          :props :show-when :checked-when :style}]
    (reduce-kv
     (fn [result k v]
       (if (contains? structural-keys k)
         result
         (assoc result
                k
                (if (map? v) (substitute-map-vals v props) (substitute-value v props)))))
     widget
     widget)))

(defn- prune-optional-substitutions
  "Drop template keys whose PARAM substitution resolved to nothing: a :class
   whose template string carries a $param and substituted to blank, and an
   :event whose template value is a :$param keyword and substituted to nil.
   This is what makes an OPTIONAL string param on :class and an OPTIONAL
   keyword param on :event expressible at all — without it the blank :class
   reaches the class parser as an unknown token and the nil :event rides the
   node as a value no authored screen can carry. Scoped to PARAM-fed values
   only: a template's static empty :class stays as written, so a static
   authoring error keeps failing loudly at the class parser rather than being
   silently absorbed here."
  [template node]
  (cond-> node
    (and (string? (:class template))
         (str/includes? (:class template) "$")
         (str/blank? (:class node)))
    (dissoc :class)
    (and (keyword? (:event template))
         (str/starts-with? (name (:event template)) "$")
         (nil? (:event node)))
    (dissoc :event)))

(declare resolve-component-usage)

;; -- Tree resolution --
(defn- resolve-tree
  "Recursively resolve a component tree template with given props and children.
   Handles: $param in :text, :class; :$param keyword values in :bind, :bind-fmt;
   {:slot :default} replaced with provided children."
  [tree props children components visited]
  (cond
    ;; Slot placeholder — inject children, resolving any nested components
    (and (map? tree) (contains? tree :slot))
    (let [slot-children (or children [])]
      (vec (mapcat (fn [child]
                     (let [resolved (resolve-tree child props nil components visited)]
                       (if (vector? resolved) resolved [resolved])))
                   slot-children)))
    ;; Widget node
    (map? tree)
    (let [tag (:tag tree)]
      (if (and tag (contains? components (name tag)))
        ;; Nested component reference — substitute the ENCLOSING component's
        ;; params into the usage's :props first (a `:props {:title :$title}`
        ;; hop must carry the outer value in), then resolve recursively.
        (resolve-component-usage
         (cond-> tree (:props tree) (update :props #(substitute-map-vals % props)))
         components
         visited)
        ;; Regular LVGL widget — substitute params
        (prune-optional-substitutions
         tree
         (cond-> (substitute-widget-props tree props)
           (:text tree) (update :text substitute-string props)
           (:class tree) (update :class substitute-string props)
           (:bind tree) (update :bind substitute-map-vals props)
           (:bind-fmt tree) (update :bind-fmt substitute-map-vals props)
           (:event tree) (update :event #(substitute-value % props))
           (:show-when tree) (update :show-when substitute-map-vals props)
           (:checked-when tree) (update :checked-when substitute-map-vals props)
           (:children tree)
           (update :children
                   (fn [ch]
                     (vec (mapcat
                           (fn [child]
                             (let [resolved
                                   (resolve-tree child props children components visited)]
                               (if (vector? resolved) resolved [resolved])))
                           ch))))))))
    ;; Vector of nodes (from slot expansion)
    (vector? tree)
    (vec (mapcat (fn [child]
                   (let [resolved (resolve-tree child props children components visited)]
                     (if (vector? resolved) resolved [resolved])))
                 tree))
    :else tree))

(defn- validate-props!
  "Validate one component usage's :props against the declared :params:
   no unknown keys, required keys present, and each non-nil resolved value
   matching its declared :type (`param-type-preds`)."
  [tag-name params provided-props resolved-props]
  (when-let [unknown (seq (remove (set (keys params)) (keys provided-props)))]
    (throw (ex-info
            (str "Unknown :props key(s) for component " tag-name
                 ": " (vec unknown)
                 ". Declared params: " (sort (keys params)))
            {:component tag-name :unknown (vec unknown) :declared (sort (keys params))})))
  (doseq [[param-key param-def] params]
    (when (and (:required param-def) (not (contains? provided-props param-key)))
      (throw (ex-info (str "Missing required param :" (name param-key)
                           " for component " tag-name)
                      {:component tag-name :param param-key})))
    (when-let [param-type (:type param-def)]
      (let [pred (or (param-type-preds param-type)
                     (throw (ex-info
                             (str "Component " tag-name
                                  " param :" (name param-key)
                                  " declares unknown :type " param-type
                                  ". Valid: " (sort (keys param-type-preds)))
                             {:component tag-name :param param-key :type param-type})))
            value (get resolved-props param-key)]
        (when (and (some? value) (not (pred value)))
          (throw
           (ex-info
            (str "Component " tag-name
                 " param :" (name param-key)
                 " expects " (name param-type)
                 ", got " (pr-str value))
            {:component tag-name :param param-key :expected param-type :actual value})))))))

(defn- scan-residue!
  "Post-expansion firewall: a residual `$param` in a :text/:class string or a
   residual `:$param` keyword value anywhere in the expanded tree means a
   substitution silently missed — throw naming the component + param."
  [tree tag-name]
  (walk/postwalk
   (fn [node]
     (when (and (keyword? node) (str/starts-with? (name node) "$"))
       (throw (ex-info (str "Component "
                            tag-name
                            " left unresolved :$"
                            (subs (name node) 1)
                            " after expansion")
                       {:component tag-name :param (keyword (subs (name node) 1))})))
     ;; EVERY KEY, not just :text and :class. Listing two keys made the firewall
     ;; exactly as wide as the substitution it guards, so the keys substitution
     ;; SKIPS were also the keys the firewall could not see — and `:style` is one of
     ;; them (it sits in substitute-widget-props' structural-keys, and resolve-tree's
     ;; cond-> chain has no clause for it). So `:style {:bg-image-src "$icon"}`
     ;; substituted to nothing and passed the firewall, shipping the literal string
     ;; "$icon" with no diagnostic at either end.
     ;;
     ;; A `:$param` KEYWORD in the same position was always caught, by the postwalk
     ;; clause above — only the STRING form leaked, which is why this was invisible:
     ;; the keyword spelling is the one an author is more likely to try first.
     ;;
     ;; Currency is safe by construction: `param-ref-pattern` requires a LETTER after
     ;; the `$`, so "$722" is not a reference and never was.
     ;;
     ;; REMAINING GAP, named rather than implied: a `$param` inside a VECTOR of plain
     ;; strings (a dropdown's `:options`, say) is visited by postwalk as a bare
     ;; string, not as a map value, so it is not reached here. Closing that costs the
     ;; key name in the diagnostic, which is the more useful half.
     (when (map? node)
       (doseq [[k s] node
               :when (string? s)]
         (when-let [[residue pname] (re-find param-ref-pattern s)]
           (throw
            (ex-info
             (str "Component " tag-name " left unresolved " residue " in " k " " (pr-str s))
             {:component tag-name :key k :param (keyword pname) :value s})))))
     node)
   tree)
  nil)

(defn- contains-stateful-widget?
  "True when the resolved tree contains a node whose tag carries runtime
   user state (schema/stateful-widget-tags)."
  [tree]
  (let [found (volatile! false)]
    (walk/postwalk (fn [node]
                     (when (and (map? node)
                                (contains? schema/stateful-widget-tags (:tag node)))
                       (vreset! found true))
                     node)
                   tree)
    @found))

(defn- attach-usage-id
  "Carry a component usage's author :id onto the resolved root node — the
   path-based identity then prefixes every inner node for free (two
   instances of one component get distinct inner identities). HARD errors
   (operator decision, docs/lvgl-factory/10-TREE-PATCH-DESIGN.md): a
   stateful-widget-bearing usage without :id (its positional segment
   breaks on sibling reorder, silently losing user state), and :id on a
   multi-root expansion (no single root to attach identity to)."
  [resolved node tag-name]
  (let [usage-id (:id node)]
    (when (and (nil? usage-id) (contains-stateful-widget? resolved))
      (throw (ex-info (str "Component usage "
                           tag-name
                           " expands to stateful"
                           " widget(s) ("
                           (str/join ", " (map name (sort schema/stateful-widget-tags)))
                           ") but has no :id — positional identity breaks on"
                           " sibling reorder and loses user runtime state."
                           " Add :id to the usage site.")
                      {:component tag-name :missing :id})))
    (cond (nil? usage-id) resolved
          (map? resolved) (assoc resolved :id usage-id)
          :else (throw (ex-info (str "Component " tag-name
                                     " expands to multiple"
                                     " roots — :id cannot attach to a single"
                                     " node. Wrap the template in one root or"
                                     " drop the usage :id.")
                                {:component tag-name :id usage-id})))))

(defn- resolve-component-usage
  "Resolve a single component usage node into expanded LVGL widgets."
  [node components visited]
  (let [tag-name (name (:tag node))
        component (get components tag-name)]
    (when-not component
      (throw (ex-info (str "Unknown component: " tag-name)
                      {:tag tag-name :available (keys components)})))
    (when (contains? visited tag-name)
      (throw (ex-info (str "Circular component reference: " tag-name)
                      {:tag tag-name :visited visited})))
    (when (> (count visited) 10)
      (throw (ex-info (str "Component nesting too deep (>10): " tag-name)
                      {:tag tag-name :depth (count visited) :chain visited})))
    (let [params (:params component)
          provided-props (:props node {})
          ;; Build resolved props with defaults
          resolved-props (reduce-kv
                          (fn [acc k param-def]
                            (assoc acc k (get provided-props k (:default param-def))))
                          {}
                          params)
          _ (validate-props! tag-name params provided-props resolved-props)
          ;; Children from usage (for slot injection)
          usage-children (:children node)
          ;; Resolve the component tree
          visited' (conj visited tag-name)
          resolved (try (resolve-tree (:tree component)
                                      resolved-props
                                      usage-children
                                      components
                                      visited')
                        (catch clojure.lang.ExceptionInfo e
                          ;; Context wrapper, not suppression: name the component
                          ;; on errors thrown below it (the innermost component
                          ;; that already named itself wins).
                          (if (:component (ex-data e))
                            (throw e)
                            (throw (ex-info (str "In component " tag-name
                                                 ": " (ex-message e))
                                            (assoc (ex-data e) :component tag-name)
                                            e)))))]
      (scan-residue! resolved tag-name)
      (attach-usage-id resolved node tag-name))))

;; -- Public API --
(defn resolve-components
  "Walk a screen's widget tree and expand any component references.
   Components map: {\"card\" {:params {...} :tree {...}}, ...}
   Widget :tag values matching component names are expanded inline.
   Returns the screen with all components resolved to LVGL widgets."
  [components screen]
  (if (empty? components)
    screen
    (let [resolve-widget (fn resolve-widget [widget]
                           (let [tag (:tag widget)]
                             (if (and tag (contains? components (name tag)))
                               (resolve-component-usage widget components #{})
                               (cond-> widget
                                 (:children widget)
                                 (update :children (fn [ch] (mapv resolve-widget ch)))))))]
      (update screen :tree resolve-widget))))

(defn load-components
  "Parse component definitions from a map.
   Input: {:component-name {:params {...} :tree {...}} ...}
   Validates that component names don't start with 'lv_'.
   Returns {\"component-name\" {:params {...} :tree {...}}} for resolution."
  [component-defs]
  (reduce-kv (fn [acc k v]
               (let [n (name k)]
                 (when (str/starts-with? n "lv_")
                   (throw (ex-info (str "Component name must not start with lv_: " n)
                                   {:component n})))
                 (assoc acc n (assoc v :params (or (:params v) {})))))
             {}
             (or component-defs {})))

;; -- Function schema registrations --
(m/=> substitute-string [:=> [:cat [:maybe :string] props-schema] [:maybe :string]])

(m/=> validate-props!
      [:=> [:cat [:string {:min 1}] params-decl-schema props-schema props-schema] :nil])

(m/=> scan-residue! [:=> [:cat some? [:string {:min 1}]] :nil])

(m/=> substitute-value [:=> [:cat [:maybe some?] props-schema] [:maybe some?]])

(m/=> substitute-map-vals
      [:=> [:cat [:maybe [:map-of :keyword [:maybe some?]]] props-schema] [:maybe :map]])

(m/=> substitute-widget-props [:=> [:cat widget-schema props-schema] widget-schema])

(m/=> prune-optional-substitutions [:=> [:cat widget-schema widget-schema] widget-schema])

(m/=> resolve-tree
      [:=>
       [:cat [:maybe some?] props-schema [:maybe [:sequential :any]] components-schema
        [:set [:string {:min 1}]]] [:maybe some?]])

(m/=> contains-stateful-widget? [:=> [:cat [:maybe some?]] :boolean])

(m/=> attach-usage-id [:=> [:cat some? widget-schema [:string {:min 1}]] some?])

(m/=> resolve-component-usage
      [:=> [:cat widget-schema components-schema [:set [:string {:min 1}]]] some?])

(m/=> resolve-components
      [:=> [:cat components-schema [:map [:tree map?]]] [:map [:tree map?]]])

(m/=> load-components
      [:=> [:cat [:maybe [:map-of :keyword component-schema]]] components-schema])