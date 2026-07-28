(ns lvgl-codegen.theme-style-groups-test
  "Schema/non-vacuity checks plus freshness for the compiled theme.c
   style-group projection."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]))

(set! *warn-on-reflection* true)

(def ^:private manifest-path "generated/theme-style-groups.json")

(defn- read-manifest []
  (with-open [reader (io/reader manifest-path)]
    (json/read reader)))

(deftest compiled-theme-projection-is-fresh
  (testing "the committed manifest equals a fresh compiled execution of theme.c"
    (let [{:keys [exit out err]}
          (shell/sh "bash"
                    "tools/theme-style-groups/generate.sh"
                    "--check"
                    manifest-path)]
      (is (zero? exit) (str out err)))))

(deftest theme-style-group-manifest-is-closed-and-non-vacuous
  (let [manifest (read-manifest)
        groups (get manifest "style-groups")
        group-names (set (map #(get % "name") groups))
        profile-ids (set (map #(get % "id") (get manifest "display-profiles")))
        expected-profiles #{"small" "medium" "large"}]
    (testing "the projection names its method and its deliberately narrow scope"
      (is (= 1 (get manifest "schema-version")))
      (is (= "lvgl-child-theme-style-groups" (get manifest "kind")))
      (is (= "compiled-execution" (get manifest "source-method")))
      (is (= #{"explicit child-theme groups and selectors"
               "resolved group-local properties"}
             (set (get manifest "scope"))))
      (is (= #{"stock parent theme cascade"
               "per-node AST style_groups"
               "effective inherited/composited draw colors"}
             (set (get manifest "excluded")))))
    (testing "all three display-size branches and both active child families exist"
      (is (= expected-profiles profile-ids))
      (is (= "no-op"
             (get-in manifest ["theme-families" "stock" "child-theme"]))))
    (testing "the live theme groups are present, including opacity/recolor canaries"
      (is (<= 30 (count groups)))
      (is (set/subset? #{"panel"
                         "disabled"
                         "disabled_dim"
                         "disabled_fill"
                         "disabled_flat"
                         "hover"
                         "pressed"
                         "trans"}
                       group-names)))
    (testing "every emitted group is applied and fully enumerates its active families"
      (doseq [group groups
              :let [group-name (get group "name")
                    applications (get group "applications")
                    variants (get group "variants")
                    families (set (map #(get % "family") applications))
                    actual-combinations
                    (set (map (juxt #(get % "family")
                                    #(get % "mode")
                                    #(get % "display-profile"))
                              variants))
                    expected-combinations
                    (set (for [family families
                               mode ["light" "dark"]
                               profile expected-profiles]
                           [family mode profile]))]]
        (is (seq applications) (str group-name " has no applications"))
        (is (= expected-combinations actual-combinations)
            (str group-name " variant coverage drift"))
        (doseq [application applications]
          (is (integer? (get application "selector"))
              (str group-name " selector is not numeric"))
          (is (seq (get application "states"))
              (str group-name " selector has no decoded state"))
          (is (string? (get application "part"))
              (str group-name " selector has no decoded part")))
        (doseq [variant variants
                [_ property] (get variant "properties")]
          (is (#{"int" "color" "transition"} (get property "type"))
              (str group-name " has an unknown property encoding")))))
    (testing "the universal scrollbar and text-opacity hazard split are concrete"
      (is (some #(and (= "scrollbar" (get % "name"))
                      (some (fn [application]
                              (and (= "*" (get application "target"))
                                   (= "scrollbar" (get application "part"))))
                            (get % "applications")))
                groups))
      (let [asgard-dark-medium
            (fn [group-name]
              (some (fn [variant]
                      (when (and (= "asgard" (get variant "family"))
                                 (= "dark" (get variant "mode"))
                                 (= "medium" (get variant "display-profile")))
                        (get variant "properties")))
                    (get (first (filter #(= group-name (get % "name")) groups))
                         "variants")))]
        (is (= 128
               (get-in (asgard-dark-medium "disabled_dim")
                       ["opa" "value"])))
        (is (nil? (get (asgard-dark-medium "disabled") "opa")))
        (is (= 0
               (get-in (asgard-dark-medium "disabled")
                       ["recolor-opa" "value"])))))))
