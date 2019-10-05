(ns opinionated.components.web.router
  (:require [integrant.core :as ig]
            [reitit.http :as http]
            [reitit.ring :as ring]
            [buddy.auth.middleware :refer [authentication-request]]
            [reitit.interceptor.sieppari]
            [reitit.http.interceptors.parameters :as parameters]
            [jsonista.core :as json]
            [muuntaja.core :as m]
            [muuntaja.interceptor]))

(defmethod ig/init-key :opinionated.components.web/router [_ {:keys [routes jwt] :as options}]
  (http/ring-handler
    (http/router routes {:conflicts nil})
    (ring/create-default-handler)
    {:executor reitit.interceptor.sieppari/executor
     :interceptors [(parameters/parameters-interceptor)
                    (muuntaja.interceptor/format-interceptor (assoc-in
                                                               m/default-options
                                                              [:formats "application/json" :encoder-opts]
                                                              {:date-format "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
                                                               :decode-key-fn true}))
                    {:enter (fn [context]
                              (update context :request authentication-request (:backend jwt)))}]
     :inject-match? false
     :inject-router? false}))