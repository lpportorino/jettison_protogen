(ns render-sheet
  "Render a named set of cards under a named family/mode into JPEGs under
   .fork-scratch/shots/, using the SAME crop the shipped gallery uses
   (devcards.gallery/render-cell!). Scratch only: writes nothing the battery
   or the gallery reads.

   Run (in the toolchain container, from tools/devcards/):
     clojure -Sdeps '{:aliases {:probe {:extra-paths [\"../../.fork-scratch\"]}}}' \\
       -M:bindings:probe -m render-sheet <tag> <card-id-substring>..."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [devcards.fixtures :as fixtures]
            [devcards.gallery :as gallery]
            [devcards.jpeg :as jpeg])
  (:import (java.io File)))

(set! *warn-on-reflection* true)

(def ^:private canvas {:w 800 :h 480})
(def ^:private paths {:wasm "../../renderer/output/controls.wasm"
                      :assets "../../renderer/assets"})

(defn -main [& args]
  (let [[tag & filters] args
        spec (fixtures/load-spec)
        built (fixtures/build-all spec)
        picked (filterv (fn [{:keys [id]}]
                          (some #(str/includes? (str id) %) filters))
                        built)
        outdir (io/file "../../.fork-scratch/shots")]
    (.mkdirs outdir)
    (println (format "rendering %d cards, tag=%s" (count picked) tag))
    (doseq [{:keys [id] ^bytes pb :bytes} picked
            fam gallery/family-renders]
      (let [img (gallery/render-cell! paths canvas pb fam (str id))
            nm (str tag "--" (str/replace (str id) #"/" "_") "--"
                    (:file-suffix fam) ".jpg")
            ^File f (io/file outdir nm)]
        (io/copy (jpeg/encode img jpeg/default-quality) f)
        (println "  " nm)))))
