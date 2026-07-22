(ns lvgl-codegen.core
  "CLI entrypoint: EDN tokens + screens → protobuf UI AST (.pb files).
   Pipeline: read EDN → validate (Malli) → resolve components
   → semantic validate → expand → emit (.pb)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [lvgl-codegen.component :as component]
            [lvgl-codegen.emit-proto :as emit-proto]
            [lvgl-codegen.expand :as expand]
            [lvgl-codegen.patch :as patch]
            [lvgl-codegen.proto-ser :as proto-ser]
            [lvgl-codegen.registry :as registry]
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

(defn load-ui-defs
  "Load + assemble the UI definition map the pipeline consumes: the design
   tokens file (layers 1-2 VALUES only) merged with its sibling
   components.edn (:class-defs + :components — composition, not values).
   Fails loud when tokens still carries evicted keys or the components
   file is missing — never a silent fallback."
  [^String tokens-path]
  (let [tokens (load-edn tokens-path)
        evicted (filterv #(contains? tokens %) [:class-defs :components])
        _ (when (seq evicted)
            (throw (ex-info (str "Tokens file carries evicted key(s) " evicted
                                 " — class macros + components "
                                 "live in the sibling components.edn")
                            {:tokens-path tokens-path :evicted evicted})))
        comp-file (io/file (java.io.File/.getParentFile (io/file tokens-path))
                           "components.edn")
        _ (when-not (java.io.File/.isFile comp-file)
            (throw (ex-info (str "components.edn not found beside tokens: "
                                 (java.io.File/.getPath comp-file))
                            {:tokens-path tokens-path})))
        comps (load-edn (java.io.File/.getPath comp-file))]
    (when-let [errors (schema/validate-components-file comps)]
      (throw (ex-info "components.edn validation failed"
                      {:path (java.io.File/.getPath comp-file) :errors errors})))
    (merge tokens comps)))

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
  (boolean (or (contains? renderer-caps/compiled-in-fonts font-name)
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
                       :compiled-in (sort renderer-caps/compiled-in-fonts)
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
   Usage: --tokens <path> --input <dir> --output <dir>"
  [& args]
  (let [arg-map (apply hash-map args)
        tokens-path (get arg-map "--tokens")
        input-dir (get arg-map "--input")
        output-dir (get arg-map "--output")]
    (when-not (and tokens-path input-dir output-dir)
      (binding [*out* *err*]
        (println "Usage: --tokens <path> --input <dir> --output <dir>"))
      (System/exit 1))
    (java.io.File/.mkdirs (io/file output-dir))
    (let [tokens (load-ui-defs tokens-path)]
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
  ::registry/ne-string)

(def ^:private loaded-components
  "Result of load-components: component-name -> component definition map."
  [:map-of [:string {:min 1}] map?])

(def ^:private resolvable-screen
  "Component-resolved screen carrying a :tree widget map (consumed by
   validate-screen -> resolve-components -> expand-screen)."
  [:map [:tree map?]])

(m/=> load-edn [:=> [:cat file-path] :map])

(m/=> load-ui-defs [:=> [:cat file-path] :map])

(m/=> join-class-vectors [:=> [:cat resolvable-screen] :map])

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

(m/=> -main [:=> [:cat [:* file-path]] :any])