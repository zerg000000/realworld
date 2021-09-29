(ns opinionated.components.web.server
  (:require [com.appsflyer.donkey.core :refer [create-server]]
            [com.appsflyer.donkey.server :refer [start stop]]
            [duct.logger :as logger]
            [integrant.core :as ig]))

(defmethod ig/init-key :opinionated.components.web/server [_ {:keys [logger donkey] :as options}]
  (logger/log logger :report ::start-server)
  {:logger logger
   :server (-> (create-server donkey (dissoc options :logger :donkey))
               (start))})

(defmethod ig/halt-key! :opinionated.components.web/server [_ {:keys [logger server]}]
  (logger/log logger :report ::stopping-server)
  (stop server))