(ns lvgl-codegen.core
  "CLI entrypoint: pinned design-tokens manifest + EDN screens → protobuf
   UI AST (.pb files). Pipeline: read manifest + EDN → validate (Malli)
   → resolve components → semantic validate → expand → emit (.pb)."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [lvgl-codegen.component :as component]
            [lvgl-codegen.emit-proto :as emit-proto]
            [lvgl-codegen.expand :as expand]
            [lvgl-codegen.patch :as patch]
            [lvgl-codegen.proto-ser :as proto-ser]
            [lvgl-codegen.renderer-caps :as renderer-caps]
            [lvgl-codegen.schema :as schema]
            [malli.core :as m])
  (:import [com.google.protobuf ByteString]
           [java.util Base64 Base64$Decoder Base64$Encoder]))

(set! *warn-on-reflection* true)

;; ── IR ↔ EDN byte codec (tree-patch baseline) ──────────────────────────────
;; The patch-baseline `.ir.edn` round-trips the emitted IR through EDN. Most of
;; the IR is plain data, but R5a's CmdSpec carries a pronto bytes field — a
;; ByteString (the cmd.Root template) that has no default EDN literal. The
;; baseline codec walks the IR converting each ByteString to a base64 marker
;; map `{::bytes-b64 "<base64>"}` on write and back to a ByteString on read, so
;; the IR stays EDN-round-trippable AND the read-back value compares `.equals`
;; to the live one (the diff baseline must match value-for-value). A plain data
;; marker (not a tagged literal) keeps the reader a pure clojure.edn pass — no
;; global *data-readers* mutation.
(defn- ir->edn-string
  "EDN string of an IR map, ByteString fields encoded as base64 markers."
  [ir]
  (pr-str (walk/postwalk (fn [form]
                           (if (instance? ByteString form)
                             {::bytes-b64 (Base64$Encoder/.encodeToString
                                           (Base64/getEncoder)
                                           (ByteString/.toByteArray ^ByteString form))}
                             form))
                         ir)))
(m/=> ir->edn-string [:=> [:cat [:map-of :keyword some?]] [:string {:min 1}]])

(defn- edn-string->ir
  "Parse an IR EDN string, decoding base64 ByteString markers back to
   ByteStrings (the inverse of `ir->edn-string`)."
  [^String s]
  (walk/postwalk (fn [form]
                   (if (and (map? form) (contains? form ::bytes-b64))
                     (ByteString/copyFrom (Base64$Decoder/.decode (Base64/getDecoder)
                                                                  ^String
                                                                  (::bytes-b64 form)))
                     form))
                 (edn/read-string s)))
(m/=> edn-string->ir [:=> [:cat [:string {:min 1}]] [:map-of :keyword some?]])

(defn load-edn
  "Read and parse an EDN file from the given path.
   Pure clojure.edn data reader — screens/tokens are data, never code,
   so #=() eval forms are structurally unreadable."
  [^String path]
  (with-open [rdr (java.io.PushbackReader. (io/reader path))] (edn/read rdr)))

(def design-tokens-path
  "The pinned design-tokens manifest — protogen owns `tokens.edn` and the
   semantic→primitive resolver and emits this fully-resolved projection
   (`output/manifests/design-tokens.json`); this repo is a pure consumer.
   Repo-root relative like every default path here; overridable per call
   (tests pass fixture manifests)."
  "../../output/manifests/design-tokens.json")

(def components-path
  "The authored composition home (class macros + components) — stays in
   THIS repo beside the screens it composes."
  "edn/components.edn")

(def ^:private resolved-token-map
  "A design-token map — token keyword → resolved manifest entry
   ({:kind k :dark v :light v}). The one shape BOTH the pinned public
   manifest and the private overlay parse to, so the overlay is checkable
   at its own boundary rather than three layers downstream."
  [:map-of :keyword schema/resolved-token])

(defn load-private-tokens
  "Read the CALLER-SUPPLIED private design-token overlay at `path` into the
   same shape the public manifest parses to — {token-kw {:kind kw :dark v
   :light v}}. `path` may be nil (no overlay): a NIL or ABSENT path yields {},
   the overlay being optional by contract. A PRESENT file is shape-validated
   STRICTLY and throws when malformed — a private token that silently vanished
   would fall back to the public design with nothing said, which is exactly the
   failure this overlay exists to make impossible."
  [^String path]
  (if-not (and path (java.io.File/.isFile (io/file path)))
    {}
    (let [tokens (load-edn path)]
      (when-let [errors (m/explain resolved-token-map tokens)]
        (throw (ex-info (str "private design-token overlay is malformed: " path)
                        {:path path :errors errors})))
      tokens)))

(defn merge-private-tokens
  "Overlay `private` design tokens onto the `public` manifest tokens under a
   DISJOINT-KEY contract, returning the merged token map. A private token
   reusing a public name is a HARD error: the overlay adds proprietary
   tokens the public manifest may not carry — it never re-defines a name
   protogen already owns. Allowing the shadow would give one token name two
   different definitions depending on which repo you read, with nothing
   anywhere saying so."
  [public private]
  (let [colliding (vec (sort (filter (set (keys public)) (keys private))))]
    (when (seq colliding)
      (throw (ex-info (str "private design-token overlay shadows public token(s): "
                           (str/join ", " (map name colliding))
                           " — the overlay must be disjoint from the pinned"
                           " manifest; rename the private token(s)")
                      {:colliding-tokens colliding})))
    (merge public private)))

(defn load-design-tokens
  "Parse a design-tokens manifest into {token-kw {:kind kw :dark v :light
   v}}, then merge the CALLER-SUPPLIED private overlay (`private-overlay-path`,
   or nil for none) over it. Values arrive concrete for BOTH modes (equal when
   the token is mode-invariant): hex strings (color), font symbol strings
   (font), ints (spacing/radius/opacity/border-width), keyword-keyed maps
   (shadow). An empty PUBLIC token set is a hard error — a vacuous manifest
   must never silently un-token the authoring surface (an empty private
   overlay, by contrast, is the normal case)."
  [^String manifest-path private-overlay-path]
  (let [raw (with-open [rdr (io/reader manifest-path)] (json/read rdr :key-fn keyword))
        tokens (into {} (map (fn [[k v]] [k (update v :kind keyword)])) (:tokens raw))]
    (when (empty? tokens)
      (throw (ex-info "design-tokens manifest carries no tokens" {:path manifest-path})))
    (merge-private-tokens tokens (load-private-tokens private-overlay-path))))

(defn load-ui-defs
  "Load + assemble the UI definition map the pipeline consumes: the pinned
   design-tokens manifest under :tokens (concrete per-mode values) merged
   with the authored components file (:class-defs + :components —
   composition, not values). The private token overlay is CALLER-SUPPLIED
   (`private-overlay-path`, or nil for none): protogen hosts the merge
   mechanism but never names a consumer-private file — the consumer passes its
   own overlay path. Fails loud when either public input is missing — never a
   silent fallback."
  ([^String tokens-manifest-path private-overlay-path]
   (load-ui-defs tokens-manifest-path components-path private-overlay-path))
  ([^String tokens-manifest-path ^String comps-path private-overlay-path]
   (let [_ (when-not (java.io.File/.isFile (io/file tokens-manifest-path))
             (throw (ex-info (str "design-tokens manifest not found: "
                                  tokens-manifest-path
                                  " — is the protogen submodule checked out?")
                             {:tokens-manifest tokens-manifest-path})))
         tokens {:tokens (load-design-tokens tokens-manifest-path private-overlay-path)}
         _ (when-not (java.io.File/.isFile (io/file comps-path))
             (throw (ex-info (str "components file not found: " comps-path)
                             {:components-path comps-path})))
         comps (load-edn comps-path)]
     (when-let [errors (schema/validate-components-file comps)]
       (throw (ex-info "components.edn validation failed"
                       {:path comps-path :errors errors})))
     (merge tokens comps))))

(defn validate-class-defs!
  "Eagerly parse + expand every :class-defs macro at token-load: a broken
   macro otherwise surfaces only at its first @use — an unused one never."
  [tokens]
  (doseq [[macro-name class-str] (:class-defs tokens)]
    (try (doseq [parsed (expand/parse-class-string class-str)]
           (expand/resolve-prop-value tokens parsed))
         (catch Exception e
           (throw (ex-info (str "Broken :class-defs macro @" (name macro-name)
                                ": " (ex-message e))
                           {:macro macro-name :class class-str}
                           e))))))

(defn- validate-asset-paths!
  "Every P:-drive asset reference (:src \"P:icons/…\") must resolve under
   the canonical assets/ tree AT BUILD TIME — a missing asset fails codegen
   here with the offending paths, never as a silent blank on the target."
  [screen output-path]
  (let [missing (volatile! [])]
    (walk/postwalk (fn [form]
                     (when (and (map? form)
                                (string? (:src form))
                                (String/.startsWith ^String (:src form) "P:"))
                       (let [rel (subs (:src form) 2)]
                         (when-not (java.io.File/.isFile (io/file "assets" rel))
                           (vswap! missing conj (:src form)))))
                     form)
                   screen)
    (when (seq @missing)
      (throw (ex-info "Asset reference(s) do not resolve under assets/"
                      {:missing @missing :output output-path})))))

(defn- font-ref-resolves?
  "True when an emitted PROP_TEXT_FONT string resolves on the target:
   a compiled-in renderer font, a binary font asset
   (assets/fonts/<name>.bin), or a TinyTTF asset — '<family>_<size>'
   against assets/fonts/<family>.ttf (the renderer rasterizes at <size>)."
  [^String font-name]
  (boolean (or (contains? (renderer-caps/compiled-in-fonts) font-name)
               (java.io.File/.isFile (io/file "assets/fonts" (str font-name ".bin")))
               (let [idx (str/last-index-of font-name "_")]
                 (and idx
                      (pos? (long idx))
                      (re-matches #"\d+" (subs font-name (inc (long idx))))
                      (java.io.File/.isFile (io/file "assets/fonts"
                                                     (str (subs font-name 0 (long idx))
                                                          ".ttf"))))))))

(defn- validate-font-refs!
  "Every PROP_TEXT_FONT the emitted IR carries must resolve AT BUILD TIME —
   compiled-in, .bin asset, or .ttf asset (parallel to
   validate-asset-paths!). A typo'd font otherwise falls back to the
   renderer's default SILENTLY on the target."
  [ir output-path]
  (let [missing (vec (remove font-ref-resolves? (renderer-caps/screen-fonts ir)))]
    (when (seq missing)
      (throw (ex-info (str "Font reference(s) resolve neither to a "
                           "compiled-in renderer font nor to an "
                           "assets/fonts/ .bin/.ttf asset: " missing)
                      {:missing missing
                       :compiled-in (sort (renderer-caps/compiled-in-fonts))
                       :output output-path})))))

(defn join-class-vectors
  "Normalize the :class vector authoring form ([\"flex\" \"w-48\"]) to the
   canonical class string on every widget — downstream (components, expand)
   sees exactly ONE class representation."
  [screen]
  (walk/postwalk (fn [form]
                   (if (and (map? form) (vector? (:class form)))
                     (update form :class #(str/join " " %))
                     form))
                 screen))

(defn- emit-patch-artifacts!
  "Tree-patch producer side (docs/lvgl-factory/10-TREE-PATCH-DESIGN.md):
   when a baseline IR (<screen>.ir.edn beside the .pb) AND the previous
   .pb both exist, diff baseline → new IR and write <screen>.patch.pb
   (ScreenPatch{base_hash target_hash ops}); on :unchanged/:full-reload
   (or no baseline) any stale patch artifact is REMOVED — a lingering
   patch would lie about the current base. Always refreshes the baseline.
   MUST run BEFORE the new .pb overwrites the old one (the base hash is
   the OLD bytes)."
  [^String output-path new-ir ^bytes new-bytes]
  (let [base-path (str/replace output-path #"\.pb$" "")
        ir-path (str base-path ".ir.edn")
        patch-path (str base-path ".patch.pb")
        ir-file (io/file ir-path)
        pb-file (io/file output-path)]
    (if (and (java.io.File/.isFile ir-file) (java.io.File/.isFile pb-file))
      (let [base-ir (edn-string->ir (slurp ir-file))
            base-bytes (java.nio.file.Files/readAllBytes (java.io.File/.toPath pb-file))
            {:keys [result ops reason]} (patch/diff-screen base-ir new-ir)]
        (if (= result :patch)
          (let [pb (patch/patch-ir (emit-proto/fnv1a-32-bytes base-bytes)
                                   (emit-proto/fnv1a-32-bytes new-bytes)
                                   ops)]
            (proto-ser/write-bytes! (proto-ser/patch->bytes pb) patch-path)
            (println (str "    patch: " (count ops)
                          " op(s) → " (java.io.File/.getName (io/file patch-path)))))
          (do (java.nio.file.Files/deleteIfExists (java.io.File/.toPath (io/file
                                                                         patch-path)))
              (when (= result :full-reload)
                (println
                 (str "    patch: not patchable (" (name reason) ") — full reload"))))))
      (java.nio.file.Files/deleteIfExists (java.io.File/.toPath (io/file patch-path))))
    (spit ir-path (ir->edn-string new-ir))
    nil))

(defn process-screen
  "Process a single screen: validate → resolve components → semantic validate
   → expand → emit → diff against the persisted baseline (patch artifacts)
   → write .pb. Returns the number of bytes written."
  [tokens components screen ^String output-path]
  (when-let [errors (schema/validate-screen screen)]
    (throw (ex-info "Screen validation failed" {:errors errors :output output-path})))
  (let [resolved (component/resolve-components components (join-class-vectors screen))]
    (when-let [errors (schema/validate-screen-semantics resolved)]
      (throw (ex-info "Screen semantic validation failed"
                      {:errors errors :output output-path})))
    (validate-asset-paths! resolved output-path)
    (let [expanded (expand/expand-screen tokens resolved)
          ir (emit-proto/emit-screen expanded)]
      (validate-font-refs! ir output-path)
      (renderer-caps/check-headroom! ir output-path)
      (proto-ser/validate-ir! ir output-path)
      (let [^bytes pb-bytes (proto-ser/ir->bytes ir)]
        (emit-patch-artifacts! output-path ir pb-bytes)
        (proto-ser/write-bytes! pb-bytes output-path)))))

(defn -main
  "Parse CLI args and run the codegen pipeline.
   Usage: --tokens <design-tokens.json> --input <dir> --output <dir>
          [--private-tokens <overlay.edn>]
   (--tokens names the pinned protogen design-tokens manifest; see
   `design-tokens-path` for the default location. --private-tokens is the
   OPTIONAL consumer overlay path — omitted means no overlay.)"
  [& args]
  (let [arg-map (apply hash-map args)
        tokens-path (get arg-map "--tokens")
        private-tokens-path (get arg-map "--private-tokens")
        input-dir (get arg-map "--input")
        output-dir (get arg-map "--output")]
    (when-not (and tokens-path input-dir output-dir)
      (binding [*out* *err*]
        (println "Usage: --tokens <design-tokens.json> --input <dir> --output <dir> [--private-tokens <overlay.edn>]"))
      (System/exit 1))
    (java.io.File/.mkdirs (io/file output-dir))
    (let [tokens (load-ui-defs tokens-path private-tokens-path)]
      (when-let [errors (schema/validate-tokens tokens)]
        (binding [*out* *err*]
          (println "Token validation failed:")
          (println errors))
        (System/exit 2))
      (validate-class-defs! tokens)
      (let [components (component/load-components (:components tokens))
            screen-files
            (->> (java.io.File/.listFiles (io/file input-dir))
                 (filter #(String/.endsWith (java.io.File/.getName ^java.io.File %) ".edn"))
                 (sort-by #(java.io.File/.getName ^java.io.File %)))]
        (doseq [^java.io.File screen-file screen-files]
          (try (let [screen (load-edn (java.io.File/.getPath screen-file))
                     base-name
                     (String/.replaceFirst (java.io.File/.getName screen-file) "\\.edn$" "")
                     output-path (str output-dir "/" base-name ".pb")
                     byte-count (process-screen tokens components screen output-path)]
                 (println (str "  "
                               (java.io.File/.getName screen-file)
                               " → "
                               base-name
                               ".pb"
                               " ("
                               byte-count
                               " bytes)")))
               (catch Exception e
                 ;; Every downstream throw must name the screen file — a bare
                 ;; mid-batch exception forces the author to bisect screens.
                 (throw (ex-info
                         (str (java.io.File/.getName screen-file) ": " (ex-message e))
                         (assoc (or (ex-data e) {})
                                :screen-file
                                (java.io.File/.getName screen-file))
                         e)))))
        (println (str "Done. Processed " (count screen-files) " screen(s)."))))))

;; -- Function schema registrations --
;; File-local arg schemas for arrow-spec tightening.
(def ^:private file-path
  "Non-empty filesystem path string (for io/reader, File construction)."
  schema/ne-string)

(def ^:private loaded-components
  "Result of load-components: component-name -> component definition map."
  [:map-of [:string {:min 1}] map?])

(def ^:private resolvable-screen
  "Component-resolved screen carrying a :tree widget map (consumed by
   validate-screen -> resolve-components -> expand-screen)."
  [:map [:tree map?]])

(m/=> load-edn [:=> [:cat file-path] [:map-of :keyword :any]])

(m/=> load-private-tokens [:=> [:cat [:maybe file-path]] resolved-token-map])

(m/=> merge-private-tokens
      [:=> [:cat resolved-token-map resolved-token-map] resolved-token-map])

(m/=> load-design-tokens
      [:=> [:cat file-path [:maybe file-path]] [:map-of :keyword [:map [:kind :keyword]]]])

(m/=> load-ui-defs
      [:function
       [:=> [:cat file-path [:maybe file-path]] :map]
       [:=> [:cat file-path file-path [:maybe file-path]] :map]])

(m/=> join-class-vectors [:=> [:cat resolvable-screen] resolvable-screen])

(m/=> validate-class-defs! [:=> [:cat schema/tokens-schema] :nil])

(m/=> validate-asset-paths! [:=> [:cat resolvable-screen file-path] :nil])

(m/=> font-ref-resolves? [:=> [:cat [:string {:min 1}]] :boolean])

(m/=> validate-font-refs!
      [:=> [:cat [:map [:root [:map [:type :keyword]]]] file-path] :nil])

(m/=> emit-patch-artifacts!
      [:=> [:cat file-path [:map [:root [:map [:type :keyword]]]] bytes?] :nil])

(m/=> process-screen
      [:=> [:cat schema/tokens-schema loaded-components resolvable-screen file-path]
       nat-int?])

(m/=> -main [:=> [:cat [:* file-path]] :nil])