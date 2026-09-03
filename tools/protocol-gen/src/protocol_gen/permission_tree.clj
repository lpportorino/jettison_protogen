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
   dropped.

   EVERY NODE CARRIES ITS SOURCE TYPE'S KIND, and that is what makes totality
   ACTIONABLE rather than merely present. An EMPTY `:message` node declares no
   fields, so every tag inside it is undescribed and a scanner must REFUSE it;
   only a `:leaf` names bytes with no tags in them and may be stepped over. A
   shape carrying children alone cannot state that difference: a granted
   message that declares zero fields and a scalar are both `children = []`, and
   a scanner reading the second rule for the first grants every tag a hostile
   peer smuggles into it, unread. The kind is DERIVED from the database's own
   field type and never inferred from the children, because the two are exactly
   the facts that come apart here.

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

(def kind
  "What a node's SOURCE TYPE is, as far as a byte-level scanner is concerned.

   TWO VALUES AND NOT THREE. The axis is `does this field's payload contain
   tagged fields`, which is the only question a scanner asks of it, and every
   proto type answers it one way or the other: a message does, everything else
   — scalars, enums, strings, bytes, and any of those repeated — does not. A
   third value naming, say, `enum` would be a distinction this artefact carries
   and nothing reads.

   IT IS NOT A RESTATEMENT OF `:children`. An empty `:message` and a `:leaf`
   both carry no children and mean opposite things to a scanner, which is the
   whole reason the key exists."
  [:enum :message :leaf])

(def node
  "One tree node: the tag that selects it, the name it is known by, the kind of
   thing its source type is, what the group may do with it, and the nodes below
   it.

   RECURSIVE BY REGISTRY rather than by a depth-limited copy, because the shape
   genuinely is: a message-typed field's children are that message's fields.

   THE `:fn` CLAUSE FORBIDS THE ONE CONTRADICTION THE TWO KEYS CAN EXPRESS — a
   `:leaf` carrying children. It is not reachable from `expand`, which descends
   only where the kind is `:message`, and it is here for the reason
   `assert-reachable!` below is: what it forbids is silent. `render-node` emits
   a leaf as a three-argument call with nowhere to put a child, so a leaf that
   carried any would have them DROPPED and the tree would quietly stop being
   total — the one property a consumer's undescribed-tag refusal rests on."
  [:schema {:registry {::node [:and
                               [:map {:closed true}
                                [:tag [:int {:min 0}]]
                                [:name [:string {:min 1}]]
                                [:kind kind]
                                [:permission permission]
                                [:children [:vector [:ref ::node]]]]
                               [:fn {:error/message "a leaf node carries no children"}
                                (fn [n] (or (= :message (:kind n))
                                            (empty? (:children n))))]]}}
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

(def node-kinds
  "Each field type a database may carry, mapped to the kind of node a field of
   that type becomes.

   WRITTEN OUT RATHER THAN DERIVED from `protocol-gen.db/known-types`, and the
   difference is the whole of `never defaulted`. A derivation — `:message` for
   the one, `:leaf` for everything else — silently answers for a type nobody
   here has considered, and the answer it gives is the DANGEROUS one: a scanner
   steps over a leaf's bytes unread. Written out, a type added to the database's
   known set matches no key, and `protocol-gen.permission-tree-test` compares
   the two sets in BOTH directions, so the new type's kind is decided by a
   person rather than inherited from a fallback.

   REPEATED IS NOT A KIND. A repeated scalar is scalar bytes and a repeated
   message is message bytes; the database records repetition separately, as
   `:repeated`, and it does not change what a scanner meeting one tag has to do.
   A proto MAP is the same fact once more: a descriptor models it as a repeated
   entry MESSAGE, so it arrives here as `:message` and takes that kind, which is
   correct — an entry has tagged key and value fields inside it."
  {:message :message
   :enum :leaf
   :bool :leaf
   :bytes :leaf
   :double :leaf
   :float :leaf
   :int32 :leaf
   :int64 :leaf
   :string :leaf
   :uint32 :leaf
   :uint64 :leaf
   :sint32 :leaf
   :sint64 :leaf
   :fixed32 :leaf
   :fixed64 :leaf
   :sfixed32 :leaf
   :sfixed64 :leaf})

(def node-constructors
  "Each modelled kind, mapped to the Rust constructor that builds it.

   TOTAL OVER `kind` ABOVE, which is closed — so these two are every value that
   can arrive. They are the whole of what the emitted fragment assumes about
   `PermissionNode` beyond the name itself, and there is deliberately no third:
   a general constructor taking a kind would let a consumer build a node whose
   kind and children disagree, which is the state this key exists to make
   unrepresentable."
  {:message "PermissionNode::message"
   :leaf "PermissionNode::leaf"})

(defn node-constructor
  "The Rust constructor naming kind `k`, or a throw naming the value.

   THROWS RATHER THAN DEFAULTING, for the reason `permission-variant` gives one
   step up, and the cost here is the sharper of the two: a node rendered with
   the wrong constructor tells a scanner to step over bytes it was supposed to
   walk, which is the exact failure this marker was added to remove."
  [k]
  (or (get node-constructors k)
      (throw (ex-info "Not a node kind this emitter can name"
                      {:kind k :known (vec (sort (keys node-constructors)))}))))

(m/=> node-constructor [:=> [:cat kind] [:string {:min 1}]])

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

(defn- field-kind
  "The kind of node the field `fld` of message `msg-id` becomes, or a refusal
   naming the field and its type.

   REACHABLE ONLY FROM HERE, and that is why this namespace judges the type at
   all rather than leaning on `protocol-gen.constructs` the way every other pass
   does. `protocol-gen.projection` runs the expressibility refusals over GRANTED
   fields, so a type this generator cannot name reaches a refusal there whenever
   a policy grants the field. This tree names every field the source declares,
   granted or DENIED, so a denied field carrying `:group` or the producer's
   `:unknown` fallback arrives here having been judged by nothing.

   IT REFUSES RATHER THAN GUESSING, on the same ground `db/known-types` states:
   an unmappable type is refused, never guessed at. The guess a fallback would
   make is not neutral — `:leaf` claims the payload holds no tagged fields, and
   proto2's `:group` is precisely a tagged interior wearing a different
   encoding. Before this key existed a node claimed nothing about its type and
   the question did not arise; carrying the kind is what makes an unnameable
   type a thing this artefact has to have an honest answer for."
  [msg-id fld]
  (or (get node-kinds (:type fld))
      (constructs/refuse! :unknown-field-type (str msg-id "." (:name fld))
                          (str "this field's type is " (pr-str (:type fld))
                               ", which this generator cannot name, so the tree cannot "
                               "say whether a scanner may step over its bytes; the types "
                               "it knows are " (pr-str (vec (sort (keys node-kinds))))))))

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
                  node-kind (field-kind msg-id fld)
                  descend? (and granted? (= :message node-kind))]
              {:tag (:number fld)
               :name (:name fld)
               :kind node-kind
               :permission (if granted? :inherit :deny)
               :children (if descend?
                           (expand universe grants deeper (:type-ref fld))
                           [])}))
          (sort-by :number (:fields src)))))

(defn- roots-of
  "One group's roots: one node per granted message, in SOURCE-ID order.

   A root is `Allow` because a grant is what the policy states at message
   grain; the filter it carries is expressed one level down, by each field
   being `Inherit` or `Deny`.

   EVERY ROOT IS A `:message` BY CONSTRUCTION — a root IS a granted message —
   so the kind is stated here rather than derived from a field type there is
   none of. A root that declares no fields is the case that matters and takes
   the same kind as any other: an emitted root with empty children says `this
   message declares nothing, so refuse every tag inside it`."
  [universe g]
  (let [grants (into {} (map (juxt :id identity)) (:messages g))]
    (mapv (fn [msg]
            {:tag message-root-tag
             :name (:id msg)
             :kind :message
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

   It states the two names the file assumes are in scope and the two
   constructors it calls on the second of them, because that is the contract a
   consumer has to satisfy before an `include!` of it will compile, and it is
   not derivable from the file's own text."
  (str "// GENERATED by protocol-gen — DO NOT EDIT.\n"
       "// Source of truth: the access policy named in this run's arguments,\n"
       "// projected in the same run and from the same value as each group's\n"
       "// `.proto`, its Rust access module and the flat permission mirror.\n"
       "//\n"
       "// IT IS A FRAGMENT, meant to be `include!`d into a module that already\n"
       "// declares `Permission` and `PermissionNode`. It names NO crate and\n"
       "// assumes exactly those two names and nothing else, so the module that\n"
       "// includes it owns where they come from. It builds every node through\n"
       "// `PermissionNode::message(tag, name, permission, children)` or\n"
       "// `PermissionNode::leaf(tag, name, permission)`, in `static` position,\n"
       "// so both must be `const fn`.\n"
       "//\n"
       "// EVERY MESSAGE NODE LISTS ONE CHILD PER FIELD ITS SOURCE DECLARES —\n"
       "// granted or denied — so a tag with no node here was never described.\n"
       "// AN EMPTY `message` NODE DECLARES NO FIELDS, so every tag inside it is\n"
       "// undescribed and a scanner must REFUSE it; only a `leaf` names bytes\n"
       "// with no tags in them and may be stepped over.\n"
       "//\n"
       "// A DENIED NODE IS TERMINAL. Nothing below one is emitted, so this file\n"
       "// never names a field of a message its group holds no grant for. Its\n"
       "// kind still names its source type — denial is carried by\n"
       "// `Permission::Deny` and never by calling a message a leaf.\n"))

(defn- render-node
  "One `PermissionNode::message(…)` or `PermissionNode::leaf(…)` call, indented
   by `indent` spaces, with any children nested inside it.

   THE CONSTRUCTOR COMES FROM THE KIND AND NOT FROM THE CHILDREN, which is the
   whole point of the key: a message that declares no fields renders
   `message(…, &[])` and a scalar renders `leaf(…)`, where one shape rendered
   both identically before. A LEAF TAKES NO CHILDREN ARGUMENT — it is not
   `&[]`, it is absent — so the two are told apart by arity as well as by name,
   and a consumer cannot build a leaf that carries anything.

   A NODE WITHOUT CHILDREN RENDERS ON ONE LINE and a parent over several. The
   layout is a function of the tree and of nothing else, so two runs over one
   projection write identical bytes; it is deliberately NOT rustfmt-canonical,
   for the reason the README records about the access module — chasing another
   tool's width heuristics here would be a copy of a rule that rots when that
   tool changes its mind."
  [indent n]
  (let [pad (apply str (repeat indent \space))
        head (str pad (node-constructor (:kind n)) "(" (:tag n) ", "
                  (rust-lit/string-literal (:name n)) ", "
                  "Permission::" (permission-variant (:permission n)))]
    (cond
      (= :leaf (:kind n)) (str head "),")
      (seq (:children n)) (str head ", &[\n"
                               (str/join "\n" (map #(render-node (+ indent 4) %) (:children n)))
                               "\n" pad "]),")
      :else (str head ", &[]),"))))

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
