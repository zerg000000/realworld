(ns opinionated.components.web.donkey
  (:require
    [com.appsflyer.donkey.core :refer [create-donkey destroy]]
    [integrant.core :as ig]))

(defmethod ig/init-key :opinionated.components.web/donkey [_ options]
  (create-donkey options))

(defmethod ig/halt-key! :opinionated.components.web/donkey [_ donkey]
  (destroy donkey))