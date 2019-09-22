(ns opinionated.components.auth.backend
  (:require [integrant.core :as ig]
            [buddy.auth.backends :as backends]))

(defmethod ig/init-key :opinionated.components.auth/backend [_ options]
  (assoc options :backend (backends/jws {:secret (:secret options)})))