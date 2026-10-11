(ns press.config-test
  "The configuration a consumer binds: the defaults fit this repository with
   nothing set; a bound map reaches every path the library resolves, every
   host command it returns, the name the house file is hashed under and the
   runbook's how-to-run sentences; a map that is not a configuration is
   refused before anything runs."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [press.config :as config]
            [press.deck :as deck]
            [press.doc :as doc]
            [press.doc.figure :as fig]
            [press.doc.fixture :as fx]
            [press.doc.io :as dio]))

(use-fixtures :each fx/cleanup)

(deftest the-defaults-fit-this-repository
  (testing "the derived root holds the house file the defaults name"
    (is (.isFile (io/file (config/default-root) "tools/press/press/house.json")))
    (is (= (config/default-root) (config/root))))
  (testing "the documented defaults"
    (is (= {:launcher "tools/press/bin/press" :house "tools/press/press/house.json"
            :build-dir ".press/doc-build" :image-hint "make -f press.mk image"}
           (select-keys config/*config* [:launcher :house :build-dir :image-hint]))))
  (testing "a relative path is taken from the root, an absolute one stands"
    (is (= (str (.getCanonicalFile (io/file (config/root) "a/b"))) (config/from-root "a/b")))
    (is (= "/a/b" (config/from-root "/a/b")))))

(defn- refusal
  "The `:press/refusal` code `(f)` throws, or `:no-throw`."
  [f]
  (try (f) :no-throw
       (catch clojure.lang.ExceptionInfo e (:press/refusal (ex-data e)))))

(deftest a-map-that-is-not-a-configuration-is-refused
  (is (= :press/bad-config (refusal #(config/with-config {:rot "/x"} (config/root)))) "an unknown key")
  (is (= :press/bad-config (refusal #(config/with-config {:launcher ""} (config/launcher)))) "an empty value")
  (is (= :press/bad-config (refusal #(config/with-config {:root 3} (config/root)))) "a value that is not text")
  (is (= "/x" (config/with-config {:root "/x"} (config/root))) "the control: a valid override binds"))

(def ^:private other
  "A consumer's configuration with nothing in common with the defaults but
   the root, which each test supplies."
  {:launcher "bin/press" :house "style/house.json" :build-dir "out/build"
   :image-hint "make press-image" :forms-hint "Run each with `tool eval`."
   :review-hint "Ask a second reader to open them."})

(defn- other-root!
  "A fresh directory to serve as a consumer's root, holding a copy of the
   house constants at `style/house.json` and the fixture document (with
   `fig1`) at `docs/guide`. Returns its path."
  []
  (let [root (fx/scratch-dir!)
        d (io/file root "docs" "guide")]
    (io/make-parents (io/file root "style" "house.json"))
    (spit (io/file root "style" "house.json") (slurp (config/from-root (config/house))))
    (.mkdirs d)
    (spit (io/file d "doc.edn") (pr-str (fx/with-figure)))
    (fx/write-fig! (str d) fx/diagram)
    (str root)))

(deftest a-bound-configuration-reaches-paths-commands-and-hash-names
  (let [root (other-root!)]
    (config/with-config (assoc other :root root)
      (testing "relative arguments are read from the bound root, and the house file from its bound place"
        (is (= {:ok? true :violations []} (select-keys (doc/check "docs/guide") [:ok? :violations])))
        (is (= (str (io/file root "out" "build" "guide" "guide.html"))
               (:path (doc/build! "docs/guide" "out/build/guide")))))
      (testing "every returned command starts with the bound launcher and spells paths from the bound root"
        (is (= ["bin/press" "fig" "solve" "out/build/guide/figs/fig1.fig.json" "out/build/guide/figs/fig1"]
               (:argv (doc/fig-solve! "docs/guide" "fig1"))))
        (is (= ["bin/press" "fig" "pick" "out/build/guide/figs/fig1.fig.json" "out/build/guide/figs/fig1" "2"
                "docs/guide/figs/fig1.pick.json" "docs/guide/figs/fig1.svg"]
               (:argv (doc/fig-pick! "docs/guide" "fig1" 2))))
        (is (= [["bin/press" "fig" "check" "docs/guide/figs/fig1.svg"]
                ["bin/press" "pdf" "out/build/guide/guide.html" "out/build/guide/pdf" "--name" "guide"]]
               (:steps (doc/pdf "docs/guide"))))
        (is (= ["bin/press" "qa" "/x/deck.html" "/x/out"] (:argv (deck/qa "/x/deck.html" "/x/out")))))
      (testing "a house that is not the tool's default travels with every shell line, and is offered for an argv"
        (is (= {"PRESS_HOUSE" "style/house.json"} (config/launcher-env)))
        (is (= "PRESS_HOUSE=style/house.json bin/press fig solve out/build/guide/figs/fig1.fig.json out/build/guide/figs/fig1"
               (:shell (doc/fig-solve! "docs/guide" "fig1"))))
        (is (every? #(str/starts-with? % "PRESS_HOUSE=style/house.json bin/press ")
                    (str/split (:shell (doc/pdf "docs/guide")) #" && "))))
      (testing "the house file is hashed under the name it was configured by"
        (let [names (set (map first (dio/stamp-inputs (io/file root "docs" "guide") ["fig1"])))]
          (is (contains? names "style/house.json"))
          (is (not (contains? names "tools/press/press/house.json")))))
      (testing "the runbook carries the bound launcher, image command and both sentences — and none of the defaults"
        (let [text (slurp (:path (doc/runbook! "docs/guide")))]
          (doseq [needle ["`bin/press`, in a container" "Build its image once with `make press-image`."
                          "Run each with `tool eval`." "Ask a second reader to open them."
                          "bin/press fig solve out/build/guide/figs/fig1.fig.json out/build/guide/figs/fig1"
                          "(doc/build! \"docs/guide\" \"out/build/guide\")"]]
            (is (str/includes? text needle) needle))
          (doseq [absent ["tools/press" "press.mk" ".press/" "Clojure REPL"]]
            (is (not (str/includes? text absent)) absent)))))
    (testing "with the default house nothing is added: the launcher reads its own"
      (is (= {} (config/launcher-env)))
      (is (= "tools/press/bin/press pdfcheck x.pdf" (dio/shell-line ["tools/press/bin/press" "pdfcheck" "x.pdf"]))))
    (testing "outside the binding the same directory is outside nothing: it is read under the default root again"
      (is (= :doc/no-dir (fx/refusal #(doc/check "docs/guide")))))))

(deftest a-missing-house-file-is-refused-by-its-configured-place
  (let [root (other-root!)]
    (config/with-config (assoc other :root root :house "style/absent.json")
      (is (= :doc/no-house (fx/refusal #(doc/check "docs/guide")))))))

(deftest the-resolved-house-identifies-the-default
  (let [root (str (fx/scratch-dir!))
        marker "tools/press/press/house.json"
        default-house (config/from-root marker)]
    (testing "the same relative spelling under a consumer root is a different house"
      (io/make-parents (io/file root marker))
      (spit (io/file root marker) (slurp default-house))
      (is (= {"PRESS_HOUSE" marker}
             (config/with-config {:root root :house marker} (config/launcher-env)))))
    (testing "the equivalent absolute default path adds no override"
      (is (= {} (config/with-config {:house default-house} (config/launcher-env)))))
    (testing "canonical equivalence tolerates dot segments"
      (is (= {} (config/with-config {:house "tools/press/press/../press/house.json"}
                  (config/launcher-env)))))))

(deftest a-packaged-library-forwards-the-house-without-guessing-its-root
  (let [root (str (fx/scratch-dir!))
        marker "tools/press/press/house.json"
        public-house (fig/house)
        consumer-house (assoc public-house "font-family" "Consumer Font")
        launcher (config/from-root (config/launcher))]
    (io/make-parents (io/file root marker))
    (spit (io/file root marker) (json/generate-string consumer-house))
    (with-redefs-fn {(ns-resolve (quote press.config) (quote resource-dir)) (constantly nil)
                     (ns-resolve (quote press.config) (quote defaults)) (assoc config/defaults :root root)}
      (fn []
        (config/with-config {:root root :house marker :launcher launcher}
          (testing "JAR defaults may discover the consumer root; it is never proof of the tool's own house"
            (is (= "Consumer Font" (get (fig/house) "font-family")))
            (is (not= (get public-house "font-family") (get (fig/house) "font-family")))
            (is (= {"PRESS_HOUSE" marker} (config/launcher-env)))
            (is (str/starts-with? (dio/shell-line [launcher "pdfcheck" "sample.pdf"])
                                  (str "PRESS_HOUSE=" marker " "))))
          (testing "even an apparent absolute default is forwarded when file resources reveal no tool root"
            (config/with-config {:house (config/from-root marker)}
              (is (= {"PRESS_HOUSE" (config/house)} (config/launcher-env))))))))))
