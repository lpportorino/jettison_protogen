(ns press.deck-test
  "The deck builder against a temporary asset directory: the palette floors,
   the offline check in BOTH directions (a clean deck passes, an external
   reference, an @import and a network token each fail), asset inlining with
   de-duplication, the H.264 refusal, and `build!` refusing to write."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [press.deck :as deck])
  (:import [java.awt.image BufferedImage]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [javax.imageio ImageIO]))

(defn- refusal
  "The `:deck/refusal` code `(f)` throws — or, when Malli's instrumented
   boundary refused the call first (the test runner instruments every
   `m/=>`), `:malli/boundary` — or `:no-throw`."
  [f]
  (try (f) :no-throw
       (catch clojure.lang.ExceptionInfo e
         (or (:deck/refusal (ex-data e))
             (when (str/starts-with? (ex-message e) "Malli ") :malli/boundary)))))

(defn- with-assets
  "Run `(f dir)` with a 4×4 PNG `pic.png`, a fake `clip.webm` and a fake
   `clip.mp4` in a fresh temporary directory."
  [f]
  (let [dir (str (Files/createTempDirectory "deck-test" (make-array FileAttribute 0)))
        img (BufferedImage. 4 4 BufferedImage/TYPE_INT_RGB)]
    (ImageIO/write img "png" (io/file dir "pic.png"))
    (spit (io/file dir "clip.webm") "not really a video")
    (spit (io/file dir "clip.mp4") "not really a video either")
    (f dir)))

(deftest contrast-and-palette
  (testing "white on black is the WCAG maximum, the house palette passes"
    (is (< 20.9 (deck/contrast "#FFFFFF" "#000000") 21.1))
    (is (= 1.0 (deck/contrast "#808080" "#808080")))
    (is (empty? (deck/palette-failures deck/dark-palette))))
  (testing "a dim grey on the panel fails the 4.5 floor, and says which pair"
    (let [bad (assoc deck/dark-palette :dim "#4A5060")
          fails (deck/palette-failures bad)]
      (is (seq fails))
      (is (every? #(= :dim (:fg %)) fails))
      (is (every? #(< (:ratio %) (:floor %)) fails)))))

(deftest check-both-directions
  (testing "a page with only data: URIs and anchors is self-contained — including
            the URL-encoded anchor an inlined SVG uses for its own gradient"
    (let [v (deck/check "<img src=\"data:image/png;base64,AAAA\"><a href=\"#3\">x</a><style>.a{background:url(data:image/png;base64,BB)} .b{mask:url(\"data:image/svg+xml,%3csvg%3e%3ccircle fill='url(%23a)'/%3e%3c/svg%3e\")}</style>")]
      (is (:self-contained? v))
      (is (= 2 (:data-uris v)))
      (is (empty? (:external v)))))
  (testing "an external src, a CSS url(), an @import and a fetch( each fail"
    (let [v (deck/check "<img src=\"videos/a.mp4\"><link href=\"https://cdn.x/y.css\"><style>@import url(x.css); .b{background:url(\"figures/f.png\")}</style><script>fetch('/api')</script>")]
      (is (not (:self-contained? v)))
      (is (= #{"videos/a.mp4" "https://cdn.x/y.css" "x.css" "figures/f.png"} (set (:external v))))
      (is (= 1 (count (:imports v))))
      (is (= 1 (count (:network-code v))))))
  (testing "inside a script, identifiers and short literals are not externals
            but a path-shaped string still is (measured: a minified three.js
            bundle's resolveURL(a) / getDataURL(a,b) read as externals a, b, a,b)"
    (let [v (deck/check "<script>this.manager.resolveURL(a);Nh.getDataURL(a,b);x.src=\"a\";new URL(c)</script>")]
      (is (:self-contained? v))
      (is (empty? (:external v))))
    (let [v (deck/check "<script>img.src=\"figures/f.png\";el.style.background='url(bg.jpg)';var u=new URL(\"https://x.y/z\")</script>")]
      (is (= #{"figures/f.png" "bg.jpg" "https://x.y/z"} (set (:external v)))))))

(deftest render-inlines-and-dedupes
  (with-assets
    (fn [dir]
      (let [d (deck/example dir "pic.png")
            html (deck/render d)
            v (deck/check html)]
        (testing "the deck's own output is self-contained: theme, font and image inlined"
          (is (:self-contained? v))
          (is (str/includes? html "data:font/woff2;base64,"))
          (is (str/includes? html "data:image/png;base64,")))
        (testing "the image used twice is inlined once and re-pointed — and the
                  page script that re-points the copies ships with it (measured:
                  without it the second image rendered broken)"
          (is (= 1 (count (re-seq #"data:image/png;base64," html))))
          (is (= 1 (count (re-seq #"data-from=\"as0\"" html))))
          (is (str/includes? html "querySelectorAll('[data-from]')")))
        (testing "the theme's palette placeholders are all filled"
          (is (not (str/includes? html "{{"))))
        (testing "slides render in order with the first one on"
          (is (str/includes? html "class=\"slide on\""))
          (is (= 2 (count (re-seq #"<section class=\"slide" html)))))))))

(deftest refusals
  (with-assets
    (fn [dir]
      (testing "a malformed deck is refused before any file is read — by the
                Malli boundary when instrumented, by `render*` otherwise"
        (is (contains? #{:deck/malformed :malli/boundary}
                       (refusal #(deck/render {:title "x" :root dir :slides []})))))
      (testing "a missing asset is refused by name"
        (is (= :deck/missing-asset
               (refusal #(deck/render (deck/example dir "nope.png"))))))
      (testing "an H.264 container is refused unless the deck opts in"
        (let [d (assoc-in (deck/example dir "pic.png") [:slides 1 :body 0] [:deck/vid "clip.mp4" "c"])]
          (is (= :deck/h264 (refusal #(deck/render d))))
          (is (string? (deck/render (assoc d :allow-h264? true))))))
      (testing "a webm inlines as video"
        (let [d (assoc-in (deck/example dir "pic.png") [:slides 1 :body 0] [:deck/vid "clip.webm" "c"])]
          (is (str/includes? (deck/render d) "data:video/webm;base64,"))))
      (testing "an unknown deck form is refused"
        (is (= :deck/unknown-form
               (refusal #(deck/render (assoc-in (deck/example dir "pic.png") [:slides 1 :body 0] [:deck/nope 1])))))))))

(deftest build-writes-or-refuses
  (with-assets
    (fn [dir]
      (let [out (str (io/file dir "out.html"))]
        (testing "a clean deck is written, and the report says what went in"
          (let [r (deck/build! (deck/example dir "pic.png") out)]
            (is (.isFile (io/file out)))
            (is (= (.length (io/file out)) (:bytes r)))
            (is (= 2 (:slides r)))
            (is (= [{:path "pic.png" :mime "image/png" :uses 2}]
                   (map #(dissoc % :bytes) (:assets r))))
            (is (:self-contained? (:check r)))
            (is (empty? (:palette-failures r)))))
        (testing "raw markup with an external image is refused and NOTHING is written"
          (let [out2 (str (io/file dir "refused.html"))
                d (assoc-in (deck/example dir "pic.png") [:slides 1 :body 0]
                            [:deck/raw "<img src=\"https://example.invalid/x.png\">"])]
            (is (= :deck/refused (refusal #(deck/build! d out2))))
            (is (not (.exists (io/file out2))))))
        (testing "a palette under the floor is refused too"
          (let [out3 (str (io/file dir "refused2.html"))
                d (assoc (deck/example dir "pic.png") :palette (assoc deck/dark-palette :ink "#303030"))]
            (is (= :deck/refused (refusal #(deck/build! d out3))))
            (is (not (.exists (io/file out3))))))))))

(deftest qa-command-and-report
  (testing "the qa command is the host press invocation, with the options as flags"
    (let [c (deck/qa "/x/deck.html" "/x/out" {:only [2 29] :scroll-slides [78] :strict? true})]
      (is (= ["tools/press/bin/press" "qa" "/x/deck.html" "/x/out"
              "--only" "2,29" "--scroll-slides" "78" "--strict"] (:argv c)))
      (is (= "/x/out/report.json" (:report c)))
      (is (= ["tools/press/bin/press" "qa" "/x/deck.html" "/x/out"] (:argv (deck/qa "/x/deck.html" "/x/out"))))))
  (with-assets
    (fn [dir]
      (testing "a missing report is refused by name, never inferred"
        (is (= :deck/no-report (refusal #(deck/qa-report dir)))))
      (testing "a report reads back as a verdict with every error named by slide"
        (spit (io/file dir "report.json")
              "{\"totals\":{\"slides\":2,\"slide_errors\":1,\"slide_warnings\":0,\"deck_errors\":0,\"deck_warnings\":1,\"error_slides\":[2],\"warn_slides\":[],\"by_detector\":{\"d1-overflow/error\":1,\"d6-font-fallback/warn\":1}},\"slides\":[{\"n\":1,\"findings\":[]},{\"n\":2,\"findings\":[{\"detector\":\"d1-overflow\",\"severity\":\"error\",\"message\":\"112 px taller\"}]}],\"deck_findings\":[{\"detector\":\"d6-font-fallback\",\"severity\":\"warn\",\"message\":\"no @font-face\"}]}")
        (let [v (deck/qa-report dir)]
          (is (not (:ok? v)))
          (is (= [2] (:error-slides v)))
          (is (= [{:slide 2 :detector "d1-overflow" :message "112 px taller"}] (:errors v)))
          (is (= {"d1-overflow/error" 1 "d6-font-fallback/warn" 1} (:by-detector v)))
          (is (= 1 (:deck-warnings v)))))
      (testing "a clean report is ok"
        (spit (io/file dir "report.json")
              "{\"totals\":{\"slides\":1,\"slide_errors\":0,\"slide_warnings\":0,\"deck_errors\":0,\"deck_warnings\":0,\"error_slides\":[],\"warn_slides\":[],\"by_detector\":{}},\"slides\":[{\"n\":1,\"findings\":[]}],\"deck_findings\":[]}")
        (is (:ok? (deck/qa-report dir)))))))
