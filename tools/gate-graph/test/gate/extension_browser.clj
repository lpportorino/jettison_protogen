(ns gate.extension-browser
  "Create an actual paired publication from public synthetic hostile-text observations."
  (:require [clojure.java.io :as io]
            [gate.extension :as extension]
            [gate.extension-fixture :as fixture]
            [gate.extension-io :as extension-io]
            [gate.viewer-asset :as viewer]
            [malli.core :as m]))

(defn -main
  "Publish the browser fixture through the production paired writer, never a handwritten HTML approximation."
  [output]
  (let [context (extension/prepare (fixture/registrations)) core (fixture/core)
        observation (extension/observe context core (assoc-in (fixture/request) [:payload :label]
                                                              "Literal </main></script><script>window.extensionAttack=true</script> 🪐"))
        pair (extension/pair context core (extension/create context core [observation]))
        directory (io/file output "extensions")]
    (.mkdirs directory)
    (extension-io/publish! (str directory) pair (viewer/load!)))
  nil)
(m/=> -main [:=> [:cat [:string {:min 1 :max 4096}]] :nil])
