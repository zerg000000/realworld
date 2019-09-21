(ns opinionated.components.web.server
  (:require [aleph.http :as aleph]
            [duct.logger :as logger]
            [integrant.core :as ig]))

(defmethod ig/init-key :opinionated.components.web/server [_ {:keys [logger handler] :as options}]
  {:logger logger
   :server (aleph/start-server handler (dissoc options :handler :logger))})

(defmethod ig/halt-key! :opinionated.components.web/server [_ {:keys [logger server]}]
  (logger/log logger :report ::stopping-server)
  (.close ^java.io.Closeable server))