(ns opinionated.components.web.routes
  (:require [integrant.core :as ig]
            [jsonista.core :as json]
            [manifold.deferred :as d]
            [manifold.executor :as ex]
            [clojure.spec.alpha :as s]
            [ring.middleware.params :as params]
            [clojure.walk :as w]
            [buddy.auth.middleware :refer [authentication-request]]
            [buddy.auth :refer [authenticated?]] 
            [opinionated.logic.user :as user]
            [opinionated.logic.article :as article]))

(defn ok-fn [user]
  {:status 200
   :body (json/write-value-as-bytes user)})

(def status-ok
  {:handler (fn status-ok [req]
              {:status 200
               :body "OK"})})

(defn params-keyword [req]
  (update req :query-params w/keywordize-keys))

(defn wrap-params [h]
  (fn params [req]
    (d/chain' req
              params/params-request
              params-keyword
              h)))

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

(defn wrap-json-format-req [h pool]
  (let [m (json/object-mapper {:decode-key-fn true})]
    (fn json-format [req]
      (d/chain' (d/future-with pool (update req :body json/read-value m))
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
  (let [protected-middlware #(-> %
                                 (wrap-protected)
                                 (wrap-auth (:backend jwt))
                                 (wrap-ok-response)
                                 (wrap-error-response))]
    ["/api"
     ["/articles"
      ["" {:get (-> #(d/future-with executor
                                    (article/get-articles db
                                                          (-> % :identity :user)
                                                          (-> % :query-params)))
                    (wrap-params)
                    protected-middlware)
           :post (-> #(d/future-with executor
                                     (article/create-article db (assoc (-> % :body :article) 
                                                                       :author (-> % :identity :user))))
                     (wrap-json-format-req executor)
                     protected-middlware)}]
      ["/feed" {:get (-> #(d/future-with executor
                                         (article/get-feed db 
                                                           (-> % :identity :user) 
                                                           (-> % :query-params)))
                         (wrap-params)
                         protected-middlware)}]
      ["/:slug"
       ["" {:get (-> #(d/future-with executor
                                     (article/get-by-slug db
                                        (-> % :path-params :slug)
                                        (-> % :identity :user)))
                     (wrap-ok-response)
                     (wrap-error-response))
            :put (-> #(d/future-with executor
                                     (article/update-article db (assoc (-> % :body :article)
                                                                       :slug (-> % :path-params :slug)
                                                                       :author (-> % :identity :user))))
                     (wrap-json-format-req executor)
                     protected-middlware)
            :delete status-ok}]
       ["/comments" {:get status-ok
                     :post status-ok}
        ["/:id" {:delete status-ok}]]
       ["/favorite" {:post (-> (fn [req]
                                 (d/future-with executor
                                                (article/favorite db
                                                                  (-> req :path-params :slug) 
                                                                  (-> req :identity :user))))
                               protected-middlware)
                     :delete (-> (fn [req]
                                   (d/future-with executor
                                                  (article/unfavorite db
                                                                      (-> req :path-params :slug)
                                                                      (-> req :identity :user))))
                                 protected-middlware)}]]]
     ["/tags" {:get (-> (fn [req]
                          (d/future-with executor (article/get-tags db)))
                        (wrap-ok-response)
                        (wrap-error-response))}]
     ["/profiles/:username"
      ["" {:get (-> #(d/future-with executor
                                    (user/get-user-by-name db
                                                           (-> % :path-params :username)
                                                           (-> % :identity :user)))
                    protected-middlware)}]
      ["/follow" {:post (-> (fn [req]
                              (d/future-with executor
                                             (user/follow db (-> req :identity :user)
                                                             (-> req :path-params :username))))
                            protected-middlware)
                  :delete (-> (fn [req]
                                (d/future-with executor
                                               (user/unfollow db (-> req :identity :user)
                                                                 (-> req :path-params :username))))
                              protected-middlware)}]]
     ["/user" {:get (-> #(d/future-with executor
                                        (user/get-user db jwt (-> % :identity :user)))
                        protected-middlware)
               :put (-> (fn [req]
                          (d/future-with executor
                                         (user/update-user db jwt
                                                           (assoc (:body req)
                                                                  :id (-> req :identity :user)))))
                        (wrap-json-format-req executor)
                        protected-middlware)}]
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
                           (wrap-error-response))}]]]))