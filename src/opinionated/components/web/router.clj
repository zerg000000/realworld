(ns opinionated.components.web.router
  (:require [integrant.core :as ig]
            [reitit.http :as http]
            [reitit.ring :as ring]
            [reitit.interceptor.sieppari]
            [muuntaja.interceptor]
            [jsonista.core :as json]
            [manifold.deferred :as d]
            [manifold.executor :as ex]
            [next.jdbc :as jdbc]
            [net.cgrand.xforms :as x]))

(defmethod ig/init-key :opinionated.components.web/router [_ {:keys [routes] :as options}]
  (http/ring-handler
    (http/router routes {:conflicts nil})
    (ring/create-default-handler)
    {:executor reitit.interceptor.sieppari/executor
     :inject-match? false}))