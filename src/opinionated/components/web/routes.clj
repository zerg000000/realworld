(ns opinionated.components.web.routes
  (:require [integrant.core :as ig]
            [jsonista.core :as json]
            [manifold.deferred :as d]
            [manifold.executor :as ex]
            [next.jdbc :as jdbc]
            [net.cgrand.xforms :as x]))

(defn ok-fn [user]
  {:status 200
   :body (json/write-value-as-bytes user)})

(defn error-fn [ex]
  {:status 400
   :body "Error!"})

(defn new-handler [options]
  (let [ds (-> options :db :spec :datasource)
        pool (-> options :executor)
        xf (map #(select-keys % [:id :user_secret]))
        plan (jdbc/plan ds ["select * from user limit 10"])]
    (fn [req]
      (ex/with-executor pool
        (-> (d/future-with (ex/wait-pool) (x/into [] xf plan))
            (d/chain' ok-fn)
            (d/catch' error-fn))))))

(def status-ok
  {:handler (fn status-ok [req]
              {:status 200
               :body "OK"})})

(defmethod ig/init-key :opinionated.components.web/routes [_ options]
  ["/api"
   ["/articles" 
    ["" {:get status-ok
         :post status-ok}]
    ["/feed" {:get status-ok}]
    ["/:slug" 
     ["" {:get status-ok
          :put status-ok
          :delete status-ok}]
     ["/comments" {:get status-ok
                   :post status-ok}
      ["/:id" {:delete status-ok}]]
     ["/favorite" {:post status-ok
                   :delete status-ok}]]]
   ["/tags" {:get status-ok}]
   ["/profiles/:username" 
    ["" {:get status-ok}]
    ["/follow" {:post status-ok
                :delete status-ok}]]
   ["/user" {:get {:handler (new-handler options)}
             :put status-ok}]
   ["/users"
    ["" {:post status-ok}]
    ["/login" {:post status-ok}]]])