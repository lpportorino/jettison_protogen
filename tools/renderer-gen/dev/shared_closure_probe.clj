(ns shared-closure-probe
  "Evidence probe: what a CANDIDATE shared source root would actually have to
  contain. Read-only; mutates nothing.

  Run:
    cd tools/renderer-gen && clojure -Sdeps '{:aliases {:p {:extra-paths [\"dev\"]}}}' \\
      -M:p dev/shared_closure_probe.clj <ns> [<ns> …]

  WHY THIS EXISTS. Some of this seam's namespaces are kept byte-identical to a
  consumer's own copies, and the obvious improvement is to stop copying and ship
  them as a source root the consumer depends on. That plan needs one number
  nobody had: the REQUIRE CLOSURE of the candidate set. A shared root is not the
  files someone listed — it is every first-party namespace those files load, and
  a root missing one of them does not fail a review, it fails to LOAD.

  A candidate set is passed as ARGUMENTS rather than baked in, deliberately.
  Which namespaces a given consumer mirrors is that consumer's fact and lives in
  that consumer's gate; a roster copied into this repository would be a second
  home for it, free to rot silently against the first. What this probe supplies
  is the part protogen genuinely owns — given a set, what does it drag in.

  Reads the `ns` FORMS under src/ rather than loading anything, so it needs no
  proto classes, no manifests and no container state, and it reports a namespace
  that would fail to compile just as happily as one that would not.

  CARRIES AN `ns` FORM SO IT CAN BE GATED — a dev file without one is collapsed
  by clj-kondo into a shared implicit `user` namespace, and cross-file
  collisions then dominate what it reports for the whole directory."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private src (io/file "src"))

(defn- clj-files []
  (filter #(str/ends-with? (.getName ^java.io.File %) ".clj") (file-seq src)))

(defn- ns-form
  "The first `ns` form in `f`, or nil."
  [f]
  (with-open [r (java.io.PushbackReader. (io/reader f))]
    (loop []
      (let [v (read {:eof ::eof :read-cond :allow} r)]
        (cond (= v ::eof) nil
              (and (seq? v) (= 'ns (first v))) v
              :else (recur))))))

(defn- requires-of [nsf]
  (->> (filter #(and (seq? %) (= :require (first %))) (rest nsf))
       (mapcat rest)
       (map #(if (sequential? %) (first %) %))
       (filter symbol?)
       set))

(def ^:private by-ns
  (into {} (for [f (clj-files)
                 :let [nf (ns-form f)]
                 :when nf]
             [(second nf) {:file (str f) :requires (requires-of nf)}])))

(def ^:private first-party (set (keys by-ns)))

(defn- closure [seeds]
  (loop [seen #{}
         queue (vec seeds)]
    (if-let [n (first queue)]
      (if (seen n)
        (recur seen (subvec queue 1))
        (recur (conj seen n)
               (into (subvec queue 1) (filter first-party (:requires (by-ns n))))))
      seen)))

(let [seeds (map symbol *command-line-args*)
      unknown (remove first-party seeds)]
  (when (empty? seeds)
    (println "usage: … dev/shared_closure_probe.clj <ns> [<ns> …]")
    (println (str "  " (count first-party) " first-party namespaces under src/:"))
    (doseq [n (sort first-party)] (println "   " n))
    (System/exit 2))
  (when (seq unknown)
    ;; Fail loud rather than silently closing over a typo, which would report a
    ;; smaller closure than the real one — the answer that flatters the plan.
    (println "NOT A FIRST-PARTY NAMESPACE UNDER src/:" (str/join " " (sort unknown)))
    (System/exit 2))
  (let [seed-set (set seeds)
        c (closure seeds)
        extra (sort (remove seed-set c))]
    (println (format "candidate set: %d namespace(s)" (count seed-set)))
    (println (format "first-party CLOSURE: %d namespace(s)" (count c)))
    (println)
    (doseq [n (sort c)]
      (println (format "  %-40s %s" n (if (seed-set n) "candidate" "*** PULLED IN ***"))))
    (println)
    (println (format "%d namespace(s) a root of the candidate set would ALSO have to carry:"
                     (count extra)))
    (doseq [n extra] (println "  " n "  <-" (:file (by-ns n))))
    (println)
    (println "third-party requires reached from the closure:")
    (doseq [n (sort (set (remove first-party (mapcat #(:requires (by-ns %)) c))))]
      (println "  " n))))
