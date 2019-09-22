(ns opinionated.components.web.routes
  (:require [integrant.core :as ig]
            [jsonista.core :as json]
            [manifold.deferred :as d]
            [manifold.executor :as ex]
            [clojure.spec.alpha :as s]
            [buddy.auth.middleware :refer [authentication-request]]
            [buddy.auth :refer [authenticated?]] 
            [opinionated.logic.user :as user]))

(defn ok-fn [user]
  {:status 200
   :body (json/write-value-as-bytes user)})

(def status-ok
  {:handler (fn status-ok [req]
              {:status 200
               :body "OK"})})

(defn wrap-auth [h & backends]
  (fn auth [req]
    (d/chain' req
              #(apply authentication-request % backends)
              h)))

(defn wrap-protected [h]
  (fn protected [req]
    (d/chain' req
              (fn [r]
                (if-not (authenticated? r)
                  (d/error-deferred (ex-info "Unauthorized" {}))
                  (h r))))))

(defn wrap-json-format [h pool]
  (let [m (json/object-mapper {:decode-key-fn true})]
    (fn json-format [req]
      (d/chain' (d/future-with pool (json/read-value (:body req) m))
                h))))

(defn wrap-spec-validate [h spec]
  (fn spec-validate [ent]
    (if (s/valid? spec ent)
      (h ent)
      (d/error-deferred (ex-info "Validation Failed" (s/explain-data spec ent))))))

(defn wrap-ok-response [h]
  (fn ok-response [req]
    (d/chain' (h req) ok-fn)))

(defn wrap-error-response [h]
  (fn error-response [req]
    (d/catch' (h req)
              (fn error-handler [error]
                {:status 400
                 :body (.getMessage error)}))))

(defmethod ig/init-key :opinionated.components.web/routes [_ {:keys [db executor jwt] :as options}]
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
   ["/user" {:get (-> #(d/future-with executor (user/get-user db jwt (-> % :identity :user)))
                      (wrap-protected)
                      (wrap-auth (:backend jwt))
                      (wrap-ok-response)
                      (wrap-error-response))
             :put status-ok}]
   ["/users"
    ["" {:post (-> #(d/future-with (ex/wait-pool) (user/register db jwt %))
                   (wrap-spec-validate :opinionated.logic.user/register)
                   (wrap-json-format executor)
                   (wrap-ok-response)
                   (wrap-error-response))}]
    ["/login" {:post (-> #(d/future-with executor (user/login db jwt %))
                         (wrap-spec-validate :opinionated.logic.user/login)
                         (wrap-json-format executor)
                         (wrap-ok-response)
                         (wrap-error-response))}]]])