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
            [opinionated.logic.article :as article]
            [opinionated.logic.comment :as comment]))

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
                (.printStackTrace error)
                {:status 400
                 :body (.getMessage error)}))))

(defmacro run [context bindings destructure-pattern & body]
  `(do
     (fn [req#]
       (d/future-with (:executor ~context)
                      (let [~destructure-pattern req#
                            {:keys [~@bindings]} ~context]
                        (do ~@body))))))

(defmethod ig/init-key :opinionated.components.web/routes [_ {:keys [executor jwt] :as options}]
  (let [protected-middlware #(-> %
                                 (wrap-protected)
                                 (wrap-auth (:backend jwt))
                                 (wrap-ok-response)
                                 (wrap-error-response))
        base-mw #(-> %
                     (wrap-ok-response)
                     (wrap-error-response))]
    ["/api"
     ["/articles"
      ["" {:get (-> (run options [db] {{user :user} :identity
                                        args         :query-params}
                          (article/get-articles db user args))
                     (wrap-params)
                     protected-middlware)
           :post (-> (run options [db] {{article :article} :body
                                        {user :user} :identity}
                       (article/create-article db (assoc article :author user)))
                     (wrap-json-format-req executor)
                     protected-middlware)}]
      ["/feed" {:get (-> (run options [db] {{user :user} :identity
                                            args         :query-params}
                          (article/get-feed db user args))
                         (wrap-params)
                         protected-middlware)}]
      ["/:slug"
       ["" {:get (-> (run options [db] {{user :user} :identity
                                        {slug :slug} :path-params}
                       (article/get-by-slug db slug user))
                     base-mw)
            :put (-> (run options [db] {{user :user} :identity
                                        {slug :slug} :path-params
                                        {article :article} :body}
                      (article/update-article db (assoc article
                                                        :slug slug
                                                        :author user)))
                     (wrap-json-format-req executor)
                     protected-middlware)
            :delete (-> (run options [db] {{user :user} :identity
                                           {slug :slug} :path-params}
                          (article/delete-article db slug user))
                        protected-middlware)}]
       ["/comments"
        ["" {:get (-> (run options [db] {{user :user}       :identity
                                         {slug :slug}       :path-params}
                           (comment/get-comment db slug user))
                      (wrap-json-format-req executor)
                      protected-middlware)
             :post (-> (run options [db] {{user :user}       :identity
                                          {slug :slug}       :path-params
                                          {comment :comment} :body}
                            (comment/add-comment db slug comment user))
                       (wrap-json-format-req executor)
                       protected-middlware)}]
        ["/:id" {:delete (-> (run options [db] {{user :user}       :identity
                                                {comment-id :id}   :path-params}
                                (comment/delete-comment db comment-id user))
                             protected-middlware)}]]
       ["/favorite" {:post (-> (run options [db] {{user :user} :identity
                                                  {slug :slug} :path-params}
                                 (article/favorite db slug user))
                               protected-middlware)
                     :delete (-> (run options [db] {{user :user} :identity
                                                    {slug :slug} :path-params}
                                  (article/unfavorite db slug user))
                                 protected-middlware)}]]]
     ["/tags" {:get (-> (run options [db] _
                          (article/get-tags db))
                        base-mw)}]
     ["/profiles/:username"
      ["" {:get (-> (run options [db] {{user :user} :identity
                                       {username :username} :path-params}
                      (user/get-user-by-name db username user))
                    protected-middlware)}]
      ["/follow" {:post (-> (run options [db] {{user :user} :identity
                                               {username :username} :path-params}
                              (user/follow db user username))
                            protected-middlware)
                  :delete (-> (run options [db] {{user :user} :identity
                                                 {username :username} :path-params}
                                (user/unfollow db user username))
                              protected-middlware)}]]
     ["/user" {:get (-> (run options [db jwt] {{user :user} :identity}
                          (user/get-user db jwt user))
                        protected-middlware)
               :put (-> (run options [db jwt] {{user :user} :identity
                                               user-info    :body}
                          (user/update-user db jwt (assoc user-info
                                                          :id user)))
                        (wrap-json-format-req executor)
                        protected-middlware)}]
     ["/users"
      ["" {:post (-> (run options [db jwt] reg-form 
                        (user/register db jwt reg-form))
                     (wrap-spec-validate :opinionated.logic.user/register)
                     (wrap-json-format executor)
                     base-mw)}]
      ["/login" {:post (-> (run options [db jwt] login-form 
                                (user/login db jwt login-form))
                           (wrap-spec-validate :opinionated.logic.user/login)
                           (wrap-json-format executor)
                           base-mw)}]]]))