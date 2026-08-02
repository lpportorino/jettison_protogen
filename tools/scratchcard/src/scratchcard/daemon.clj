(ns scratchcard.daemon
  "The warm JVM behind the socket.

  IT EXISTS BECAUSE OF ONE MEASURED NUMBER. A cold JVM plus the
  devcards.host/fixtures/lanes namespace load costs ~2.0s inside the pinned
  container (~2.6s including docker start) BEFORE GraalWasm compiles
  controls.wasm — and the first few renders then pay JIT warmup on top, which
  measured 1400-2000ms per cell against 3-40ms once warm. A per-invocation CLI
  pays all of that every call and never reaches steady state.

  IT MUST RUN ON GraalVM. `devcards.host/assert-optimizing-runtime!` hard-fails
  on a stock JDK, and interpreted wasm measured ~290ms per render against
  roughly a twentieth of that. This is not a preference; the host refuses.

  THE CLIENT OWNS THE SOCKET PATH. It is read from the environment and never
  recomputed here, because the per-fork key is a hash of the repo root and
  every fork's container sees the SAME workspace mount — an in-container
  derivation would collide across forks. Missing env is a hard failure, not a
  guess.

  A STALE SOCKET IS DETECTED BY CONNECTING, NOT BY `exists?`. That is the only
  correct staleness test for a unix socket: a leftover file from a killed
  daemon is indistinguishable from a live one by any filesystem property. A
  live peer means REFUSE; a dead one means unlink and bind.

  THREAD PER CLIENT, AND OPS DO NOT SERIALISE. See `scratchcard.run` for why
  parallel rendering is sound here. What a connection thread buys is that one
  slow or rude client cannot block another, and a handler that dies takes only
  its own connection.

  SHUTDOWN EXITS THE JVM EXPLICITLY. Truffle and the render pool leave
  non-daemon threads that would otherwise keep the process — and therefore the
  container — alive after main returns."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [scratchcard.diff :as diff]
   [scratchcard.digest :as digest]
   [scratchcard.protocol :as proto]
   [scratchcard.retention :as retention]
   [scratchcard.run :as run])
  (:import
   (java.io BufferedReader File InputStreamReader PrintWriter)
   (java.net StandardProtocolFamily UnixDomainSocketAddress)
   (java.nio.channels Channels ServerSocketChannel SocketChannel)
   (java.nio.file Files LinkOption Path Paths)))

(def socket-env "PROTOGEN_RENDER_SOCKET")
(def workspace-env "PROTOGEN_WORKSPACE")

(defonce ^:private started-at (atom nil))
(defonce ^:private run-count (atom 0))
(defonce ^:private booted-src-sha (atom nil))

(def source-roots
  "The trees whose code this daemon EXECUTES.

  Both, deliberately: scratchcard is the tool and devcards supplies the render
  host and the armed quality lanes, so an edit to either changes what a warm
  daemon does while the daemon carries on running the old bytes."
  ["tools/scratchcard/src" "tools/devcards/src"])

(defn source-sha
  "A digest over every Clojure source file the daemon runs.

  Sorted by path so the digest is a function of CONTENT, not of directory
  iteration order — otherwise a daemon would look stale to itself on a
  filesystem that enumerates differently between calls."
  ^String [^String workspace]
  (let [files (->> source-roots
                   (mapcat #(file-seq (io/file workspace %)))
                   (filter #(and (.isFile ^File %)
                                 (re-find #"\.clj[cs]?$" (.getName ^File %))))
                   (sort-by #(.getPath ^File %)))]
    (digest/of-string
     (str/join "\n" (map #(str (.getPath ^File %) " " (digest/of-file %)) files)))))

(defn staleness
  "Whether the daemon is running code that no longer matches the tree.

  REPORTS, NEVER AUTO-RESTARTS. Silent self-recovery of a running process is
  the class this fleet refuses: it hides the fact that the operator's edit was
  not in effect, which is exactly the confusion this exists to end.

  MEASURED, and it is why this is here at all: a security fix was verified
  against a warm daemon still running the pre-fix code, and the only symptom
  was the fix appearing not to work."
  [^String workspace]
  (let [booted @booted-src-sha
        current (try (source-sha workspace) (catch Exception _ nil))]
    (cond
      (or (nil? booted) (nil? current)) {:stale? false :known? false}
      (= booted current) {:stale? false :known? true}
      :else {:stale? true
             :known? true
             :booted (subs booted 0 12)
             :on-disk (subs current 0 12)
             :remediation "source changed since this daemon booted — `scratchcard restart` to adopt it"})))

(defn- as-path ^Path [^String s] (Paths/get s (make-array String 0)))

(defn already-bound?
  "Is a LIVE peer listening on `socket-path`?

  A connect probe, deliberately — see the namespace docstring."
  [^String socket-path]
  (try
    (with-open [_ (SocketChannel/open (UnixDomainSocketAddress/of (as-path socket-path)))]
      true)
    (catch Exception _ false)))

(defn clear-stale-socket!
  "Remove a dead socket file, or throw when a live daemon holds it."
  [^String socket-path]
  (let [p (as-path socket-path)]
    (when (Files/exists p (into-array LinkOption []))
      (if (already-bound? socket-path)
        (throw (ex-info (str "a daemon is already listening on " socket-path)
                        {:error "ALREADY_RUNNING" :socket socket-path}))
        (Files/deleteIfExists p)))))

;; ── ops ────────────────────────────────────────────────────────────────────

(defn- op-status
  [{:keys [workspace socket-path]}]
  {:daemon "up"
   :socket socket-path
   :workspace workspace
   :started-at @started-at
   :runs @run-count
   :pid (.pid (java.lang.ProcessHandle/current))
   ;; Embedded so every response points at its own logs, the way the sibling
   ;; fleet's do — an error that names no next command costs a round trip.
   :logs (str "docker logs " (or (System/getenv "PROTOGEN_CONTAINER") "<container>"))})

(defn- op-regenerate
  [{:keys [workspace]} args]
  (let [{:keys [file card resolutions families modes bp-from-canvas? timeout-ms]} args]
    (when (str/blank? (str file))
      (throw (ex-info "regenerate needs :file — the screen to render"
                      {:error "INPUT_MISSING"})))
    (swap! run-count inc)
    (run/regenerate!
     (cond-> {:repo-root workspace
              :screen-path file
              :image-tag (System/getenv "PROTOGEN_IMAGE_TAG")
              :matrix-opts (cond-> {}
                             resolutions (assoc :resolutions
                                                (mapv #(-> % (update :w long) (update :h long))
                                                      resolutions))
                             families (assoc :families (mapv long families))
                             modes (assoc :modes (mapv long modes))
                             (some? bp-from-canvas?) (assoc :bp-from-canvas? bp-from-canvas?))}
       card (assoc :card card)
       timeout-ms (assoc :timeout-ms (long timeout-ms))
       (:keep args) (assoc :keep (long (:keep args)))))))

(defn handle
  "Dispatch one validated request. Returns a response envelope.

  Every outcome is a VALUE, including a thrown handler: the accept loop must
  never be the thing that decides whether a client hears back."
  [ctx {:keys [id op args]}]
  (try
    (case op
      "ping" (proto/ok id "pong")
      ;; `status` declares an EMPTY arg set, so `(:card args)` was unreachable
      ;; and its fallback reported zero bytes for every real card. Usage is
      ;; summed across all cards instead — growth must be visible without the
      ;; caller naming anything.
      "status" (proto/ok id (assoc (op-status ctx)
                                   :usage (retention/total-usage
                                           (str (:workspace ctx) "/.protogen/scratch"))
                                   :staleness (staleness (:workspace ctx))))
      "diff" (let [{:keys [card from to]} args
                   root (str (:workspace ctx) "/.protogen/scratch")
                   ;; `as-ref`, not `ref` — binding `ref` would shadow
                   ;; `clojure.core/ref`.
                   as-ref (fn [v d] (cond (nil? v) d
                                          (= "latest" v) :latest
                                          (int? v) v
                                          (re-matches #"\d+" (str v)) (parse-long (str v))
                                          :else (str v)))]
               (proto/ok id (diff/diff root (or card "hello")
                                       (as-ref from :previous) (as-ref to :latest))))
      "stop" (proto/ok id "stopping")
      ;; STALENESS RIDES THE REGENERATE RESPONSE, not only `status`. A warning
      ;; a caller has to ASK for is a warning they will not see: the moment it
      ;; matters is the moment they are reading a result produced by the old
      ;; code and concluding their edit did nothing.
      "regenerate" (let [v (op-regenerate ctx args)
                         st (staleness (:workspace ctx))]
                     (proto/ok id (cond-> v (:stale? st) (assoc :stale st))))
      (proto/err id "UNKNOWN_OP" (str "unhandled op " op)))
    (catch Exception e
      (proto/err id (or (:error (ex-data e)) "RENDER_FAILED")
                 (str (ex-message e))
                 (select-keys (ex-data e) [:stage :errors])))))

;; ── server ─────────────────────────────────────────────────────────────────

(defn- serve-connection!
  [ctx ^SocketChannel ch shutdown]
  (try
    (with-open [ch ch
                in (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) "UTF-8"))]
      (let [out (PrintWriter. (Channels/newOutputStream ch))
            line (.readLine in)
            decoded (proto/decode-line line)
            resp (if (nil? decoded)
                   (proto/err nil "MALFORMED_REQUEST"
                              (if (nil? line)
                                "connection closed without a request"
                                "request line is not a JSON object"))
                   (let [[status v] (proto/validate-request decoded)]
                     (if (= :error status) v (handle ctx v))))]
        (.print out (proto/encode-line resp))
        (.flush out)
        (when (and (map? decoded) (= "stop" (:op decoded)))
          (deliver shutdown true))))
    (catch Exception e
      (binding [*out* *err*]
        (println "scratchcard: connection handler died:" (ex-message e))))))

(defn serve!
  "Bind `socket-path` and accept until a `stop` op arrives. Blocks."
  [{:keys [socket-path] :as ctx}]
  (clear-stale-socket! socket-path)
  (reset! started-at (str (java.time.Instant/now)))
  ;; Recorded BY THE PROCESS IT DESCRIBES, in memory rather than in a file, so
  ;; the marker cannot outlive its writer — the failure mode of a marker
  ;; nothing rewrites when the daemon dies.
  (reset! booted-src-sha (try (source-sha (:workspace ctx)) (catch Exception _ nil)))
  (let [shutdown (promise)
        addr (UnixDomainSocketAddress/of (as-path socket-path))]
    (io/make-parents (io/file socket-path))
    (with-open [server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
      (.bind server addr)
      (println "scratchcard daemon listening on" socket-path)
      (flush)
      (future
        (while (not (realized? shutdown))
          (try
            (let [ch (.accept server)]
              (.start (Thread. #(serve-connection! ctx ch shutdown))))
            (catch Exception _ nil))))
      @shutdown)
    (Files/deleteIfExists (as-path socket-path))
    (println "scratchcard daemon stopped")
    (flush)))

(defn -main
  [& _]
  (let [socket-path (System/getenv socket-env)
        workspace (or (System/getenv workspace-env) "/workspace")]
    (when (str/blank? (str socket-path))
      (binding [*out* *err*]
        (println (str "scratchcard daemon: " socket-env " is not set. The CLIENT "
                      "owns the socket path — every fork's container sees the same "
                      "workspace mount, so an in-container derivation would collide "
                      "across forks.")))
      (System/exit 5))
    (serve! {:socket-path socket-path :workspace workspace})
    ;; Truffle and the render pool leave non-daemon threads alive.
    (shutdown-agents)
    (System/exit 0)))
