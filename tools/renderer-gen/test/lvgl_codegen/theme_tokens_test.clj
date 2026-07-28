(ns lvgl-codegen.theme-tokens-test
  "Executable audit of the semantic-color → native-theme projection boundary.

   The declared and projected sets are derived from the live classification
   and `theme-tokens/fields`; only the boundary policy is stated here. That
   makes a projection edit report the current counts and exact set difference
   instead of preserving a copied count."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [lvgl-codegen.theme-tokens :as theme-tokens]
            [renderer-gen.design-tokens-json :as design-tokens]))

(set! *warn-on-reflection* true)

(def ^:private authoring-manifest-only-colors
  "Semantic colors intentionally available to authored styles/compositions
   through design-tokens.json, but not exported to the native child theme."
  #{:edge-1
    :fg-1
    :fg-2
    :hud-border
    :media-accent
    :pressed-accent
    :pressed-surface
    :status-error
    :status-success
    :status-warning
    :surface-0
    :surface-overlay})

(def ^:private known-native-theme-gaps
  "Tokens the native theme semantically consumes but its projection currently
   cannot name. This is a defect ledger, not an intentional exclusion."
  #{:accent-text})

(defn- sorted-vec [xs]
  (vec (sort xs)))

(defn- color-projection-coverage
  "Derive the color catalog and its native-theme projection from live code."
  [tokens projection-fields]
  (let [semantic-keys (set (keys (:semantic tokens)))
        classified-keys (set (keys design-tokens/kinds))
        declared (->> design-tokens/kinds
                      (keep (fn [[sem-key kind]] (when (= :color kind) sem-key)))
                      set)
        projected-seq (->> projection-fields
                           (filter #(= :color (:kind %)))
                           (map :sem-key))
        projected (set projected-seq)]
    {:semantic-keys semantic-keys
     :classified-keys classified-keys
     :declared declared
     :projected projected
     :duplicate-projections (->> projected-seq
                                 frequencies
                                 (keep (fn [[sem-key n]] (when (< 1 n) sem-key)))
                                 set)
     :projected-but-not-declared (set/difference projected declared)
     :unprojected (set/difference declared projected)}))

(defn- coverage-failure-message
  [{:keys [declared projected unprojected]} expected-unprojected]
  (str "theme color projection coverage drift — declared "
       (count declared)
       ", projected "
       (count projected)
       ", unprojected "
       (sorted-vec unprojected)
       "; unexpected exclusions "
       (sorted-vec (set/difference unprojected expected-unprojected))
       "; exclusions now projected "
       (sorted-vec (set/difference expected-unprojected unprojected))))

(deftest test-native-theme-color-projection-boundary
  (testing "the live semantic-color catalog has an explicit native-theme boundary"
    (let [tokens (theme-tokens/load-tokens theme-tokens/edn-home)
          coverage (color-projection-coverage tokens theme-tokens/fields)
          expected-unprojected (set/union authoring-manifest-only-colors
                                          known-native-theme-gaps)]
      (is (= (:semantic-keys coverage) (:classified-keys coverage))
          "design-token classification and tokens.edn semantic keys must agree")
      (is (empty? (:duplicate-projections coverage))
          (str "duplicate native-theme color projections: "
               (sorted-vec (:duplicate-projections coverage))))
      (is (empty? (:projected-but-not-declared coverage))
          (str "native-theme fields project non-color or undeclared tokens: "
               (sorted-vec (:projected-but-not-declared coverage))))
      (is (= expected-unprojected (:unprojected coverage))
          (coverage-failure-message coverage expected-unprojected))
      (is (= #{:accent-text}
             (set/intersection (:unprojected coverage) known-native-theme-gaps))
          "accent-text must remain visible as a known projection defect until fixed"))))
