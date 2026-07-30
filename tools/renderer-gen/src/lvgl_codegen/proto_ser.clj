(ns lvgl-codegen.proto-ser
  "Protobuf serialization boundary — screen IR ↔ protobuf binary bytes.
   Extracted from emit-proto to isolate the pronto/protobuf-java dependency
   and provide the deserialization half for roundtrip testing.

   Uses top-level defmapper — proto-java classes must be on classpath at
   load time (ensured by Makefile: codegen depends on proto-java)."
  (:require [lvgl-codegen.proto-schema :as proto-schema]
            [malli.core :as m]
            [malli.error :as me]
            [pronto.core :as pronto]
            [pronto.utils :as pronto-utils])
  (:import [java.nio.file CopyOption Files OpenOption Paths StandardCopyOption]
           [ui UiAst$Screen UiAst$ScreenPatch UiAst$StateUpdate]))

(set! *warn-on-reflection* true)

;; -- Top-level mapper (classes guaranteed on classpath) --
(pronto/defmapper lvgl-proto-mapper [UiAst$Screen UiAst$ScreenPatch UiAst$StateUpdate])

;; -- Serialization (screen IR → bytes) --
(defn ir->bytes
  "Convert an already-emitted proto IR map to protobuf binary bytes."
  [screen-ir]
  (let [proto-map (pronto/clj-map->proto-map lvgl-proto-mapper UiAst$Screen screen-ir)
        proto-obj (pronto-utils/proto-map->proto proto-map)]
    (com.google.protobuf.MessageLite/.toByteArray proto-obj)))

(defn state-update->bytes
  "Serialize a StateUpdate IR map — the host→WASM controller-binding inbound
   payload (`controls_update_state`):
   `{:values [{:name \"volume\" :int_value 70} …]}`."
  [update-ir]
  (let [proto-map (pronto/clj-map->proto-map lvgl-proto-mapper UiAst$StateUpdate update-ir)
        proto-obj (pronto-utils/proto-map->proto proto-map)]
    (com.google.protobuf.MessageLite/.toByteArray proto-obj)))
(m/=> state-update->bytes
      [:=> [:cat [:map [:values [:vector [:map [:name [:string {:min 1}]]]]]]] bytes?])

(defn patch->bytes
  "Serialize a ScreenPatch IR map ({:base_hash … :target_hash … :ops […]},
   built by lvgl-codegen.patch/patch-ir) to protobuf binary bytes — the
   controls_apply_patch inbound payload."
  [patch-ir]
  (let [proto-map (pronto/clj-map->proto-map lvgl-proto-mapper UiAst$ScreenPatch patch-ir)
        proto-obj (pronto-utils/proto-map->proto proto-map)]
    (com.google.protobuf.MessageLite/.toByteArray proto-obj)))
(m/=> patch->bytes
      [:=>
       [:cat
        [:map [:base_hash int?] [:target_hash int?]
         [:ops [:sequential [:map [:kind :keyword]]]]]] bytes?])

(defn bytes->patch
  "Deserialize ScreenPatch protobuf bytes to its IR map (test oracle)."
  [^bytes pb-bytes]
  (let [proto-obj (UiAst$ScreenPatch/parseFrom pb-bytes)
        proto-map (pronto/proto->proto-map lvgl-proto-mapper proto-obj)]
    (pronto/proto-map->clj-map proto-map)))
(m/=> bytes->patch
      [:=>
       [:cat bytes?]
       [:map [:base_hash int?] [:target_hash int?]
        [:ops [:sequential [:map [:kind :keyword]]]]]])

;; -- Deserialization (bytes → screen IR) --
(defn bytes->screen
  "Deserialize protobuf binary bytes to Clojure screen IR map."
  [^bytes pb-bytes]
  (let [proto-obj (UiAst$Screen/parseFrom pb-bytes)
        proto-map (pronto/proto->proto-map lvgl-proto-mapper proto-obj)]
    (pronto/proto-map->clj-map proto-map)))

;; -- File I/O wrappers --
(defn validate-ir!
  "proto-IR backstop: pronto must be unreachable as a validation layer —
   its errors name a proto field and nothing else. A failure here carries
   the Malli explain path instead."
  [expanded-screen output-path]
  (when-let [explanation (proto-schema/explain-proto-ir expanded-screen)]
    (throw (ex-info "Emitted proto IR failed schema validation"
                    {:output output-path :explain (me/humanize explanation)}))))
(m/=> validate-ir! [:=> [:cat map? [:string {:min 1}]] :nil])

(defn write-bytes!
  "Write bytes to a file ATOMICALLY (write a sibling .tmp, then rename
   onto the target). A concurrent reader — the web harness's file
   watcher, the visual harness, a deploy tar — can therefore never
   observe a torn half-written artifact. Returns byte count."
  [^bytes pb-bytes ^String output-path]
  (let [tmp-path (Paths/get (str output-path ".tmp") (into-array String []))
        path (Paths/get output-path (into-array String []))
        ^"[Ljava.nio.file.OpenOption;" opts (make-array OpenOption 0)]
    (Files/write tmp-path pb-bytes opts)
    (Files/move tmp-path
                path
                (into-array CopyOption
                            [StandardCopyOption/ATOMIC_MOVE
                             StandardCopyOption/REPLACE_EXISTING]))
    (count pb-bytes)))
(m/=> write-bytes! [:=> [:cat bytes? [:string {:min 1}]] nat-int?])

;; -- Function schema registrations --
(m/=> ir->bytes [:=> [:cat map?] bytes?])

(m/=> bytes->screen [:=> [:cat bytes?] proto-schema/screen])