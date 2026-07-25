(ns devcards.tokens
  "The devcards→design-tokens read-path: resolve a semantic COLOUR token to
   its concrete hex by reading protogen's emitted design-tokens.json.

   This is the ratified consumer contract (see renderer-gen's
   design-tokens-json emitter): a consumer READS the fully-resolved manifest
   from the pin, it never re-implements the resolver — so there is exactly one
   resolver in the tree. It gives an authored-composition colour (a scrubber /
   media hue) the same one home in tokens.edn every theme colour already has,
   instead of a literal copied into the lego.

   The manifest is mode-forked ({\"dark\" \"light\"} per token); the media
   colours are mode-invariant by contract, so `color` reads the shared value
   and asserts the two modes agree — a divergence would be a silent per-mode
   surprise an authored (theme-family-independent) composition cannot honour."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))

(set! *warn-on-reflection* true)

(def manifest-path
  "protogen's emitted design-tokens.json, relative to the tools/devcards CWD
   every devcards entry point runs from (renderer.mk `cd tools/devcards`) —
   the same relative-path read-path devcards.docs uses for the descriptor set.
   `make -f renderer.mk manifests` (a check-renderer prerequisite) keeps it
   fresh from tokens.edn."
  "../../output/manifests/design-tokens.json")

(defn- load-manifest
  "Read + parse the committed design-tokens.json. Fail loud if absent — the
   manifests lane emits it and is a check-renderer prerequisite, so a missing
   file is a sequencing bug, never a default to paper over."
  []
  (let [^java.io.File f (io/file manifest-path)]
    (when-not (.exists f)
      (throw (ex-info (str "design-tokens.json not found at " manifest-path
                           " — run `make -f renderer.mk manifests` first")
                      {:path manifest-path})))
    (json/read-str (slurp f))))

(def ^:private manifest
  "The parsed manifest, read once on first token resolution."
  (delay (load-manifest)))

(defn color
  "The concrete #RRGGBB hex for a mode-invariant semantic COLOUR token,
   resolved from design-tokens.json. Throws if the token is absent, not a
   colour, or mode-forked (an authored composition colour is one value)."
  [sem-key]
  (let [entry (get-in @manifest ["tokens" (name sem-key)])]
    (when-not entry
      (throw (ex-info (str "unknown design token: " sem-key)
                      {:token sem-key :path manifest-path})))
    (when-not (= "color" (get entry "kind"))
      (throw (ex-info (str "design token " sem-key " is not a colour")
                      {:token sem-key :kind (get entry "kind")})))
    (let [dark (get entry "dark")
          light (get entry "light")]
      (when-not (= dark light)
        (throw (ex-info (str "design token " sem-key " is mode-forked; an "
                             "authored composition colour must be mode-invariant")
                        {:token sem-key :dark dark :light light})))
      dark)))
