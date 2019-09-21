(ns opinionated.components.executors
  (:require [integrant.core :as ig]
            [manifold.executor :as ex]))

(defmethod ig/init-key :opinionated.components.executors/execute [_ options]
  (ex/fixed-thread-executor 4 options))

(defmethod ig/halt-key! :opinionated.components.executors/execute [_ executor]
  (.shutdown executor))