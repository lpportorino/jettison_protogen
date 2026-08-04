(ns uigen.wire-bounds
  "Read a ui_ast WIRE BOUND from the published manifest, fail-loud.

   `renderer-gen.ui-ast-bounds-json` emits `output/manifests/ui-ast-bounds.json`
   from `proto/ui/ui_ast.options` exactly so a generator can READ the bound it
   must not exceed rather than re-type the literal beside a hand-written
   fail-fast message. This namespace is the reading half.

   WHY A RE-TYPED LITERAL IS WORSE THAN IT LOOKS. The bound has three consumers
   and only one of them used to be unheld: the renderer's C `#define` is bound by
   a `_Static_assert` against the nanopb-generated struct, the manifest is bound
   by the freshness lane that regenerates and diffs it, and a Clojure literal is
   bound by NOTHING. Lower the nanopb bound and the C stops compiling while the
   generator goes on emitting templates the renderer will refuse at load — a
   defect that surfaces on a device, not in a battery.

   FAIL LOUD, NEVER DEFAULT. Every refusal names its own CLAUSE, so a canary can
   require the clause under test and require its neighbours to stay silent — a
   red that cannot be attributed proves nothing about the clause it was meant to
   drive. A missing manifest, a shapeless one, an empty one, an unknown field, an
   undeclared option and a non-positive value are six different defects, and
   answering any of them with a fallback number would publish a cap nobody chose.

   THE VALIDATION IS PURE AND THE IO IS NOT, deliberately: `bounds-of` and
   `bound-in` take their input as an argument, so every refusal is drivable from
   a hermetic fixture. Only `!bounds` reads the classpath."
  (:require [asgard.manifest :as mf]
            [asgard.schema :as s]
            [malli.core :as m]))

(set! *warn-on-reflection* true)

(def manifest-name
  "The manifest this namespace reads, named in every error message. Its producer
   is `renderer-gen.ui-ast-bounds-json`; its freshness lane is
   `make -f renderer.mk manifests`."
  "ui-ast-bounds.json")

(defn- refusal
  "The refusal exception for one clause, carrying its own `:clause` so a canary
   can attribute a red to the clause under test rather than to a neighbour.

   BUILT AND RETURNED rather than thrown, so `throw` stays visible at each call
   site — and so this helper has a return its own arrow-spec can name honestly. A
   helper that always throws can only claim a return it never reaches."
  [clause message data]
  (ex-info (str "uigen.wire-bounds: " message)
           (assoc data :clause clause :manifest manifest-name)))
(m/=> refusal
      [:=> [:cat :keyword s/ne-string [:map-of :keyword :any]]
       [:fn {:error/message "an ex-info"} #(instance? clojure.lang.ExceptionInfo %)]])

(defn bounds-of
  "The validated `{field {option value}}` map inside a parsed wire-bound
   manifest. Refuses an absent manifest, one carrying no `bounds` map, and one
   declaring no field at all — the third because an empty result is what a wrong
   path, a truncated read and a comment-only options file all produce, and
   reading it as `no bounds apply` would silently unbound every generator that
   asks."
  [manifest]
  (when-not manifest
    (throw (refusal :manifest-absent
                    (str manifest-name " is not on the classpath and not at the"
                         " filesystem fallback — the wire bounds cannot be read")
                    {})))
  (let [bounds (:bounds manifest)]
    (when-not (map? bounds)
      (throw (refusal :manifest-shape
                      (str manifest-name " carries no `bounds` map")
                      {:present-keys (vec (sort (map name (keys manifest))))})))
    (when (empty? bounds)
      (throw (refusal :manifest-empty
                      (str manifest-name " declares no bounded fields")
                      {})))
    bounds))
(m/=> bounds-of [:=> [:cat [:maybe [:map-of :keyword :any]]] [:map-of :keyword :any]])

(defn bound-in
  "One nanopb `option` of one fully-qualified ui_ast `field`, out of a validated
   bounds map, as a positive long. Every miss is a throw: a field nobody
   published, an option that field does not declare, and a value that is not a
   positive integer are three distinct defects in the options file, and none of
   them has a safe substitute."
  [bounds field option]
  (let [opts (get bounds (keyword field))]
    (when-not opts
      (throw (refusal :unknown-field
                      (str "no bound is published for field " (pr-str field))
                      {:field field})))
    (let [v (get opts (keyword option))]
      (when-not v
        (throw (refusal :unknown-option
                        (str "field " (pr-str field) " declares no " option
                             " (it declares "
                             (pr-str (vec (sort (map name (keys opts))))) ")")
                        {:field field :option option})))
      (when-not (and (int? v) (pos? v))
        (throw (refusal :bad-option-value
                        (str "field " (pr-str field) "'s " option " is " (pr-str v)
                             ", which is not a positive integer")
                        {:field field :option option :value v})))
      (long v))))
(m/=> bound-in [:=> [:cat [:map-of :keyword :any] s/ne-string s/ne-string] :int])

(def ^:private !bounds
  "Delay-cached bounds map off the classpath — the one impure entry point."
  (delay (bounds-of (mf/ui-ast-bounds))))

(defn max-size
  "The nanopb `max_size` of `field` — the widest byte payload the renderer's
   generated struct can store for it."
  [field]
  (bound-in @!bounds field "max_size"))
(m/=> max-size [:=> [:cat s/ne-string] :int])

(defn max-count
  "The nanopb `max_count` of `field` — the most repeated entries the renderer's
   generated struct can store for it."
  [field]
  (bound-in @!bounds field "max_count"))
(m/=> max-count [:=> [:cat s/ne-string] :int])
