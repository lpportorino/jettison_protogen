(ns protocol-gen.verify
  "The INDEPENDENT ORACLE. Reads a `FileDescriptorSet` protoc built from the
   emitted text and judges it against the inputs the generator was given.

   IT SHARES NO CODE WITH THE GENERATOR, and that is the whole of its value: an
   oracle that imports the thing it judges can only ever agree with it. It
   re-derives what SHOULD be emitted from the policy, the database, the mints
   and the registry, using its own small implementation of that question, and
   compares that against what protoc actually parsed. Nothing under
   `protocol-gen`'s `src` is required here, and the canary greps this file for
   such a require and fails on one.

   THE DESCRIPTOR RATHER THAN THE TEXT, deliberately. A consumer that generates
   code reads a descriptor; a check that read the emitted text would pass over
   a file protoc cannot compile, and would say nothing at all about whether a
   validation annotation survived compilation. Constraints are read out of the
   parsed options' UNKNOWN FIELDS — the extension is not linked in here, so its
   presence is checked by field number rather than by type, which needs no
   dependency on the validation schema at all.

   THE SAME DISCIPLINE ON THE RUST SIDE, which has no descriptor to read. The
   `rust-access` mode does not parse the emitted module either: it judges a
   DUMP that a rustc-compiled harness produced by calling the module's own
   public API. So a module rustc cannot compile, or one whose API no longer
   answers, is a fault rather than a silently-empty comparison — and what is
   judged is the ANSWER a consumer would get, not the text a regex could
   misread.

   EXIT CODES SEPARATE A VERDICT FROM A FAULT: 0 clean, 1 findings, 2 the check
   could not run. A suite that accepted \"non-zero\" would take a stack trace as
   proof that a clause fired."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [com.google.protobuf DescriptorProtos$DescriptorProto
            DescriptorProtos$EnumDescriptorProto
            DescriptorProtos$EnumValueDescriptorProto
            DescriptorProtos$FieldDescriptorProto
            DescriptorProtos$FileDescriptorProto
            DescriptorProtos$FileDescriptorSet]))

(set! *warn-on-reflection* true)

(def buf-validate-field-extension
  "The extension number protovalidate hangs its field rules on. Checked by
   NUMBER because the extension is not linked into this oracle: an unlinked
   extension is preserved verbatim in the parsed message's unknown fields, so
   its presence is decidable without depending on the schema that declares it."
  1159)

(def descriptor-type-of
  "Database field type -> the descriptor type protoc must report for it. A
   substituted type is as much a wire break as a substituted number, so the
   comparison covers both."
  {:double "TYPE_DOUBLE" :float "TYPE_FLOAT" :int32 "TYPE_INT32"
   :int64 "TYPE_INT64" :uint32 "TYPE_UINT32" :uint64 "TYPE_UINT64"
   :bool "TYPE_BOOL" :string "TYPE_STRING" :bytes "TYPE_BYTES"
   :enum "TYPE_ENUM" :message "TYPE_MESSAGE"})

(defn read-descriptor-set
  "Parse the `FileDescriptorSet` at `path`."
  [^String path]
  (with-open [in (io/input-stream path)]
    (DescriptorProtos$FileDescriptorSet/parseFrom in)))

(defn- field-facts
  "One descriptor field as plain data."
  [^DescriptorProtos$FieldDescriptorProto f]
  {:number (.getNumber f)
   :type (str (.getType f))
   :repeated (= "LABEL_REPEATED" (str (.getLabel f)))
   :constrained (.hasField (.getUnknownFields (.getOptions f))
                           buf-validate-field-extension)})

(defn descriptor-index
  "Every message and enum in `fds`, keyed by fully-qualified name.

   Only the files this run emitted are indexed: an import brings its own file
   into the set, and judging those would be judging the validation schema
   rather than the projection. `keep-files` is the set of file names to keep."
  [^DescriptorProtos$FileDescriptorSet fds keep-files]
  (reduce
   (fn [acc ^DescriptorProtos$FileDescriptorProto file]
     (if-not (contains? keep-files (.getName file))
       acc
       (let [pkg (.getPackage file)
             qualify (fn [n] (if (str/blank? pkg) n (str pkg "." n)))]
         (-> acc
             (update :messages into
                     (for [^DescriptorProtos$DescriptorProto m (.getMessageTypeList file)]
                       [(qualify (.getName m))
                        (into {} (map (juxt #(.getName ^DescriptorProtos$FieldDescriptorProto %)
                                            field-facts))
                              (.getFieldList m))]))
             (update :enums into
                     (for [^DescriptorProtos$EnumDescriptorProto e (.getEnumTypeList file)]
                       [(qualify (.getName e))
                        (vec (sort-by :number
                                      (for [v (.getValueList e)]
                                        {:name (.getName ^DescriptorProtos$EnumValueDescriptorProto v)
                                         :number (.getNumber ^DescriptorProtos$EnumValueDescriptorProto v)})))]))))))
   {:messages {} :enums {}}
   (.getFileList fds)))

(defn- flatten-id
  "The emitted name for a source id. A SECOND implementation of the generator's
   rule, on purpose — an oracle that imported the rule could not disagree with
   it, and disagreeing is its job."
  [id]
  (str/replace id "." "_"))

(defn- expected-fields
  "The fields a grant should produce, as name -> facts, drawn from the source
   rather than from anything the generator wrote."
  [msg registry-entry granted]
  (into {}
        (for [fld (:fields msg)
              :when (or (= :all granted) (contains? granted (:name fld)))]
          [(:name fld)
           {:number (or (:number fld) (get registry-entry (:name fld)))
            :type (descriptor-type-of (:type fld))
            :repeated (boolean (:repeated fld))
            :constrained (boolean (seq (:constraints fld)))}])))

(defn expected-index
  "What the emitted descriptor SHOULD contain, re-derived from the policy, the
   database, the mints and the registry."
  [policy database minted registry]
  (reduce
   (fn [acc g]
     (let [pkg (:package g)]
       (reduce
        (fn [a grant]
          (let [id (:message grant)
                msg (or (get-in database [:messages id])
                        (some-> (get minted id) (assoc :fields (:fields (get minted id)))))]
            (when-not msg
              (throw (ex-info "oracle: policy grants a message no input carries" {:message id})))
            (assoc-in a [:messages (str pkg "." (flatten-id id))]
                      (expected-fields msg (get registry id) (:fields grant)))))
        (update acc :enums into (map #(str pkg "." (flatten-id %))) (:enums g []))
        (:grants g))))
   {:messages {} :enums #{}}
   (:groups policy)))

(defn- message-findings
  "Findings for one message: a field the descriptor is missing, a field it
   carries that nothing granted, and any disagreement about a field's facts."
  [qualified expected actual]
  (concat
   (for [[nm want] (sort expected)
         :let [got (get actual nm)]
         :when (not= want got)]
     (if got
       (str qualified "." nm ": expected " (pr-str want) ", descriptor has " (pr-str got))
       (str qualified "." nm ": granted, and the emitted descriptor does not carry it")))
   (for [nm (sort (remove (set (keys expected)) (keys actual)))]
     (str qualified "." nm ": present in the emitted descriptor and granted by nothing"))))

(defn findings
  "Every disagreement between what the inputs say should be emitted and what
   protoc parsed out of the emitted text."
  [expected actual]
  (concat
   (mapcat (fn [[qualified want]]
             (if-let [got (get-in actual [:messages qualified])]
               (message-findings qualified want got)
               [(str qualified ": granted, and the emitted descriptor has no such message")]))
           (sort-by key (:messages expected)))
   (for [qualified (sort (remove (:messages expected) (keys (:messages actual))))]
     (str qualified ": present in the emitted descriptor and granted by nothing"))
   (for [qualified (sort (remove (:enums expected) (keys (:enums actual))))]
     (str qualified ": enum present in the emitted descriptor and granted by nothing"))
   (for [qualified (sort (remove (set (keys (:enums actual))) (:enums expected)))]
     (str qualified ": enum granted, and the emitted descriptor has no such enum"))))

(defn mirror-findings
  "Every disagreement between the permission mirror and the descriptor.

   The mirror is the generator's own CLAIM about what it emitted; checking it
   against protoc is what stops a correct schema shipping beside a mirror that
   describes a different one."
  [mirror actual]
  (for [[_group-id g] (:groups mirror)
        [id msg] (:messages g)
        [nm fld] (:fields msg)
        :let [qualified (str (:package g) "." (:proto-name msg))
              got (get-in actual [:messages qualified nm])]
        :when (not= (:number fld) (:number got))]
    (str "mirror " id "." nm " claims number " (:number fld)
         ", descriptor has " (pr-str (:number got)))))

(defn fixture-db-findings
  "Every disagreement between a hand-written descriptor DATABASE and protoc's
   own descriptor of the protos it claims to describe.

   THIS IS WHAT MAKES A HAND-WRITTEN FIXTURE HONEST. Every later assertion in
   the canary is a statement about the generator's behaviour over its input; if
   that input has drifted from the proto it claims to mirror, all of them are
   statements about nothing. Comparing against protoc gets the guarantee
   without running — or coupling to — the parser that owns the real database."
  [database actual]
  (let [db-msgs (into {} (for [[id msg] (:messages database)]
                           [id (into {} (for [f (:fields msg)]
                                          [(:name f)
                                           {:number (:number f)
                                            :type (descriptor-type-of (:type f))
                                            :repeated (boolean (:repeated f))
                                            :constrained (boolean (seq (:constraints f)))}]))]))
        db-enums (into {} (for [[id e] (:enums database)]
                            [id (vec (sort-by :number
                                              (map #(select-keys % [:name :number])
                                                   (:values e))))]))]
    (concat
     (mapcat (fn [[id want]]
               (if-let [got (get-in actual [:messages id])]
                 (message-findings id want got)
                 [(str id ": in the fixture database and not in the compiled protos")]))
             (sort-by key db-msgs))
     (for [id (sort (remove (set (keys db-msgs)) (keys (:messages actual))))]
       (str id ": in the compiled protos and not in the fixture database"))
     (for [[id want] (sort-by key db-enums)
           :let [got (get-in actual [:enums id])]
           :when (not= want got)]
       (str id ": fixture database has " (pr-str want) ", compiled protos have "
            (pr-str got)))
     (for [id (sort (remove (set (keys db-enums)) (keys (:enums actual))))]
       (str id ": enum in the compiled protos and not in the fixture database")))))

(defn parse-access-dump
  "The lines of a Rust access dump as `{[group source-id] {:read b :write b}}`.

   THE DUMP IS WHAT THE EMITTED MODULE ANSWERED, not what it says. A canary
   compiles each module with rustc together with a harness that walks
   `MESSAGES` and prints `GROUP`, `source_id()`, `may_read()` and `may_write()`
   for every one — so what reaches here has been through the Rust compiler and
   the module's own public API, which is the same discipline this oracle
   applies to a `.proto` by reading protoc's descriptor rather than the text.

   A LINE IT CANNOT PARSE IS A FAULT, NOT A FINDING, and the caller exits 2 on
   one: a dump whose shape changed means the harness or the module moved, and
   scoring that as a disagreement about ACCESS would name the wrong defect."
  [text]
  (reduce
   (fn [acc line]
     (let [parts (str/split line #"\t")]
       (when (not= 4 (count parts))
         (throw (ex-info "access dump line is not four tab-separated fields"
                         {:line line :fields (count parts)})))
       (let [[group id read-s write-s] parts
             flag (fn [v] (case v
                            "true" true
                            "false" false
                            (throw (ex-info "access dump flag is not a Rust bool"
                                            {:line line :value v}))))]
         (assoc acc [group id] {:read (flag read-s) :write (flag write-s)}))))
   {}
   (remove str/blank? (str/split-lines text))))

(defn expected-access
  "What each group's Rust access module SHOULD answer, re-derived from the
   POLICY ALONE, as `{[group source-id] {:read b :write b}}`.

   THE POLICY ALONE IS SUFFICIENT AND THAT IS THE POINT. A grant's `:access`
   IS the direction; nothing in the database, the mints or the registry bears
   on it. So this derivation cannot be led astray by the same input that led
   the generator astray, and it shares no code with the thing it judges."
  [policy]
  (into {}
        (for [g (:groups policy)
              grant (:grants g)]
          [[(name (:id g)) (:message grant)]
           {:read (contains? (:access grant) :read)
            :write (contains? (:access grant) :write)}])))

(defn access-findings
  "Every disagreement between what the policy granted and what the emitted
   Rust modules answered."
  [expected actual]
  (let [describe (fn [{may-read :read may-write :write}]
                   (str "read=" may-read " write=" may-write))]
    (concat
     (for [[[group id] want] (sort-by key expected)
           :let [got (get actual [group id])]
           :when (not= want got)]
       (if got
         (str group "/" id ": policy grants " (describe want)
              ", the emitted module answers " (describe got))
         (str group "/" id ": granted, and the emitted module answers for no such message")))
     (for [[group id] (sort (remove (set (keys expected)) (keys actual)))]
       (str group "/" id ": answered by the emitted module and granted by nothing")))))

(defn parse-tree-dump
  "The lines of a permission-tree dump as `{[group path] {:tag n :permission s
   :children n}}`.

   THE DUMP IS WHAT THE EMITTED STATIC HELD, not what its text says. A canary
   compiles the emitted fragment into a harness that declares `Permission` and
   `PermissionNode` itself, walks every group's tree through the `GROUPS` table
   and prints one line per node — so what reaches here has been through rustc
   and through the same const data a consumer's scanner would read.

   A LINE IT CANNOT PARSE IS A FAULT, NOT A FINDING, for the reason
   `parse-access-dump` gives: a dump whose shape changed means the harness or
   the emission moved, and scoring that as a disagreement about PERMISSION
   would name the wrong defect."
  [text]
  (reduce
   (fn [acc line]
     (let [parts (str/split line #"\t")]
       (when (not= 5 (count parts))
         (throw (ex-info "permission tree dump line is not five tab-separated fields"
                         {:line line :fields (count parts)})))
       (let [[group path tag perm children] parts
             number (fn [v] (try (Long/parseLong v)
                                 (catch NumberFormatException _
                                   (throw (ex-info "permission tree dump field is not an integer"
                                                   {:line line :value v})))))]
         (assoc acc [group path]
                {:tag (number tag) :permission perm :children (number children)}))))
   {}
   (remove str/blank? (str/split-lines text))))

(defn- source-fields
  "The fields message `id` declares, in NUMBER order, with a minted field's
   number taken from the registry.

   THE SOURCE AND NOT THE GRANT. A node's children are total over what the
   message declares, so this deliberately ignores the policy — the policy
   decides each field's PERMISSION one level up."
  [database minted registry id]
  (let [msg (or (get-in database [:messages id]) (get minted id))]
    (when-not msg
      (throw (ex-info "oracle: a tree names a message no input carries" {:message id})))
    (sort-by :number
             (for [f (:fields msg)]
               (assoc f :number (or (:number f) (get-in registry [id (:name f)])))))))

(defn- expected-children
  "The nodes below message `id`, as `[path facts]` pairs, re-derived from the
   source and the group's grants.

   A SECOND IMPLEMENTATION OF THE TREE RULE, on purpose. `grants` maps a
   message id to that grant's `:fields` — `:all` or a set of names — so a field
   the filter kept INHERITS and every other field of the source is DENIED, and
   a denied node is terminal. `seen` refuses a cycle rather than recursing for
   ever; the generator refuses one too, so reaching this throw means the
   generator emitted something it should have refused."
  [database minted registry grants seen id prefix]
  (when (contains? seen id)
    (throw (ex-info "oracle: the emitted tree expands a cyclic message"
                    {:message id :path (vec (sort seen))})))
  (let [wanted (get grants id)
        deeper (conj seen id)]
    (mapcat
     (fn [f]
       (let [granted? (boolean (or (= :all wanted)
                                   (and (set? wanted) (contains? wanted (:name f)))))
             descend? (and granted? (= :message (:type f)))
             path (str prefix ">" (:name f))]
         (cons [path {:tag (:number f)
                      :permission (if granted? "Inherit" "Deny")
                      :children (if descend?
                                  (count (source-fields database minted registry
                                                        (:type-ref f)))
                                  0)}]
               (when descend?
                 (expected-children database minted registry grants deeper
                                    (:type-ref f) path)))))
     (source-fields database minted registry id))))

(defn- expected-tree
  "What every group's emitted tree SHOULD hold, as `{[group path] facts}`,
   re-derived from the policy, the database, the mints and the registry."
  [policy database minted registry]
  (into {}
        (for [g (:groups policy)
              :let [grants (into {} (map (juxt :message :fields)) (:grants g))]
              grant (sort-by :message (:grants g))
              :let [id (:message grant)
                    kids (expected-children database minted registry grants #{} id id)]
              [path facts] (cons [id {:tag 0
                                      :permission "Allow"
                                      :children (count (source-fields database minted
                                                                      registry id))}]
                                 kids)]
          [[(name (:id g)) path] facts])))

(defn tree-findings
  "Every disagreement between the tree the inputs describe and the tree the
   emitted statics held."
  [expected actual]
  (let [describe (fn [f] (str "tag=" (:tag f) " permission=" (:permission f)
                              " children=" (:children f)))]
    (concat
     (for [[k want] (sort-by key expected)
           :let [got (get actual k)]
           :when (not= want got)]
       (if got
         (str (first k) "/" (second k) ": the policy describes " (describe want)
              ", the emitted tree holds " (describe got))
         (str (first k) "/" (second k)
              ": described by the policy, and the emitted tree has no such node")))
     (for [k (sort (remove (set (keys expected)) (keys actual)))]
       (str (first k) "/" (second k)
            ": held by the emitted tree and described by nothing")))))

(defn parse-state-dump
  "The lines of a state-table dump as `{[group subsystem] permitted}`.

   THE DUMP IS WHAT THE EMITTED STATICS HELD. A canary compiles the fragment
   into a harness that walks `GROUP_STATE_SUBSYSTEMS` and prints one line per
   entry, so what reaches here has been through rustc and through the same const
   data a read path would narrow against.

   A LINE IT CANNOT PARSE IS A FAULT, NOT A FINDING, for the reason the two
   dumps beside it give."
  [text]
  (reduce
   (fn [acc line]
     (let [parts (str/split line #"\t")]
       (when (not= 3 (count parts))
         (throw (ex-info "state table dump line is not three tab-separated fields"
                         {:line line :fields (count parts)})))
       (let [[group subsystem permitted] parts]
         (assoc acc [group subsystem]
                (case permitted
                  "true" true
                  "false" false
                  (throw (ex-info "state table dump flag is not a Rust bool"
                                  {:line line :value permitted})))))))
   {}
   (remove str/blank? (str/split-lines text))))

(defn expected-state-table
  "What the emitted state table SHOULD hold, as `{[group subsystem] permitted}`,
   re-derived from the POLICY ALONE.

   THE POLICY ALONE IS SUFFICIENT AND THAT IS THE POINT — the same argument
   `expected-access` makes. A state subsystem resolves against nothing in the
   database, the mints or the registry, so this derivation cannot be led astray
   by the input that led the generator astray.

   IT IS THE CROSS PRODUCT, deliberately: every group against every DECLARED
   subsystem. A derivation that produced only the permitted pairs could not tell
   a table missing a denial from one that never had it."
  [policy]
  (let [declared (sort (:state-subsystems policy []))]
    (into {}
          (for [g (:groups policy)
                s declared]
            [[(name (:id g)) s]
             (boolean (some #{s} (:state-subsystems g [])))]))))

(defn state-findings
  "Every disagreement between the table the policy describes and the table the
   emitted statics held."
  [expected actual]
  (concat
   (for [[[group s] want] (sort-by key expected)
         :let [got (get actual [group s])]
         :when (not= want got)]
     (if (nil? got)
       (str group "/" s ": the policy describes permitted=" want
            ", and the emitted table carries no row for it")
       (str group "/" s ": the policy describes permitted=" want
            ", the emitted table holds permitted=" got)))
   (for [[group s] (sort (remove (set (keys expected)) (keys actual)))]
     (str group "/" s ": held by the emitted table and declared by nothing"))))

(defn- die
  [code lines]
  (run! #(binding [*out* *err*] (println %)) lines)
  (System/exit code))

(defn- parse-args
  [args]
  (when (odd? (count args))
    (die 2 ["verify: every flag takes a value"]))
  (into {} (map (fn [[f v]] [(keyword (str/replace f #"^--" "")) v])) (partition 2 args)))

(defn- read-edn
  [path what]
  (when-not (java.io.File/.isFile (io/file path))
    (die 2 [(str "verify: " what " not found: " path)]))
  (edn/read-string (slurp path)))

(defn- check-emitted
  "Judge an emitted descriptor set against the inputs that produced it."
  [{:keys [descriptor files policy db minted registry mirror]}]
  (doseq [[flag v] [["--descriptor" descriptor] ["--files" files] ["--policy" policy]
                    ["--db" db] ["--minted" minted] ["--registry" registry]
                    ["--mirror" mirror]]]
    (when-not v (die 2 [(str "verify emitted: " flag " is required")])))
  (let [actual (descriptor-index (read-descriptor-set descriptor)
                                 (set (str/split files #",")))
        expected (expected-index (read-edn policy "policy") (read-edn db "database")
                                 (read-edn minted "mints") (read-edn registry "registry"))
        checked (reduce + 0 (map count (vals (:messages expected))))
        found (concat (findings expected actual)
                      (mirror-findings (read-edn mirror "mirror") actual))]
    ;; NON-VACUITY FIRST. A comparison over zero fields reports exactly what a
    ;; clean one reports, so an empty population is a fault and not a pass.
    (when (or (zero? checked) (empty? (:messages actual)))
      (die 2 [(str "verify: CANNOT RUN — " checked " expected field(s), "
                   (count (:messages actual)) " descriptor message(s). "
                   "An empty side means discovery broke, not that there is "
                   "nothing to check.")]))
    (if (seq found)
      (die 1 (cons (str "verify: FAIL — " (count found) " finding(s):")
                   (map #(str "  " %) found)))
      (println (str "verify: clean — " (count (:messages expected)) " message(s), "
                    checked " field(s), " (count (:enums expected))
                    " enum(s) agreed between the inputs, the emitted descriptor "
                    "and the permission mirror")))))

(defn- check-fixture-db
  "Judge a hand-written descriptor database against protoc's descriptor of the
   protos it claims to describe."
  [{:keys [descriptor files db]}]
  (doseq [[flag v] [["--descriptor" descriptor] ["--files" files] ["--db" db]]]
    (when-not v (die 2 [(str "verify fixture-db: " flag " is required")])))
  (let [actual (descriptor-index (read-descriptor-set descriptor)
                                 (set (str/split files #",")))
        database (read-edn db "database")
        checked (reduce + 0 (map (comp count :fields) (vals (:messages database))))
        found (fixture-db-findings database actual)]
    (when (or (zero? checked) (empty? (:messages actual)))
      (die 2 [(str "verify fixture-db: CANNOT RUN — " checked " database field(s), "
                   (count (:messages actual)) " compiled message(s).")]))
    (if (seq found)
      (die 1 (cons (str "verify fixture-db: FAIL — " (count found) " finding(s):")
                   (map #(str "  " %) found)))
      (println (str "verify fixture-db: clean — " (count (:messages database))
                    " message(s), " checked " field(s), " (count (:enums database))
                    " enum(s) agreed between the fixture database and protoc")))))

(defn- check-rust-access
  "Judge the access facts the emitted Rust modules ANSWER against the policy
   that granted them."
  [{:keys [dump policy]}]
  (doseq [[flag v] [["--dump" dump] ["--policy" policy]]]
    (when-not v (die 2 [(str "verify rust-access: " flag " is required")])))
  (when-not (java.io.File/.isFile (io/file dump))
    (die 2 [(str "verify rust-access: dump not found: " dump)]))
  (let [expected (expected-access (read-edn policy "policy"))
        ;; THE READ IS INSIDE THE GUARD, not only the parse. An unreadable file
        ;; and a malformed line are the same class — the check could not run —
        ;; and letting one of them escape as an uncaught throw would report a
        ;; FAULT with the exit code reserved for a FINDING.
        actual (try (parse-access-dump (slurp dump))
                    (catch Exception e
                      (die 2 [(str "verify rust-access: CANNOT RUN — " (ex-message e)
                                   " " (pr-str (ex-data e)))])))
        found (access-findings expected actual)]
    ;; NON-VACUITY FIRST, on BOTH sides and each on its own. A comparison over
    ;; an empty population reports exactly what a clean one reports, and either
    ;; side going dark alone is invisible to a union floor.
    (when (or (empty? expected) (empty? actual))
      (die 2 [(str "verify rust-access: CANNOT RUN — " (count expected)
                   " granted message(s), " (count actual)
                   " answered by the emitted module(s). An empty side means "
                   "discovery broke, not that there is nothing to check.")]))
    (if (seq found)
      (die 1 (cons (str "verify rust-access: FAIL — " (count found) " finding(s):")
                   (map #(str "  " %) found)))
      (println (str "verify rust-access: clean — " (count expected)
                    " granted message(s) agreed between the policy and the "
                    "access direction each emitted Rust module answers")))))

(defn- check-permission-tree
  "Judge the nested permission tree the emitted statics HELD against the
   policy, the database, the mints and the registry that produced it."
  [{:keys [dump policy db minted registry]}]
  (doseq [[flag v] [["--dump" dump] ["--policy" policy] ["--db" db]
                    ["--minted" minted] ["--registry" registry]]]
    (when-not v (die 2 [(str "verify permission-tree: " flag " is required")])))
  (when-not (java.io.File/.isFile (io/file dump))
    (die 2 [(str "verify permission-tree: dump not found: " dump)]))
  (let [expected (try (expected-tree (read-edn policy "policy") (read-edn db "database")
                                     (read-edn minted "mints") (read-edn registry "registry"))
                      (catch Exception e
                        (die 2 [(str "verify permission-tree: CANNOT RUN — " (ex-message e)
                                     " " (pr-str (ex-data e)))])))
        ;; THE READ IS INSIDE THE GUARD, not only the parse — the reason
        ;; `check-rust-access` gives: an unreadable file and a malformed line
        ;; are both "the check could not run", and letting one escape would
        ;; report a FAULT with the exit code reserved for a FINDING.
        actual (try (parse-tree-dump (slurp dump))
                    (catch Exception e
                      (die 2 [(str "verify permission-tree: CANNOT RUN — " (ex-message e)
                                   " " (pr-str (ex-data e)))])))
        found (tree-findings expected actual)]
    ;; NON-VACUITY FIRST, on BOTH sides and each on its own.
    (when (or (empty? expected) (empty? actual))
      (die 2 [(str "verify permission-tree: CANNOT RUN — " (count expected)
                   " described node(s), " (count actual)
                   " held by the emitted tree(s). An empty side means discovery "
                   "broke, not that there is nothing to check.")]))
    (if (seq found)
      (die 1 (cons (str "verify permission-tree: FAIL — " (count found) " finding(s):")
                   (map #(str "  " %) found)))
      (println (str "verify permission-tree: clean — " (count expected)
                    " node(s) agreed between the inputs and the permission tree "
                    "each emitted static holds")))))

(defn- check-state-table
  "Judge the state subsystem table the emitted statics HELD against the policy
   that declared it."
  [{:keys [dump policy]}]
  (doseq [[flag v] [["--dump" dump] ["--policy" policy]]]
    (when-not v (die 2 [(str "verify state-table: " flag " is required")])))
  (when-not (java.io.File/.isFile (io/file dump))
    (die 2 [(str "verify state-table: dump not found: " dump)]))
  (let [expected (expected-state-table (read-edn policy "policy"))
        actual (try (parse-state-dump (slurp dump))
                    (catch Exception e
                      (die 2 [(str "verify state-table: CANNOT RUN — " (ex-message e)
                                   " " (pr-str (ex-data e)))])))
        found (state-findings expected actual)]
    ;; NON-VACUITY FIRST, on BOTH sides and each on its own. A policy declaring
    ;; no state axis emits an EMPTY table, which is the honest rendering of that
    ;; policy and is also indistinguishable from a table that went dark — so it
    ;; is a fault to judge rather than something to report clean.
    (when (or (empty? expected) (empty? actual))
      (die 2 [(str "verify state-table: CANNOT RUN — " (count expected)
                   " described row(s), " (count actual)
                   " held by the emitted table. An empty side means the policy "
                   "declares no state axis, or discovery broke — neither is a "
                   "clean verdict.")]))
    (if (seq found)
      (die 1 (cons (str "verify state-table: FAIL — " (count found) " finding(s):")
                   (map #(str "  " %) found)))
      (println (str "verify state-table: clean — " (count expected)
                    " row(s) agreed between the policy and the state subsystem "
                    "table the emitted statics hold")))))

(def ^:private modes
  {"emitted" check-emitted
   "fixture-db" check-fixture-db
   "rust-access" check-rust-access
   "permission-tree" check-permission-tree
   "state-table" check-state-table})

(defn -main
  "Judge an emitted artefact against its source. Three modes:

     emitted    --descriptor --files --policy --db --minted --registry --mirror
                What the generator wrote, against the inputs it was given.
     fixture-db --descriptor --files --db
                A hand-written descriptor database, against protoc's own
                descriptor of the protos it claims to describe.
     rust-access --dump --policy
                The access DIRECTION each emitted Rust module answers — read
                out of a rustc-compiled harness that calls the module's own
                public API — against the policy that granted it.
     permission-tree --dump --policy --db --minted --registry
                The NESTED permission tree each emitted static holds — read out
                of a rustc-compiled harness that walks it — against the inputs
                that produced it. Judges every node's tag, permission and
                DIRECT CHILD COUNT, so a message described without a child per
                field its source declares is a finding rather than a smaller
                clean run.
     state-table --dump --policy
                The STATE SUBSYSTEM TABLE the emitted statics hold — read out of
                a rustc-compiled harness that walks it — against the policy that
                declared it. Judges the CROSS PRODUCT of groups and declared
                subsystems, so a missing row is a finding rather than a narrower
                clean run."
  [& args]
  (let [[mode & rest-args] args
        handler (or (get modes mode)
                    (die 2 [(str "verify: unknown mode " (pr-str mode)
                                 "; expected one of " (pr-str (vec (sort (keys modes)))))]))]
    (handler (parse-args rest-args))))
