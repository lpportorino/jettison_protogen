(ns protocol-gen.render
  "Projection -> the emitter's input. The one place a `:type-ref` becomes a
   NAME.

   `protocol-gen.emit` deliberately knows nothing about a database, so somebody
   has to turn a reference into the identifier the emitted file uses. That is
   this namespace, and it is separate for a reason: resolution is where a
   reference can be resolved WRONGLY, and keeping it out of the emitter leaves
   the emitter with nothing to get wrong.

   It resolves only within the group. `protocol-gen.projection` has already
   refused any granted field whose type the group did not also grant, so a
   reference that reaches here always names something in the same file."
  (:require [malli.core :as m]
            [protocol-gen.db :as db]
            [protocol-gen.emit :as emit]
            [protocol-gen.projection :as projection]))

(set! *warn-on-reflection* true)

(defn- names-in-group
  "id -> emitted name, over everything the group carries."
  [g]
  (into {} (map (juxt :id :proto-name)) (concat (:messages g) (:enums g))))

(defn- proto-type-of
  "The emitted type name for one field: a scalar's own spelling, or the emitted
   name of the message or enum it references.

   An unresolvable reference throws rather than emitting the raw id. The
   projection's closure check has already refused that case, so reaching here
   means the two passes disagree — which is a defect in this tool, not in its
   input, and must not be papered over with a plausible-looking name."
  [names fld]
  (if (contains? db/referring-types (:type fld))
    (or (get names (:type-ref fld))
        (throw (ex-info "Reference survived the closure check unresolved"
                        {:field (:name fld) :type-ref (:type-ref fld)})))
    (name (:type fld))))

(defn- render-field
  "One projected field as the emitter's field. `:number` is COPIED, never
   recomputed — this function has no index and takes none."
  [names options-for msg-id fld]
  {:name (:name fld)
   :number (:number fld)
   :proto-type (proto-type-of names fld)
   :repeated (boolean (:repeated fld))
   :oneof (:oneof fld)
   :options (vec (options-for msg-id fld))})

(defn- render-message
  "One projected message as the emitter's message. Only the oneofs that still
   have a granted member are declared; the emitter drops an empty block anyway,
   and carrying a declaration for one would put a name in the file for a
   construct that is not there."
  [names options-for msg]
  (let [fields (mapv #(render-field names options-for (:id msg) %) (:fields msg))
        live (into #{} (keep :oneof) fields)]
    {:proto-name (:proto-name msg)
     :fields fields
     :oneofs (into [] (comp (filter #(contains? live (:name %)))
                            (map #(select-keys % [:name :required])))
                   (:oneofs msg))}))

(defn render-group
  "One projected group as the emitter's file.

   `options-for` is called as `(options-for msg-id fld)` and returns that
   field's option strings. It is a PARAMETER rather than a require so this
   namespace stays free of the constraint vocabulary, and so a caller that
   wants no annotations at all passes a function that says so out loud instead
   of silently getting none. The message id rides along only so a refusal
   inside it can name the field it is about.

   `imports` is a parameter for the same reason: what a file must import is a
   property of what its options say, and that is the caller's fact."
  [g options-for imports]
  {:package (:package g)
   :imports (vec imports)
   :enums (mapv #(select-keys % [:proto-name :values]) (:enums g))
   :messages (mapv #(render-message (names-in-group g) options-for %) (:messages g))})

(m/=> render-group
      [:=> [:cat projection/projected-group
            [:=> [:cat [:string {:min 1}] db/field] [:sequential :string]]
            [:sequential [:string {:min 1}]]]
       emit/emitted-file])
