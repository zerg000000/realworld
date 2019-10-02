(ns opinionated.main
  (:gen-class)
  (:require [clojure.java.io :as io]
            [duct.core :as duct]))

(duct/load-hierarchy)

(defn -main [& args]
  (let [keys (or (duct/parse-keys args) [:duct/daemon])
        profiles [:duct.profile/prod]]
    (-> (duct/resource "app/config.edn")
        (duct/read-config)
        (duct/exec-config profiles keys))))