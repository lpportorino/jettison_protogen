(ns scratchcard.scope-test
  "Pins the per-fork name derivation.

  WHAT THESE TESTS ARE FOR: every machine-global name the daemon touches is
  derived here, so a silent change to the derivation would not break a build —
  it would strand a running daemon under an old name and spawn a second one
  under the new, which looks like a flaky daemon rather than a code change.
  These pin the derivation against fixed inputs so that change cannot be
  silent.

  `scope` is pure and takes the root as an argument precisely so the derivation
  tests run without git, without the environment, and without a container.

  TWO TESTS HERE ARE NOT IN THAT CLASS, and say so rather than blurring it:
  `discover-repo-root-refuses-a-non-checkout` shells out to git, and
  `client-mirrors-the-jvm-derivation` READS `bin/scratchcard.bb` off disk and so
  requires CWD to be `tools/scratchcard` — it refuses with `CLIENT_UNREADABLE`
  rather than passing when it cannot find the client."
  (:require
   [clojure.java.io :as io]
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
    ;; U+00FF is two UTF-8 bytes, both with the high bit set. A JVM byte is
    ;; SIGNED, and `Integer/toHexString` — which the hex loop calls — widens to
    ;; int first, so an unmasked 0xc3 hexes as "ffffffc3" rather than "c3".
    ;; (`format "%02x"` does NOT have this problem: java's Formatter
    ;; special-cases Byte and adds 2^8. Naming the wrong culprit here is how the
    ;; masking looks optional.) This pins the width the mask and pad produce.
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

(def ^:private client-path "bin/scratchcard.bb")

(def ^:private client-source
  "The babashka client's source TEXT.

  Read from disk on purpose. `bin/scratchcard.bb` is a SECOND home for the
  derivation this namespace owns, and the client runs under babashka — no JVM
  test can call into it, so a textual mirror is the only form available. Its
  absence is what let that file's own docstring claim an assertion that did
  not exist."
  (delay
    (let [f (io/file client-path)]
      (when-not (.isFile f)
        (throw (ex-info (str "cannot read " client-path " from "
                             (System/getProperty "user.dir")
                             " — this suite runs with CWD=tools/scratchcard")
                        {:error "CLIENT_UNREADABLE"})))
      (slurp f))))

(defn- sole-capture
  "The one capture of `rx` in the client, or a throw naming `what`.

  A MISS MUST STOP THE CLAUSE rather than yield nil. A regex that stopped
  matching after a refactor would otherwise turn every assertion below into a
  comparison of two nils and report a perfect mirror over nothing — a gate going
  green on what it never judged.

  IT RAISES, so clojure.test records an ERROR rather than a FAILURE, and that is
  the honest colour: a lost anchor means this clause could not RUN, which is not
  the same claim as the two homes disagreeing. Keep the distinction — where a
  divergence CAN be expressed as a property of the captured text, assert it with
  `is` so it fails as a verdict; reserve the raise for the anchor itself."
  [rx what]
  (let [ms (re-seq rx @client-source)]
    (when-not (= 1 (count ms))
      (throw (ex-info (str "expected exactly one " what " in " client-path
                           ", found " (count ms))
                      {:error "MIRROR_ANCHOR_LOST" :what what :found (count ms)})))
    (second (first ms))))

(deftest client-mirrors-the-jvm-derivation
  ;; Every name below has two homes: `scratchcard.scope` in the JVM and
  ;; `bin/scratchcard.bb` in the client. They must agree or the client binds a
  ;; socket the daemon never looks at, which presents as a daemon that will not
  ;; start rather than as the constant drift it is.
  (let [s (scope/scope known-root)]
    (testing "sun-path-max"
      (is (= scope/sun-path-max
             (parse-long (sole-capture #"(?s)\(def sun-path-max\b.*?\n\s+(\d+)\)"
                                       "sun-path-max definition")))))
    (testing "the hash length kept from the repo-root digest"
      (is (= scope/hash-length
             (parse-long (sole-capture #"\(subs \(sha256-hex repo-root\) 0 (\d+)\)"
                                       "worktree-hash derivation")))))
    (testing "the container name prefix"
      (is (str/starts-with?
           (:container-name s)
           (sole-capture #"\(str \"(protogen-render-)\" worktree-hash\)"
                         "container-name derivation"))))
    (testing "the socket and lock file names"
      (is (str/ends-with?
           (:socket-path s)
           (str "/" (sole-capture #"\(fs/path runtime-dir \"(render\.sock)\"\)"
                                  "socket-path derivation"))))
      (is (str/ends-with?
           (:lock-path s)
           (str "/" (sole-capture #"\(fs/path runtime-dir \"(render\.lock)\"\)"
                                  "lock-path derivation")))))
    (testing "the runtime dir — the segment the socket actually hangs off"
      ;; This is the clause the first version of this test lacked, and its
      ;; absence hid a REAL divergence: the client used a bare `or` over
      ;; `XDG_RUNTIME_DIR` where `scope/runtime-base` uses `not-empty`, so a
      ;; set-but-empty value gave the client a relative path and the JVM /tmp.
      ;; Mirroring the constants while leaving the JOIN unmirrored is how a
      ;; green test coexists with a daemon that will not start.
      ;; Anchor on the DEF, which survives any change to its body, and assert
      ;; the PROPERTIES on the captured body — so a divergence is a FAIL naming
      ;; this clause, not an ERROR from a regex that stopped matching.
      (let [body (-> (sole-capture #"(?s)\(def runtime-dir(.*?)\(def socket-path"
                                   "runtime-dir definition")
                     ;; COMMENTS STRIPPED BEFORE ASSERTING, and this is not
                     ;; hygiene. The first version of this clause matched the
                     ;; client's own explanatory comment — which names
                     ;; `not-empty` — so it stayed GREEN with the code reverted
                     ;; to the divergent form. Only the mutation found it; the
                     ;; passing run looked identical either way.
                     (str/replace #"(?m);;.*$" ""))]
        (is (str/includes? body "not-empty")
            "the client must treat an empty XDG_RUNTIME_DIR as unset, as scope/runtime-base does")
        (is (str/includes? body "\"/tmp\"")
            "the client must share scope/runtime-base's /tmp fallback")
        (is (str/includes? body "\"protogen\"")
            "the client must share scope's `protogen` path segment"))
      (is (str/includes? (:runtime-dir s) "/protogen/")))
    (testing "the client refuses a repo root that does not contain its cwd"
      ;; NOT "the same way the JVM does" — the containment check mirrors `scope`,
      ;; the env scrub deliberately does NOT exist there, and conflating the two
      ;; is what an earlier version of this label did.
      ;;
      ;; ANCHORED ON CODE SHAPE, NOT PROSE, and note WHY that works here: this
      ;; clause applies no comment-stripping (unlike the runtime-dir clause
      ;; above), so what keeps it off the client's English is that both anchors
      ;; are quote-bearing forms prose cannot contain. Verify that property holds
      ;; before adding a third anchor — a bare word would match the docstring.
      (let [src @client-source]
        (is (str/includes? src "\"env\" \"-u\" \"GIT_DIR\" \"-u\" \"GIT_WORK_TREE\"")
            "the client must scrub GIT_DIR/GIT_WORK_TREE, as uber.sh and forks.sh do — NOT as scope does, which deliberately declines to touch the environment because in-container GIT_DIR is what resolves the workspace")
        (is (str/includes? src "(contains-dir? root cwd)")
            "the client must verify git's answer CONTAINS its cwd — the fallback fires only when git fails, never when git lies")))))
