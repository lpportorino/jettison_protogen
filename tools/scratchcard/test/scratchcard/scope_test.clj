(ns scratchcard.scope-test
  "Pins the per-fork name derivation.

  WHAT THESE TESTS ARE FOR: every machine-global name the daemon touches is
  derived here, so a silent change to the derivation would not break a build —
  it would strand a running daemon under an old name and spawn a second one
  under the new, which looks like a flaky daemon rather than a code change.
  These pin the derivation against fixed inputs so that change cannot be
  silent.

  `scope` is pure and takes the root as an argument precisely so these run
  without git, without the environment, and without a container."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [scratchcard.scope :as scope])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(def ^:private known-root
  "A fixed absolute path standing in for a checkout. Never touched on disk.

  NOT under /home: `tools/lint/no_host_paths.sh` bans an operator-home path in
  any checked-in file, and it does not care that this one is fictional — the
  ban is on the SHAPE, because a real one bakes one operator's layout into a
  tree every consumer clones."
  "/srv/example/git/jettison_protogen")

(deftest sha256-hex-matches-known-vectors
  (testing "the empty string's sha256 — the standard vector"
    (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
           (scope/sha256-hex ""))))
  (testing "a byte with the high bit set is not sign-extended into the hex"
    ;; U+00FF is two UTF-8 bytes; a naive (format "%02x" b) over a signed byte
    ;; yields "ffffffc3" rather than "c3". This pins that it does not.
    (is (= 64 (count (scope/sha256-hex "ÿ"))))
    (is (re-matches #"[0-9a-f]{64}" (scope/sha256-hex "ÿ")))))

(deftest worktree-hash-is-the-sha256-prefix
  (is (= scope/hash-length (count (scope/worktree-hash known-root))))
  (is (= (subs (scope/sha256-hex known-root) 0 scope/hash-length)
         (scope/worktree-hash known-root)))
  (testing "deterministic across calls"
    (is (= (scope/worktree-hash known-root) (scope/worktree-hash known-root))))
  (testing "two forks with the SAME basename get different keys — the whole point"
    (let [a "/srv/example/a/jettison_protogen"
          b "/srv/example/b/jettison_protogen"]
      (is (not= (scope/worktree-hash a) (scope/worktree-hash b))))))

(deftest scope-derives-every-name-from-the-key
  (let [s (scope/scope known-root)
        k (:worktree-hash s)]
    (testing "every machine-global name carries the key"
      (is (str/includes? (:runtime-dir s) k))
      (is (str/includes? (:socket-path s) k))
      (is (str/includes? (:lock-path s) k))
      (is (= (str "protogen-render-" k) (:container-name s))))
    (testing "socket and lock are siblings in the runtime dir"
      (is (= (:runtime-dir s) (subs (:socket-path s) 0 (count (:runtime-dir s)))))
      (is (= (:runtime-dir s) (subs (:lock-path s) 0 (count (:runtime-dir s))))))
    (testing "on-disk state is hash-free — the path IS the worktree"
      (is (not (str/includes? (:scratch-root s) k)))
      (is (str/starts-with? (:scratch-root s) known-root)))
    (testing "labels let a caller target THIS fork exactly rather than by name pattern"
      (is (= known-root (get (:labels s) "protogen.worktree")))
      (is (= "render-daemon" (get (:labels s) "protogen.role"))))
    (testing "the runtime dir is owner-only — the fallback base is /tmp"
      (is (= "rwx------" (:runtime-dir-mode s))))))

(deftest socket-path-length-is-refused-before-bind
  (testing "a normal path is accepted"
    (let [p "/run/user/1000/protogen/0123456789abcdef/render.sock"]
      (is (nil? (scope/socket-path-problem p)))
      (is (= p (scope/check-socket-path! p)))))
  (testing "an over-long path is refused, and the message names both lengths"
    (let [p (str "/tmp/" (str/join (repeat scope/sun-path-max "x")) "/render.sock")
          problem (scope/socket-path-problem p)]
      (is (some? problem))
      (is (str/includes? problem (str (alength (.getBytes p "UTF-8")))))
      (is (str/includes? problem (str (dec scope/sun-path-max))))))
  (testing "check-socket-path! throws with a typed error rather than a bare message"
    (let [p (str "/tmp/" (str/join (repeat scope/sun-path-max "x")) "/render.sock")
          e (is (thrown? clojure.lang.ExceptionInfo (scope/check-socket-path! p)))]
      (is (= "SOCKET_PATH_TOO_LONG" (:error (ex-data e))))))
  (testing "the boundary is exact — sun-path-max bytes does not fit, one less does"
    ;; sun_path holds sun-path-max bytes INCLUDING the NUL, so the longest
    ;; usable path is one byte shorter. Pin both sides so an off-by-one in
    ;; either direction fails here rather than at bind time.
    (let [at (str/join (repeat scope/sun-path-max "x"))
          under (str/join (repeat (dec scope/sun-path-max) "x"))]
      (is (some? (scope/socket-path-problem at)))
      (is (nil? (scope/socket-path-problem under))))))

(deftest discover-repo-root-refuses-a-non-checkout
  (testing "a fresh temp dir outside any checkout yields nil, never a fallback"
    ;; A fallback to the passed dir would hand out a hash for a
    ;; non-repository, and every name derived from it would look valid while
    ;; belonging to nothing.
    (let [tmp (str (Files/createTempDirectory "scratchcard-scope-test"
                                              (into-array FileAttribute [])))]
      (is (nil? (scope/discover-repo-root tmp)))
      (testing "and `current` throws rather than inventing a scope"
        (let [e (is (thrown? clojure.lang.ExceptionInfo (scope/current tmp)))]
          (is (= "NO_REPO_ROOT" (:error (ex-data e))))))))
  (testing "this checkout resolves to a real root"
    (let [here (System/getProperty "user.dir")]
      (is (some? (scope/discover-repo-root here))))))
