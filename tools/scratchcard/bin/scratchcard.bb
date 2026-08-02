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

(def repo-root (or (sh-out "git" "rev-parse" "--show-toplevel") (str (fs/cwd))))
(def worktree-hash (subs (sha256-hex repo-root) 0 16))
(def container-name (str "protogen-render-" worktree-hash))
(def image-tag (or (System/getenv "PROTOGEN_IMAGE_TAG")
                   "jettison-proto-generator-base:latest"))
(def runtime-dir
  (str (fs/path (or (System/getenv "XDG_RUNTIME_DIR") "/tmp") "protogen" worktree-hash)))
(def socket-path (str (fs/path runtime-dir "render.sock")))
(def lock-path (str (fs/path runtime-dir "render.lock")))

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

(defn- emit! [resp]
  (println (json/generate-string resp))
  (System/exit (if (and (map? resp) (false? (:ok resp))) 1 0)))

(defn- parse-resolutions [s]
  (when (seq s)
    (mapv (fn [tok]
            (let [[w h] (str/split tok #"x")]
              {:w (parse-long w) :h (parse-long h)}))
          (str/split s #","))))

(defn -main [& args]
  (let [[cmd & rest] args
        opts (apply hash-map rest)]
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
                                                                    (parse-resolutions (get opts "--res")))))))
      "ping" (do (ensure-daemon!) (emit! (request! "ping" {})))
      (do (println "usage: scratchcard <ping|status|up|stop|restart|regenerate> [--file P] [--card C] [--res 800x480,390x844]")
          (println (str "  worktree " repo-root))
          (println (str "  hash     " worktree-hash))
          (println (str "  socket   " socket-path))
          (System/exit 2)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
