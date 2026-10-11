(ns gate.catalog-identity
  "Integrity of one sorted local inventory; no current filesystem or file-content claims."
  (:require [clojure.string :as str]
            [gate.canonical :as canonical]
            [gate.catalog-contract :as cc]
            [malli.core :as m]))
(defn require-valid!
  "Validate exact sorted distinct names/path bindings and the metadata-only content seal."
  [document]
  (when-not (m/validate cc/Document document)
    (throw (ex-info "Directory catalog refused" {:code :catalog-shape})))
  (let [names (mapv :name (:entries document)) directory (:directory document)
        prefix (if (str/ends-with? directory "/") directory (str directory "/"))]
    (when-not (and (= names (vec (sort (distinct names))))
                   (every? #(= (:path %) (str prefix (:name %))) (:entries document)))
      (throw (ex-info "Directory catalog refused" {:code :catalog-shape})))
    (when-not (= (:artifact document) (canonical/sha256 (canonical/encode (dissoc document :artifact) 134217728)))
      (throw (ex-info "Directory catalog refused" {:code :catalog-identity}))))
  document)
(m/=> require-valid! [:=> [:cat cc/Document] cc/Document])

(defn seal
  "Bind immutable listing metadata after sorting; this hash includes no entry file contents."
  [material]
  (require-valid! (assoc material :artifact (canonical/sha256 (canonical/encode material 134217728)))))
(m/=> seal [:=> [:cat cc/Material] cc/Document])
