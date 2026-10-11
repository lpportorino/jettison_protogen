(ns press.config
  "Where the press works and what it tells a reader to run — the ONE place a
   consumer adapts the library to its own repository.

   `*config*` holds a map (`config-spec`); every path the library resolves,
   every host command it returns and every input path it hashes is read from
   it. A consumer binds its own values around each call:

     (press.config/with-config {:root \"/srv/site\" :launcher \"bin/press\"}
       (press.doc/check \"docs/guide\"))

   Keys:
     :root        the directory every RELATIVE path argument is resolved
                  against, and that returned host commands are relative to
     :launcher    the launcher path printed as the first word of every
                  returned host command
     :house       the house-constants JSON (relative to `:root`, or absolute);
                  when it is not the tool's default, returned `:shell` lines
                  start with `PRESS_HOUSE=…` and `launcher-env` gives the
                  same for a caller that runs an `:argv` itself
     :build-dir   where built pages and candidate layouts go (never committed)
     :image-hint  the command a reader runs once to build the tool image
     :forms-hint  the RUNBOOK sentence saying how its Clojure forms are run
     :review-hint the RUNBOOK sentence saying who reviews the printed pages

   The default `:root` is derived, never hard-coded: see `default-root`.
   A value a function returns must be realised INSIDE the binding — the
   library's public functions return maps, vectors and strings, not lazy
   sequences that would read the config later."
  (:require [clojure.java.io :as io]
            [malli.core :as m]
            [malli.error :as me])
  (:import [java.io File]))

(set! *warn-on-reflection* true)

(def ^:private ne-string [:string {:min 1}])

(def config-spec
  "The configuration map: every key present, every value a non-empty string."
  [:map {:closed true}
   [:root ne-string]
   [:launcher ne-string]
   [:house ne-string]
   [:build-dir ne-string]
   [:image-hint ne-string]
   [:forms-hint ne-string]
   [:review-hint ne-string]])

(def overrides-spec
  "What `with-config` takes: any subset of `config-spec`'s keys."
  [:map {:closed true}
   [:root {:optional true} ne-string]
   [:launcher {:optional true} ne-string]
   [:house {:optional true} ne-string]
   [:build-dir {:optional true} ne-string]
   [:image-hint {:optional true} ne-string]
   [:forms-hint {:optional true} ne-string]
   [:review-hint {:optional true} ne-string]])

(def ^:private marker
  "The file whose presence under a directory makes it a root the defaults fit."
  "tools/press/press/house.json")

(defn- root-above
  "The nearest ancestor of `start` (itself included) that holds `marker`,
   as an absolute path, or nil."
  [^File start]
  (loop [^File d (.getAbsoluteFile start)]
    (cond (nil? d) nil
          (.isFile (io/file d marker)) (.getPath d)
          :else (recur (.getParentFile d)))))

(m/=> root-above [:=> [:cat [:fn #(instance? File %)]] [:maybe ne-string]])

(defn- resource-dir
  "The directory this library's own resources are read from, when they are
   plain files (not inside a jar), else nil."
  []
  (when-let [r (io/resource "press/doc/house-print.css")]
    (when (= "file" (.getProtocol r))
      (.getParentFile (io/file (.toURI r))))))

(m/=> resource-dir [:=> [:cat] [:maybe [:fn #(instance? File %)]]])

(defn default-root
  "The root used when a consumer configures none, the first that answers:
   the `press.root` system property; the `PRESS_ROOT` environment variable;
   the nearest ancestor of the working directory holding
   `tools/press/press/house.json`; the nearest such ancestor of this
   library's own resource directory (so the library finds its repository
   from any working directory); the working directory."
  []
  (or (not-empty (System/getProperty "press.root"))
      (not-empty (System/getenv "PRESS_ROOT"))
      (root-above (io/file (System/getProperty "user.dir")))
      (some-> (resource-dir) root-above)
      (System/getProperty "user.dir")))

(m/=> default-root [:=> [:cat] ne-string])

(def defaults
  "The configuration in force when nothing is bound."
  {:root (default-root)
   :launcher "tools/press/bin/press"
   :house marker
   :build-dir ".press/doc-build"
   :image-hint "make -f press.mk image"
   :forms-hint (str "Each is a call into the `press.doc` namespace (required as `doc`): evaluate it in a "
                    "Clojure REPL that has the press library on its classpath.")
   :review-hint "Open and read each one: a clean print report is not a page review."})

(def ^:dynamic *config*
  "The configuration map in force (`config-spec`). Bind it with
   `with-config`, which checks the result."
  defaults)

(defn checked
  "`cfg` when it is a valid `config-spec`; else a refusal
   (`:press/bad-config`) naming what is wrong."
  [cfg]
  (when-not (m/validate config-spec cfg)
    (throw (ex-info (str "press configuration is not valid: " (pr-str (me/humanize (m/explain config-spec cfg))))
                    {:press/refusal :press/bad-config})))
  cfg)

(m/=> checked [:=> [:cat [:map-of :keyword [:maybe some?]]] config-spec])

(defmacro with-config
  "Run `body` with `overrides` (any subset of the config keys) merged over
   the configuration in force. Refuses `:press/bad-config` for an unknown
   key or a value that is not a non-empty string."
  [overrides & body]
  `(binding [*config* (checked (merge *config* ~overrides))]
     ~@body))

(defn root
  "The configured root directory, as given."
  []
  (:root *config*))

(m/=> root [:=> [:cat] ne-string])

(defn launcher
  "The launcher path a returned host command starts with."
  []
  (:launcher *config*))

(m/=> launcher [:=> [:cat] ne-string])

(defn house
  "The house-constants path, as configured (relative to the root, or absolute)."
  []
  (:house *config*))

(m/=> house [:=> [:cat] ne-string])

(defn launcher-env
  "The environment the launcher must be given beside a returned `:argv`.

   `{\"PRESS_HOUSE\" house}` when the configured house is not the tool's own
   default, else empty: the browser half reads its default house unless told
   otherwise, so a consumer's house must reach it or the two halves judge one
   figure by two styles. When packaged resources do not reveal the tool's
   own root, always forward the configured house: a consumer's discovered
   root is not evidence about the launcher's default style. Every returned
   `:shell` line already carries it."
  []
  (let [configured (io/file (house))
        configured (.getCanonicalFile ^File (if (.isAbsolute configured) configured (io/file (root) (house))))
        tool-root (some-> (resource-dir) root-above)
        tool-house (when tool-root (.getCanonicalFile (io/file tool-root marker)))]
    (if (= configured tool-house) {} {"PRESS_HOUSE" (house)})))

(m/=> launcher-env [:=> [:cat] [:map-of ne-string ne-string]])

(defn build-dir
  "The build directory, as configured (relative to the root, or absolute)."
  []
  (:build-dir *config*))

(m/=> build-dir [:=> [:cat] ne-string])

(defn image-hint
  "The command a reader runs once to build the tool image."
  []
  (:image-hint *config*))

(m/=> image-hint [:=> [:cat] ne-string])

(defn forms-hint
  "The RUNBOOK sentence saying how its Clojure forms are run."
  []
  (:forms-hint *config*))

(m/=> forms-hint [:=> [:cat] ne-string])

(defn review-hint
  "The RUNBOOK sentence saying who reviews the printed pages, and how."
  []
  (:review-hint *config*))

(m/=> review-hint [:=> [:cat] ne-string])

(defn from-root
  "`path` as a canonical absolute path string: an absolute path stands, a
   relative one is taken against the configured root (never the JVM's
   working directory)."
  [path]
  (let [f (io/file path)]
    (.getCanonicalPath ^File (if (.isAbsolute f) f (io/file (root) path)))))

(m/=> from-root [:=> [:cat [:string {:min 0}]] ne-string])
