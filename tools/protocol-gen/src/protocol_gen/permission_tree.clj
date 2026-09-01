(ns protocol-gen.permission-tree
  "The NESTED permission mirror: one static tree per group, in Rust, for a
   consumer that walks encoded bytes tag by tag.

   WHY A SECOND MIRROR RATHER THAN THE FLAT ONE. `protocol-gen.mirror` emits a
   FLAT map — group, message, field, number, provenance — and a byte-level
   scanner cannot walk it. A scanner holds a position in a message and a tag it
   has just read; what it needs at that instant is the node for THAT tag under
   the node it is standing on, and a flat map has no `under`. The two artefacts
   also carry different facts: the flat mirror carries NUMBER PROVENANCE and
   DIRECTION, which a scanner does not consult, and carries no permission axis
   at all. Neither is the other under another name, and neither replaces the
   other here.

   WHAT A TREE SAYS THAT A PROJECTED `.proto` DOES NOT. The group's schema
   simply omits a withheld field, so a reader of it cannot tell a field the
   policy denied from a field nobody has added yet. This tree names both: a
   granted field is present and INHERITS its message's grant, a withheld one is
   present and DENIED. That is what makes TOTALITY checkable — see below — and
   it is the one place this generator deliberately names something the group's
   `.proto` withholds.

   THAT DISCLOSURE IS BOUNDED, and the bound is what makes it acceptable. A
   denied node is TERMINAL: nothing beneath it is emitted, so the tree never
   names a field of a message the group holds no grant for, and never names a
   message id the group's `.proto` does not already carry. What it discloses is
   the field NAMES of messages the group was already granted.

   TOTALITY, and the precise form of it this shape can carry. Every node the
   tree emits for a message lists ONE CHILD PER FIELD THE SOURCE MESSAGE
   DECLARES — granted or not — so a scanner meeting a tag with no node knows
   the generator never described it, rather than wondering whether a field was
   dropped. EMPTY CHILDREN THEREFORE MEANS `DESCEND NO FURTHER`, which covers
   three cases and not one: a scalar leaf, a message with no fields, and a
   denied node. A shape carrying only `tag`, `name`, `permission` and
   `children` cannot tell those apart, and pretending otherwise would be a
   claim the emitted data does not support.

   THE PERMISSION AXIS IS MESSAGE-GRAINED, because the policy is. A grant names
   a message, a direction and a FIELD FILTER; no field carries a grant of its
   own. So a message root is `Allow`, a field the filter kept is `Inherit`, and
   a field the filter dropped is `Deny`. `Unspecified` is the enum's zero value
   and is never emitted — a node with no permission is not a shape this
   generator can produce.

   THE CLOSURE CHECK IS LEANT ON, NOT RE-DERIVED. `protocol-gen.projection`
   already refuses a granted field whose message type the group was not also
   granted, so every message-typed field this namespace expands has its target
   in the same group's projection. Re-deriving that here would be a second
   opinion about a question one pass has already settled."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [protocol-gen.constructs :as constructs]
            [protocol-gen.db :as db]
            [protocol-gen.projection :as projection]
            [protocol-gen.rust-lit :as rust-lit]))

(set! *warn-on-reflection* true)

(def message-root-tag
  "The `tag` a MESSAGE ROOT carries. Zero, and it is not a magic number: zero is
   not a legal proto field number — `protocol-gen.constructs/min-field-number`
   is 1 — so a node carrying it cannot be confused with a field. A root is
   selected by NAME (its source id); everything below a root is selected by
   TAG."
  0)

(def permission
  "The permission a node carries, as this generator models it.

   `:unspecified` is deliberately absent. It is the emitted enum's ZERO VALUE,
   which a consumer needs so that a default-constructed node is not silently a
   grant; nothing here can produce one, and a model that could would be
   claiming a state the policy has no way to express."
  [:enum :allow :deny :inherit])

(def node
  "One tree node: the tag that selects it, the name it is known by, what the
   group may do with it, and the nodes below it.

   RECURSIVE BY REGISTRY rather than by a depth-limited copy, because the shape
   genuinely is: a message-typed field's children are that message's fields."
  [:schema {:registry {::node [:map {:closed true}
                               [:tag [:int {:min 0}]]
                               [:name [:string {:min 1}]]
                               [:permission permission]
                               [:children [:vector [:ref ::node]]]]}}
   ::node])

(def group-tree
  "One group's whole tree: its policy id, the static it is emitted under, and
   its roots."
  [:map {:closed true}
   [:id :keyword]
   [:static [:re #"^[A-Z][A-Z0-9_]*$"]]
   [:roots [:vector node]]])

(def permission-variants
  "Each modelled permission, mapped to the Rust variant that names it.

   TOTAL OVER `permission` ABOVE, which is closed — so these three are every
   value that can arrive, and `permission-variant` throws on anything else
   rather than rendering a guess into a file a scanner will trust."
  {:allow "Allow"
   :deny "Deny"
   :inherit "Inherit"})

(defn permission-variant
  "The Rust `Permission` variant naming `p`, or a throw naming the value.

   THROWS RATHER THAN DEFAULTING, for the reason
   `protocol-gen.rust-access/access-variant` gives: a permission rendered from
   a guess is the one failure a permission table exists to make impossible."
  [p]
  (or (get permission-variants p)
      (throw (ex-info "Not a permission this emitter can name"
                      {:permission p :known (vec (sort (keys permission-variants)))}))))

(m/=> permission-variant [:=> [:cat permission] [:string {:min 1}]])

(defn static-name
  "The Rust static `id`'s tree is emitted under: the policy id uppercased, with
   every character Rust cannot carry in an identifier replaced by an
   underscore.

   NOT ASSUMED INJECTIVE. `:relay-a` and `:relay_a` are two legal, distinct
   policy ids that land on one static name, so `module` refuses a collision
   rather than emitting two statics whose second definition would not compile —
   the same shape `protocol-gen.projection/proto-name-of` carries for message
   names."
  [id]
  (str/upper-case (str/replace (name id) #"[^A-Za-z0-9]" "_")))

(m/=> static-name [:=> [:cat :keyword] [:string {:min 1}]])

(defn- source-message
  "The message `id` names in `universe` — the database with the minted
   declarations merged in — or a refusal.

   IT ASKS THE UNIVERSE AND NOT THE PROJECTION, and that is the whole reason
   this namespace takes one. A projected message carries the GRANTED fields; a
   node's totality is over the fields the SOURCE declares, so the withheld ones
   have to come from somewhere the policy has not already filtered."
  [universe id]
  (or (get-in universe [:messages id])
      (constructs/refuse! :message-not-in-database id
                          (str "the projection carries this message and neither the "
                               "database nor the minted declarations do"))))

(defn- expand
  "The children of message `msg-id`: one node per field the SOURCE declares, in
   NUMBER order.

   `grants` is the group's projected messages keyed by source id, so a field
   the grant kept is one this message's projection carries and every other
   field of the source is denied. `seen` is the message ids on the path from
   the root, which is what makes a self-referential grant a REFUSAL rather than
   a stack overflow: a static tree is finite by construction, so a cycle has no
   emission at all and approximating one at some depth would be a tree that
   silently stops describing what it claims to describe.

   ORDER IS BY NUMBER, matching `protocol-gen.emit`: the layout is a function
   of the wire contract rather than of the order anything was built in, which
   is what makes two runs write identical bytes."
  [universe grants seen msg-id]
  (when (contains? seen msg-id)
    (constructs/refuse! :permission-cycle msg-id
                        (str "expanding this message reaches it again through "
                             (pr-str (vec (sort seen)))
                             "; a static tree is finite, so a cycle has no emission")))
  (let [src (source-message universe msg-id)
        kept (into #{} (map :name) (:fields (get grants msg-id)))
        deeper (conj seen msg-id)]
    (mapv (fn [fld]
            (let [granted? (contains? kept (:name fld))
                  descend? (and granted? (= :message (:type fld)))]
              {:tag (:number fld)
               :name (:name fld)
               :permission (if granted? :inherit :deny)
               :children (if descend?
                           (expand universe grants deeper (:type-ref fld))
                           [])}))
          (sort-by :number (:fields src)))))

(defn- roots-of
  "One group's roots: one node per granted message, in SOURCE-ID order.

   A root is `Allow` because a grant is what the policy states at message
   grain; the filter it carries is expressed one level down, by each field
   being `Inherit` or `Deny`."
  [universe g]
  (let [grants (into {} (map (juxt :id identity)) (:messages g))]
    (mapv (fn [msg]
            {:tag message-root-tag
             :name (:id msg)
             :permission :allow
             :children (expand universe grants #{} (:id msg))})
          (sort-by :id (:messages g)))))

(defn- nodes-under-denial
  "Every path beneath a denied node, each with the permission it carries.

   THE PREDICATE IS `BENEATH A DENIAL`, NOT `GRANTED BENEATH A DENIAL`, and it
   is deliberately the stronger of the two. A denial is terminal: the scanner
   refuses the node and never descends, so a grant below it is unreachable AND
   a denial below it is an interior this generator undertook not to disclose.
   The narrow reading would pass the second, which is exactly the emission a
   defect in `expand` produces."
  [nodes]
  (letfn [(walk [prefix denied? siblings]
            (mapcat (fn [n]
                      (let [path (str prefix (:name n))
                            deny? (= :deny (:permission n))]
                        (concat (when denied? [[path (:permission n)]])
                                (walk (str path ">") (or denied? deny?) (:children n)))))
                    siblings))]
    (vec (walk "" false nodes))))

(defn- assert-reachable!
  "Refuse a tree that describes anything beneath a denial, naming the node.

   A DEFENSIVE INVARIANT OVER THIS GENERATOR'S OWN OUTPUT, and saying so is the
   point: no access policy can reach it, because `expand` makes a denied node
   terminal, so the population it judges is empty on every legal input. It is
   here for the same reason `protocol-gen.numbering/assert-stamped!` is — the
   thing it forbids is catastrophic and silent, and the check costs one walk.
   Its ability to FAIL is proven by mutation in the canary and cannot be proven
   by any policy."
  [group-id nodes]
  (when-let [[[path p] :as offenders] (seq (nodes-under-denial nodes))]
    (constructs/refuse! :grant-under-denial (str (name group-id) "/" path)
                        (str "this node carries " p " while sitting beneath a denied "
                             "node; a denial is terminal, so nothing below one is "
                             "reachable or may be described — "
                             (count offenders) " node(s) in this group"))))

(defn trees
  "Every projected group's tree, in POLICY ORDER, each with the Rust static it
   is emitted under.

   Refuses two group ids that flatten onto one static name: the second
   definition would not compile, and a generator that emitted it would be
   handing a consumer a file whose failure names Rust rather than the policy."
  [universe groups]
  (let [built (mapv (fn [g]
                      (let [roots (roots-of universe g)]
                        (assert-reachable! (:id g) roots)
                        {:id (:id g) :static (static-name (:id g)) :roots roots}))
                    groups)]
    (doseq [[nm gs] (group-by :static built)
            :when (> (count gs) 1)]
      (constructs/refuse! :name-collision nm
                          (str "these group ids flatten onto one Rust static: "
                               (pr-str (vec (sort (map :id gs)))))))
    built))

(m/=> trees
      [:=> [:cat db/database [:sequential projection/projected-group]]
       [:vector group-tree]])

(def banner
  "The header the emitted file carries.

   It states the two names the file assumes are in scope, because that is the
   contract a consumer has to satisfy before an `include!` of it will compile,
   and it is not derivable from the file's own text."
  (str "// GENERATED by protocol-gen — DO NOT EDIT.\n"
       "// Source of truth: the access policy named in this run's arguments,\n"
       "// projected in the same run and from the same value as each group's\n"
       "// `.proto`, its Rust access module and the flat permission mirror.\n"
       "//\n"
       "// IT IS A FRAGMENT, meant to be `include!`d into a module that already\n"
       "// declares `Permission` and `PermissionNode`. It names NO crate and\n"
       "// assumes exactly those two names and nothing else, so the module that\n"
       "// includes it owns where they come from.\n"
       "//\n"
       "// EVERY MESSAGE NODE LISTS ONE CHILD PER FIELD ITS SOURCE DECLARES —\n"
       "// granted or denied — so a tag with no node here was never described.\n"
       "// EMPTY CHILDREN MEANS `DESCEND NO FURTHER`: a scalar leaf, a message\n"
       "// with no fields, or a denied node, which are not distinguishable in\n"
       "// this shape and are not meant to be.\n"
       "//\n"
       "// A DENIED NODE IS TERMINAL. Nothing below one is emitted, so this file\n"
       "// never names a field of a message its group holds no grant for.\n"))

(defn- render-node
  "One `PermissionNode::new(…)` call, indented by `indent` spaces, with its
   children nested inside it.

   A LEAF RENDERS ON ONE LINE and a parent over several. The layout is a
   function of the tree and of nothing else, so two runs over one projection
   write identical bytes; it is deliberately NOT rustfmt-canonical, for the
   reason the README records about the access module — chasing another tool's
   width heuristics here would be a copy of a rule that rots when that tool
   changes its mind."
  [indent n]
  (let [pad (apply str (repeat indent \space))
        head (str pad "PermissionNode::new(" (:tag n) ", "
                  (rust-lit/string-literal (:name n)) ", "
                  "Permission::" (permission-variant (:permission n)) ", ")]
    (if (seq (:children n))
      (str head "&[\n"
           (str/join "\n" (map #(render-node (+ indent 4) %) (:children n)))
           "\n" pad "]),")
      (str head "&[]),"))))

(defn- render-tree
  "One group's `pub static`, with its roots."
  [{:keys [id static roots]}]
  (str "/// The permission tree the group `" (name id) "` is scanned against.\n"
       "///\n"
       "/// One root per message the policy grants it, selected by NAME; every\n"
       "/// node below a root is selected by its wire TAG.\n"
       "pub static " static ": &[PermissionNode] = &[\n"
       (str/join "\n" (map #(render-node 4 %) roots))
       (when (seq roots) "\n")
       "];\n"))

(defn module
  "The whole emitted Rust fragment: one static per group, then the table that
   selects one at run time.

   THE SELECTOR IS DATA AND NOT A FUNCTION, deliberately. A consumer picks a
   group by the connecting client's identity, which is a run-time value, and a
   `const fn` doing that comparison would be logic this file has no business
   carrying — while a sorted table of pairs is something the consumer searches
   however it likes, with no allocation and nothing to keep in step."
  [built]
  (let [ordered (vec (sort-by (comp name :id) built))]
    (str banner
         "\n"
         (str/join "\n" (map render-tree ordered))
         "\n/// Every group's tree, keyed by the group's policy id, in id order.\n"
         "pub static GROUPS: &[(&str, &[PermissionNode])] = &[\n"
         (str/join "" (for [t ordered]
                        (str "    (" (rust-lit/string-literal (name (:id t))) ", "
                             (:static t) "),\n")))
         "];\n")))

(m/=> module [:=> [:cat [:sequential group-tree]] [:string {:min 1}]])
