(ns scratchcard.provenance
  "Stamps a run with everything needed to tell WHY it differs from another run.

  A run archive whose entries cannot be attributed is a pile of pictures. When
  two runs disagree, exactly three things can be responsible, and the manifest
  must separate them or the reader goes hunting in the wrong place:

    1. THE INPUT changed          -> `:input` sha
    2. THE RENDERER changed       -> `:wasm` sha, `:protogen` sha
    3. THE JUDGEMENT changed      -> the lane roster, thresholds, class table

  The third is the one a naive stamp omits, and omitting it is what makes a
  threshold edit read as a pixel regression.

  EVERY VALUE IS READ FROM ITS ONE HOME, never re-spelled here. The protocol
  constants come from `devcards.host`, the toolchain pins from
  `Dockerfile.base`, the wasm build stamp from the file the build writes.
  A literal copied into this namespace would be a second home that cannot
  track the first.

  `:engine-impl` IS LOAD-BEARING, not decoration. GraalWasm falls back to an
  interpreter when the optimizing runtime is unavailable, and interpreted
  renders are roughly twenty times slower. `devcards.host` hard-fails on that
  today, but recording the value makes the ARCHIVE self-describing rather than
  trusting a guard held somewhere else — a reader comparing timings months
  later cannot re-run that guard.

  DEGRADES RATHER THAN THROWS. A missing wasm, an absent docker, a checkout
  with no tags: each is a fact to RECORD, not a reason to lose the run's
  stamp. Every collector returns nil or a marked-absent map instead of
  exploding, because the run that most needs a stamp is the one that failed."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [devcards.host :as host]
   [scratchcard.digest :as digest])
  (:import
   (java.io File)
   (java.lang.management ManagementFactory)))

(defn- git
  "Run a git subcommand in `dir`; trimmed stdout, or nil on any failure."
  [dir & args]
  (let [{:keys [exit out]} (apply shell/sh "git" (concat args [:dir dir]))]
    (when (zero? exit)
      (not-empty (str/trim out)))))

(defn git-stamp
  "The checkout's identity: sha, branch, dirtiness, describe.

  `:dirty?` is separate from `:sha` on purpose. A dirty tree means the sha
  does NOT identify what was rendered, and a reader comparing two runs at the
  same sha needs to know that before concluding the renderer is
  non-deterministic."
  [^String repo-root]
  (let [status (git repo-root "status" "--porcelain")]
    {:sha (git repo-root "rev-parse" "HEAD")
     :short-sha (git repo-root "rev-parse" "--short" "HEAD")
     :branch (git repo-root "rev-parse" "--abbrev-ref" "HEAD")
     :describe (git repo-root "describe" "--always" "--dirty" "--tags")
     :dirty? (boolean (seq status))}))

(defn file-stamp
  "`path`'s identity: existence, size and sha256.

  Records `:exists? false` rather than returning nil, so an absent file is a
  POSITIVE statement in the manifest instead of a missing key a reader has to
  interpret."
  [path]
  (let [f (io/file path)]
    (if (.isFile ^File f)
      {:path (str path) :exists? true
       :bytes (.length ^File f) :sha256 (digest/of-file f)}
      {:path (str path) :exists? false})))

(defn wasm-stamp
  "The reference interpreter's identity.

  `:build-sha` is the stamp the wasm build itself writes beside the artifact;
  it answers \"which sources produced this\" where the sha256 answers \"which
  bytes am I running\". They are different questions and a run wants both."
  [^String repo-root]
  (let [wasm (io/file repo-root "renderer" "output" "controls.wasm")
        build-sha (io/file repo-root "renderer" "output" "controls.wasm.build-sha")]
    (assoc (file-stamp wasm)
           :build-sha (when (.isFile build-sha) (str/trim (slurp build-sha))))))

(def ^:private pin-pattern
  "Matches any pinned version in `Dockerfile.base`.

  Deliberately GENERIC rather than a named list: a new pin is picked up
  automatically, where an enumeration here would silently stop tracking the
  file it claims to mirror."
  #"(?m)^(?:ENV|ARG)\s+([A-Z0-9_]*VERSION)=(\S+)")

(defn toolchain-pins
  "Every `*VERSION` pin declared in `Dockerfile.base`, as a map.

  `Dockerfile.base` is the ONE home for every toolchain version in this repo,
  so the manifest reads it rather than restating any value."
  [^String repo-root]
  (let [f (io/file repo-root "Dockerfile.base")]
    (when (.isFile f)
      (into {} (map (fn [[_ k v]] [(keyword k) v]))
            (re-seq pin-pattern (slurp f))))))

(defn image-id
  "The docker image id behind `tag`, or nil.

  nil covers both \"docker is absent\" (the daemon's own container has no
  docker CLI) and \"the image is not built\". Neither is an error here; the
  spawning client is where a missing image must fail loudly."
  [^String tag]
  (try
    (let [{:keys [exit out]} (shell/sh "docker" "image" "inspect" "--format" "{{.Id}}" tag)]
      (when (zero? exit) (not-empty (str/trim out))))
    (catch Exception _ nil)))

(defn protocol-stamp
  "The pinned per-render protocol, read from `devcards.host`.

  Read, never copied: these three numbers are the contract the goldens and the
  cross-engine mirror were minted under, and a scratch render that silently
  used different ones would produce hashes that look like regressions."
  []
  {:ticks host/render-ticks
   :tick-ms host/tick-ms
   :dpi host/default-dpi})

(defn load-average
  "The 1-minute system load, or nil where the JVM cannot report it.

  Recorded beside every timing because THIS HOST IS USUALLY BUSY — sibling
  agents, containers and other sessions share it, and the same command has
  been measured swinging well over 30% between interleaved runs. A timing
  without a load figure cannot be compared to one taken an hour later."
  []
  (let [v (.getSystemLoadAverage (ManagementFactory/getOperatingSystemMXBean))]
    (when-not (neg? v) v)))

(defn collect
  "The full provenance block for one run.

  `runtime` carries the facts only a live host can answer — `:abi`,
  `:fb-format`, `:engine-impl` — because obtaining them requires building a
  GraalWasm context, and provenance must stay collectible by a run that never
  got that far."
  [{:keys [repo-root image-tag runtime]}]
  {:protogen (git-stamp repo-root)
   :wasm (merge (wasm-stamp repo-root)
                (select-keys runtime [:abi :fb-format]))
   :toolchain (cond-> {:pins (toolchain-pins repo-root)
                       :image-tag image-tag
                       :image-id (when image-tag (image-id image-tag))}
                (:engine-impl runtime) (assoc :engine-impl (:engine-impl runtime)))
   :protocol (protocol-stamp)
   :host {:load-average (load-average)}})
