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

(defn new-handler [options]
  (let [ds (-> options :db :spec :datasource)
        xf (map #(select-keys % [:id :user_secret]))
        plan (jdbc/plan ds ["select * from user limit 10"])]
    (fn [req]
      (x/into [] xf plan))))

(defn ok-fn [user]
  {:status 200
   :body (json/write-value-as-bytes user)})

(defn error-fn [ex]
  {:status 400
   :body "Error!"})

(defmethod ig/init-key :opinionated.components.web/router [_ options]
  (let [h (new-handler options)
        pool (ex/fixed-thread-executor 4)
        wait-pool (ex/wait-pool)]
    (http/ring-handler
     (http/router
      ["/users" {:get {:handler (fn [req]
                                  (ex/with-executor pool
                                    (-> (d/future-with wait-pool (h req))
                                        (d/chain' ok-fn)
                                        (d/catch' error-fn))))}}])
     (ring/create-default-handler)
     {:executor reitit.interceptor.sieppari/executor
      :inject-match? false})))