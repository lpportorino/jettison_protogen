(ns scratchcard.scope
  "THE ONE AUTHORITY for every per-fork name the scratch-devcard tool uses.

  Many protogen forks run on one host. Every machine-global name this tool
  touches is therefore keyed by a WORKTREE HASH, so two checkouts never
  contend for a container name, a socket, or a lock — and no checkout can
  observe another's daemon.

  THE KEY IS `sha256(git rev-parse --show-toplevel)`, TRUNCATED. Three rules,
  each of which the sibling repos learned the hard way and state explicitly:

  - Hash the OUTPUT of `git rev-parse --show-toplevel`, never a raw cwd or a
    configured project root. Otherwise a symlinked checkout hashes differently
    depending on which path the caller happened to arrive by, and one worktree
    silently acquires two identities.
  - NOT a git sha — it churns on every commit, so every commit would orphan
    the running daemon.
  - NOT the basename — two forks both named `jettison_protogen` collide, which
    is precisely the case this exists to prevent.

  THE HASH CANNOT BE RECOMPUTED INSIDE THE CONTAINER. Every fork's daemon sees
  the same workspace mount point, so an in-container hash of the repo root
  collides across forks. The host computes it and passes it across the spawn
  boundary as an explicit environment variable; the daemon never derives it.

  NO TCP PORTS, ANYWHERE. Isolation is by hash-keyed paths, which means
  multi-clone and git-worktree setups auto-isolate with no port-allocation
  contract to keep in step. There is nothing to allocate and nothing to
  collide.

  WHY THE SOCKET LIVES UNDER $XDG_RUNTIME_DIR rather than beside the checkout:
  an AF_UNIX path is bounded by `sun_path`, and a repo-relative socket under a
  deeply-nested worktree silently exceeds it. `runtime-base` keeps the path
  short regardless of how deep the checkout sits, and `check-socket-path!`
  refuses rather than letting `bind` fail obscurely later."
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [scratchcard.digest :as digest]))

(def hash-length
  "Hex characters kept from the sha256 of the repo root.

  16 matches the sibling fleet's daemon key. It is a NAMESPACING key, never a
  security boundary: the only cost of a collision is two forks sharing a
  daemon, and 64 bits of a sha256 makes that unreachable in a directory tree."
  16)

(def sun-path-max
  "Bytes available for an AF_UNIX path, including the trailing NUL.

  108 on Linux (`sun_path[108]` in `<sys/un.h>`). Exceeding it does not
  produce a clear error at bind time, which is why `check-socket-path!`
  refuses up front with the measured length."
  108)

(defn sha256-hex
  "Lowercase hex sha256 of `s`, read as UTF-8.

  Delegates to `scratchcard.digest` so the per-fork key and the manifest's
  file hashes cannot be computed two different ways."
  ^String [^String s]
  (digest/of-string s))

(defn discover-repo-root
  "The canonical worktree root of `dir` per git, or nil when `dir` is not in a
  git checkout.

  Deliberately returns nil rather than falling back to `dir`: a fallback would
  hand out a hash for a non-repository, and every name derived from it would
  look valid while belonging to nothing. Callers decide what to do with the
  refusal."
  [dir]
  (let [{:keys [exit out]} (shell/sh "git" "rev-parse" "--show-toplevel" :dir dir)]
    (when (zero? exit)
      (let [root (str/trim out)]
        (when-not (str/blank? root) root)))))

(defn worktree-hash
  "The per-fork key: the first `hash-length` hex characters of the repo root's
  sha256."
  ^String [^String repo-root]
  (subs (sha256-hex repo-root) 0 hash-length))

(defn runtime-base
  "Directory holding the per-fork runtime dirs.

  `$XDG_RUNTIME_DIR` when set, else `/tmp`. The fallback is why `scope`
  reports `:runtime-dir-mode` — under `/tmp` the directory would otherwise be
  world-traversable, and this socket accepts render requests."
  ^String []
  (or (not-empty (System/getenv "XDG_RUNTIME_DIR")) "/tmp"))

(defn scope
  "Every per-fork name derived from `repo-root`, as one map.

  PURE, and takes the root as an argument so a test can pin the derivation
  against a known path without touching git or the environment. `current` is
  the discovering wrapper."
  [^String repo-root]
  (let [wt-hash (worktree-hash repo-root)
        runtime-dir (str (runtime-base) "/protogen/" wt-hash)]
    {:repo-root repo-root
     :worktree-hash wt-hash
     :runtime-dir runtime-dir
     ;; 0700: the fallback base is /tmp, and this socket takes render requests.
     :runtime-dir-mode "rwx------"
     :socket-path (str runtime-dir "/render.sock")
     :lock-path (str runtime-dir "/render.lock")
     :container-name (str "protogen-render-" wt-hash)
     ;; Labels are how a test — or an operator — targets THIS fork's container
     ;; exactly, rather than pattern-matching a name and killing a sibling's.
     :labels {"protogen.role" "render-daemon"
              "protogen.worktree" repo-root}
     ;; Hash-free by construction: the path IS the worktree, so a second key
     ;; would only add a way for the two to disagree.
     :scratch-root (str repo-root "/.protogen/scratch")
     :state-root (str repo-root "/.protogen")}))

(defn socket-path-problem
  "nil when `socket-path` fits `sun-path-max`, else a message naming both
  lengths.

  Separated from the throwing form so a caller that wants to REPORT the
  condition (a status op) does not have to catch an exception to do it."
  [^String socket-path]
  (let [len (alength (.getBytes socket-path "UTF-8"))]
    (when (>= len sun-path-max)
      (str "AF_UNIX socket path is " len " bytes, limit is " (dec sun-path-max)
           " plus NUL: " socket-path))))

(defn check-socket-path!
  "Return `socket-path`, or throw when it cannot be bound.

  Fails HERE rather than at bind time, where an over-long path surfaces as an
  opaque errno with no mention of the limit it broke."
  ^String [^String socket-path]
  (if-let [problem (socket-path-problem socket-path)]
    (throw (ex-info problem {:error "SOCKET_PATH_TOO_LONG"
                             :socket-path socket-path
                             :limit sun-path-max}))
    socket-path))

(defn current
  "`scope` for the checkout containing `dir`, with the socket path checked.

  Throws when `dir` is not in a git checkout: every name this tool uses is
  derived from the worktree root, so there is nothing honest to return."
  ([] (current (System/getProperty "user.dir")))
  ([dir]
   (if-let [root (discover-repo-root dir)]
     (doto (scope root) (-> :socket-path check-socket-path!))
     (throw (ex-info (str "not inside a git checkout: " dir)
                     {:error "NO_REPO_ROOT" :dir dir})))))
