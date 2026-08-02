#!/usr/bin/env bb
;; scratchcard — the client. Speaks NDJSON over AF_UNIX to the warm daemon,
;; spawning it on demand.
;;
;; ZERO EXTERNAL DEPS, deliberately: AF_UNIX, flock and sha256 are all bb
;; built-ins, so this runs on a cold clone with nothing but the pinned binary.
;;
;; EVERY PER-FORK NAME IS DERIVED HERE, and this file is the CLIENT-side
;; authority — it must agree with `scratchcard.scope`, which is the in-JVM one.
;; The key is sha256(git rev-parse --show-toplevel)[0:16]: not a git sha (it
;; would churn every commit and orphan the daemon), not the basename (two forks
;; both named jettison_protogen collide, which is the case this prevents).
;;
;; NO TCP PORTS. Isolation is by hash-keyed paths, so multi-clone setups
;; auto-isolate with nothing to allocate.

(ns scratchcard
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader PrintWriter]
           [java.net UnixDomainSocketAddress]
           [java.nio.channels Channels FileChannel SocketChannel]
           [java.nio.file Paths StandardOpenOption]
           [java.security MessageDigest]))

(defn- sh-out [& args]
  (let [{:keys [exit out]} (apply p/sh args)]
    (when (zero? exit) (str/trim out))))

(defn- sha256-hex [^String s]
  (->> (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))
       (map #(format "%02x" (bit-and % 0xff)))
       (apply str)))

(defn- contains-dir?
  "Whether `root` is `dir` or an ancestor of it, on CANONICAL paths.
  Mirrors `scratchcard.scope/contains-dir?`. The separator is appended so
  /work is not read as an ancestor of /workspace."
  [^String root ^String dir]
  (let [canon #(.getCanonicalPath (java.io.File. ^String %))
        r (canon root) d (canon dir)]
    (or (= r d) (str/starts-with? d (str r java.io.File/separator)))))

(def repo-root
  "The worktree root CONTAINING this client's cwd, or a refusal.

  THREE THINGS HERE ARE DELIBERATE, and the previous one-liner had none of them.

  `env -u GIT_DIR -u GIT_WORK_TREE` — and GIT_DIR is the one that matters, which
  is worth stating because the obvious guess is wrong. MEASURED: GIT_DIR alone,
  from a directory outside any checkout, makes `rev-parse --show-toplevel` exit 0
  and return THE CWD. GIT_WORK_TREE alone does NOT — from outside a checkout it
  still fails, because it overrides the REPORTED worktree only once a gitdir has
  been discovered. Both are scrubbed because either can be inherited. Every name
  below is keyed by sha256(repo-root), so one stray variable collapses two
  checkouts onto one container, one socket and one lock. `tools/uber.sh` and
  `tools/claude/forks.sh` scrub for the same reason.

  THE CONTAINMENT CHECK — because the fallback below fires only when git FAILS,
  never when git LIES. It mirrors `scratchcard.scope/discover-repo-root`, which
  was hardened for that case while this client was left behind.

  THE SCRUB HAS NO JVM COUNTERPART, DELIBERATELY, so do not restore symmetry
  here. `scope` declines to touch the environment because inside the toolchain
  container the exported GIT_DIR is the only thing that resolves the workspace at
  all. This client runs on the HOST, where nothing needs it. The residue is worth
  knowing: containment ALONE cannot catch GIT_DIR-only, because git then returns
  the cwd and the check passes trivially — so the scrub is what closes that here,
  and the JVM home is still open to it.

  REFUSING RATHER THAN FALLING BACK TO CWD. The old fallback handed out a hash
  for a non-repository, and every name derived from it looked valid while
  belonging to nothing — which `scratchcard.scope`'s own docstring forbids in
  as many words. A daemon keyed to nothing is worse than a clear refusal."
  (let [cwd (str (fs/cwd))
        root (sh-out "env" "-u" "GIT_DIR" "-u" "GIT_WORK_TREE"
                     "git" "rev-parse" "--show-toplevel")]
    (if (and root (seq root) (contains-dir? root cwd))
      root
      (binding [*out* *err*]
        (println (str "scratchcard: refusing to derive a per-fork key — "
                      (if (and root (seq root))
                        (str "git answered " root ", which does not contain " cwd
                             " (a stray GIT_DIR/GIT_WORK_TREE, or a bare repo)")
                        (str cwd " is not inside a git checkout"))))
        (println "  Every container, socket and lock name is keyed by the repo root,")
        (println "  so a wrong root silently shares another checkout's daemon.")
        (System/exit 4)))))
(def worktree-hash (subs (sha256-hex repo-root) 0 16))
(def container-name (str "protogen-render-" worktree-hash))
(def image-tag (or (System/getenv "PROTOGEN_IMAGE_TAG")
                   "jettison-proto-generator-base:latest"))
(def runtime-dir
  ;; `not-empty` MIRRORS `scratchcard.scope/runtime-base`, and the difference is
  ;; not cosmetic: `""` is truthy in Clojure, so a bare `or` would take an
  ;; XDG_RUNTIME_DIR that is set-but-empty and build a RELATIVE socket path here
  ;; while the JVM built one under /tmp. The client would then bind a socket the
  ;; daemon never looks at, which presents as a daemon that will not start.
  (str (fs/path (or (not-empty (System/getenv "XDG_RUNTIME_DIR")) "/tmp")
                "protogen" worktree-hash)))
(def socket-path (str (fs/path runtime-dir "render.sock")))
(def lock-path (str (fs/path runtime-dir "render.lock")))

(def sun-path-max
  "Bytes available for an AF_UNIX path, including the trailing NUL — 108 on
  Linux (`sun_path[108]` in <sys/un.h>).

  MIRRORS `scratchcard.scope/sun-path-max`, and `scope_test` asserts the two
  agree. The guard lives HERE as well because THIS file is what constructs the
  path handed to `bind`: a check that ran only in the JVM would sit downstream
  of the failure it exists to prevent."
  108)

(when (>= (count (.getBytes ^String socket-path "UTF-8")) sun-path-max)
  (binding [*out* *err*]
    (println (str "scratchcard: the socket path is " (count (.getBytes ^String socket-path "UTF-8"))
                  " bytes and AF_UNIX allows " (dec sun-path-max)
                  " plus NUL — this worktree is nested too deeply: " socket-path)))
  (System/exit 4))

(defn- npath [^String s] (Paths/get s (make-array String 0)))

(defn daemon-up? []
  (try (with-open [_ (SocketChannel/open (UnixDomainSocketAddress/of (npath socket-path)))] true)
       (catch Exception _ false)))

(defn- docker-rm! []
  ;; `docker rm -f` returns before the reaper frees the NAME, so an immediate
  ;; `docker run --name` hits "Conflict: name already in use". Poll until gone.
  (p/sh "docker" "rm" "-f" container-name)
  (loop [tries 0]
    (let [{:keys [out]} (p/sh "docker" "ps" "-aq" "--filter" (str "name=^" container-name "$"))]
      (when (and (seq (str/trim (str out))) (< tries 40))
        (Thread/sleep 100)
        (recur (inc tries))))))

(defn- spawn! []
  (fs/create-dirs runtime-dir)
  ;; owner-only: on the /tmp fallback this would otherwise be world-traversable,
  ;; and the socket accepts render requests.
  (fs/set-posix-file-permissions runtime-dir "rwx------")
  (docker-rm!)
  (let [uid (str/trim (:out (p/sh "id" "-u")))
        gid (str/trim (:out (p/sh "id" "-g")))
        argv ["docker" "run" "-d" "--rm" "--restart=no" "--stop-timeout" "10"
              "--name" container-name
              "--user" (str uid ":" gid)
              ;; IDENTITY MOUNT: host path == container path, so the client and
              ;; the in-container JVM rendezvous on one string and every error
              ;; message names a path the operator can reach directly.
              "-v" (str runtime-dir ":" runtime-dir)
              ;; The repo under BOTH path views — the canonical /workspace AND
              ;; the host's own absolute path. This is the fleet's cvgpu
              ;; pattern and it removes path translation entirely: a caller on
              ;; the host passes host paths, a caller inside passes container
              ;; paths, and neither needs to know which side it is on. Without
              ;; it the client must rewrite every path it sends, and every
              ;; path it forgets becomes a "No such file or directory" whose
              ;; cause is invisible from the message.
              "-v" (str repo-root ":/workspace")
              "-v" (str repo-root ":" repo-root)
              ;; Per-fork dependency cache, gitignored and in-tree — the same
              ;; move sych makes. The container runs as the CALLING uid so it
              ;; cannot use the image's root-owned ~/.m2, and without this the
              ;; daemon re-resolves every dependency on each cold boot.
              "-e" (str "HOME=" repo-root "/.protogen/home")
              "-e" (str "PROTOGEN_RENDER_SOCKET=" socket-path)
              "-e" (str "PROTOGEN_WORKSPACE=" repo-root)
              "-e" (str "PROTOGEN_WORKTREE_HASH=" worktree-hash)
              "-e" (str "PROTOGEN_CONTAINER=" container-name)
              "-e" (str "PROTOGEN_IMAGE_TAG=" image-tag)
              "--label" "protogen.role=render-daemon"
              "--label" (str "protogen.worktree=" repo-root)
              "--entrypoint" "bash"
              image-tag
              "-lc" (str "cd " repo-root "/tools/scratchcard && exec clojure -M:daemon")]
        {:keys [err exit]} (apply p/sh argv)]
    (when-not (zero? exit)
      (binding [*out* *err*]
        (println (str "scratchcard: docker run failed (exit " exit "): " (str/trim (str err)))))
      (System/exit 5))))

(defn- wait-up [ms]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (daemon-up?) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 250) (recur))))))

(defn ensure-daemon! []
  (when-not (daemon-up?)
    (fs/create-dirs runtime-dir)
    ;; flock so concurrent invocations do not double-spawn. No explicit
    ;; .release — bb's SCI sandbox blocks FileLockImpl.release; with-open
    ;; closing the channel is what frees it.
    (with-open [ch (FileChannel/open (npath lock-path)
                                     (into-array StandardOpenOption
                                                 [StandardOpenOption/CREATE
                                                  StandardOpenOption/WRITE]))]
      (.lock ch)
      (when-not (daemon-up?)
        (binding [*out* *err*]
          (println "scratchcard: starting warm daemon (cold boot ~10-30s)…"))
        (spawn!)
        (when-not (wait-up 180000)
          (binding [*out* *err*]
            (println (str "scratchcard: daemon did not bind " socket-path
                          " in 180s — see `docker logs " container-name "`")))
          (System/exit 5))))))

(def ^:private reply-timeout-ms
  ;; STRICTLY WIDER than any server-side deadline, so the daemon's richer typed
  ;; error always wins the race and this cap only trips on a wedged daemon.
  600000)

(defn request! [op args]
  (with-open [ch (SocketChannel/open (UnixDomainSocketAddress/of (npath socket-path)))]
    (let [out (PrintWriter. (Channels/newOutputStream ch))
          in (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) "UTF-8") 1048576)]
      (.print out (str (json/generate-string {:id 1 :op op :args (or args {})}) "\n"))
      (.flush out)
      (let [line (deref (future (.readLine in)) reply-timeout-ms ::timeout)]
        (cond
          (= ::timeout line)
          {:ok false :error "DAEMON_TIMEOUT"
           :message (str "no reply within " reply-timeout-ms "ms — wedged daemon? "
                         "`scratchcard restart` discards it")}
          ;; A closed stream is NEVER a success-shaped nil.
          (nil? line)
          {:ok false :error "NO_RESPONSE"
           :message (str "daemon closed without a reply — `docker logs " container-name "`")}
          :else (json/parse-string line true))))))

(defn- failed?
  "Did the CALLER's request fail — at either level?

  A reply carries TWO ok flags and they answer different questions. The OUTER
  one is transport: did the daemon accept and answer the request at all. The
  INNER `:value :ok` is the RENDER: did the screen build and draw. Reading only
  the outer flag answers \"did the socket round trip work\", which is never the
  question the caller asked, so every input rejection — a missing screen, a bad
  class token — exited 0 and reported success to anything checking `$?`."
  [resp]
  (and (map? resp)
       (or (false? (:ok resp))
           (false? (:ok (:value resp))))))

(defn- emit! [resp]
  (println (json/generate-string resp))
  (System/exit (if (failed? resp) 1 0)))

(def accepted-flags
  "The flags each command accepts, mirroring the daemon's CLOSED arg sets.

  The daemon refuses an unknown argument with UNKNOWN_ARGUMENT and names the
  accepted set. That guarantee ends at this client unless the client keeps it:
  the request map was built by pulling known keys out of `opts`, so anything
  else was dropped before the daemon could see it. A typo'd `--fil screen.edn`
  therefore rendered the DEFAULT example and reported it CLEAN — a green
  verdict about a file the author never named.

  Keep in step with the `case` below; a flag added there and not here is
  refused, which is the safe direction to be wrong in."
  {"regenerate" #{"--file" "--card" "--res" "--families" "--modes"
                  "--keep" "--timeout-ms" "--bp-from-canvas"}
   "diff" #{"--card" "--from" "--to"}
   "status" #{}
   "up" #{}
   "stop" #{}
   "restart" #{}
   "ping" #{}})

(defn- parse-resolutions [s]
  (when (seq s)
    (mapv (fn [tok]
            (let [[w h] (str/split tok #"x")]
              {:w (parse-long w) :h (parse-long h)}))
          (str/split s #","))))

(defn- parse-ints
  "A comma-separated index list — `--families 0,2`, `--modes 1`.

  A token that is not an integer becomes nil rather than throwing here, and
  the daemon's own schema refuses the resulting list by name. One refusal, at
  the seam that owns the vocabulary, beats a second copy of it in this client."
  [s]
  (when (seq s)
    (mapv parse-long (str/split s #","))))

(defn- parse-bool
  "`true`/`false` for a flag the daemon takes as a boolean.

  Anything else is REFUSED here rather than coerced: `--bp-from-canvas 0` read
  as truthy would silently sweep the breakpoint tiers, which is the opposite
  of what the caller typed, and the whole point of the flag is that it changes
  what the render means."
  [flag s]
  (case s
    "true" true
    "false" false
    (do (binding [*out* *err*]
          (println (str "scratchcard: " flag " takes true or false; got " (pr-str s))))
        (System/exit 2))))

(defn -main [& args]
  ;; `argv`, not `rest` — binding `rest` would shadow `clojure.core/rest`, the
  ;; rename hazard this repo has been bitten by twice: the linter stays green
  ;; and a missed reference dies at runtime.
  (let [[cmd & argv] args
        ;; An ODD argument count would throw a bare stack trace out of
        ;; `hash-map`; usage is the useful answer.
        _ (when (odd? (count argv))
            (binding [*out* *err*]
              (println "scratchcard: options must come in --flag value pairs; got"
                       (pr-str argv)))
            (System/exit 2))
        opts (apply hash-map argv)
        ;; An unknown flag is REFUSED, never dropped — the client half of the
        ;; daemon's closed-arg contract. An empty set is truthy, so a command
        ;; taking no flags still refuses one.
        _ (when-let [accepted (accepted-flags cmd)]
            (when-let [unknown (seq (sort (remove accepted (keys opts))))]
              (binding [*out* *err*]
                (println (str "scratchcard: unknown flag(s) for `" cmd "`: "
                              (str/join " " unknown)
                              " — accepted: "
                              (if (seq accepted)
                                (str/join " " (sort accepted))
                                "(none)"))))
              (System/exit 2)))]
    (case (or cmd "help")
      "status" (emit! (if (daemon-up?)
                        (request! "status" {})
                        {:ok false :error "DAEMON_DOWN" :daemon "down"
                         :socket socket-path :container container-name
                         :message "not running — any command starts it"}))
      "up" (do (ensure-daemon!) (emit! {:ok true :daemon "up" :socket socket-path}))
      "stop" (emit! (if (daemon-up?) (request! "stop" {})
                        {:ok true :daemon "already down"}))
      "restart" (do (when (daemon-up?) (try (request! "stop" {}) (catch Exception _ nil)))
                    (docker-rm!)
                    (ensure-daemon!)
                    (emit! {:ok true :daemon "restarted" :socket socket-path}))
      "regenerate" (do (ensure-daemon!)
                       (emit! (request! "regenerate"
                                        (cond-> {:file (or (get opts "--file")
                                                           (str repo-root "/tools/scratchcard/example/hello.edn"))}
                                          (get opts "--card") (assoc :card (get opts "--card"))
                                          (get opts "--res") (assoc :resolutions
                                                                    (parse-resolutions (get opts "--res")))
                                          (get opts "--families") (assoc :families
                                                                         (parse-ints (get opts "--families")))
                                          (get opts "--modes") (assoc :modes
                                                                      (parse-ints (get opts "--modes")))
                                          (get opts "--keep") (assoc :keep
                                                                     (parse-long (get opts "--keep")))
                                          (get opts "--timeout-ms") (assoc :timeout-ms
                                                                           (parse-long (get opts "--timeout-ms")))
                                          (get opts "--bp-from-canvas")
                                          (assoc :bp-from-canvas?
                                                 (parse-bool "--bp-from-canvas"
                                                             (get opts "--bp-from-canvas")))))))
      "diff" (do (ensure-daemon!)
                 (emit! (request! "diff"
                                  (cond-> {}
                                    (get opts "--card") (assoc :card (get opts "--card"))
                                    (get opts "--from") (assoc :from (get opts "--from"))
                                    (get opts "--to") (assoc :to (get opts "--to"))))))
      "ping" (do (ensure-daemon!) (emit! (request! "ping" {})))
      (do (println "usage: scratchcard <ping|status|up|stop|restart|regenerate|diff>")
          (println "  regenerate [--file P] [--card C] [--res 800x480,390x844]")
          (println "             [--families 0,1,2]   0 asgard, 1 vanilla, 2 stock")
          (println "             [--modes 0,1]        0 light, 1 dark")
          (println "             [--bp-from-canvas true|false]")
          (println "                                  default false: EVERY cell renders at")
          (println "                                  bp 0, so md:/lg:/xl: styles never apply")
          (println "             [--keep N] [--timeout-ms N]")
          (println "  diff       [--card C] [--from latest|previous|N] [--to ...]")
          (println (str "  worktree " repo-root))
          (println (str "  hash     " worktree-hash))
          (println (str "  socket   " socket-path))
          (System/exit 2)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
