(ns opinionated.components.web.routes
  (:require [integrant.core :as ig]
            [jsonista.core :as json]
            [manifold.deferred :as d]
            [manifold.executor :as ex]
            [opinionated.logic.user :as user]))

(defn ok-fn [user]
  {:status 200
   :body (json/write-value-as-bytes user)})

(def status-ok
  {:handler (fn status-ok [req]
              {:status 200
               :body "OK"})})

(defn wrap-json-format [h pool]
  (let [m (json/object-mapper {:decode-key-fn true})]
    (fn [req]
      (d/chain' (d/future-with pool (json/read-value (:body req) m))
                h))))

(defn wrap-ok-response [h]
  (fn [req]
    (d/chain' (h req) ok-fn)))

(defn wrap-error-response [h]
  (fn [req]
    (d/catch' (h req)
              (fn error-handler [error]
                (prn error)
                {:status 400
                 :body "Error!"}))))

(defmethod ig/init-key :opinionated.components.web/routes [_ {:keys [db executor] :as options}]
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
   ["/user" {:get status-ok
             :put status-ok}]
   ["/users"
    ["" {:post (-> #(d/future-with (ex/wait-pool) (user/register db %))
                   (wrap-json-format executor)
                   (wrap-ok-response)
                   (wrap-error-response))}]
    ["/login" {:post status-ok}]]])