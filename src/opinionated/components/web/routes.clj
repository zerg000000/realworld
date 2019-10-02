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

(defn ok-fn [body mapper]
  {:status 200
   :body (json/write-value-as-bytes body mapper)})

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
      (d/error-deferred (ex-info "Invalid Form Input" (s/explain-data spec ent))))))

(defn wrap-ok-response [h pool]
  (let [mapper (json/object-mapper {:date-format "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"})]
    (fn ok-response [req]
      (d/chain' (h req) #(d/future-with pool (ok-fn % mapper))))))

(defn wrap-error-response [h pool]
  (let [mapper (json/object-mapper {:date-format "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"})]
    (fn error-response [req]
      (d/catch' (h req)
                (fn error-handler [error]
                  (d/future-with pool
                    {:status 400
                     :body
                     (json/write-value-as-bytes
                       {:errors {:body [(.getMessage error)]}}
                       mapper)}))))))

(defmacro run [context bindings destructure-pattern & body]
  `(let [pool# (:wait-pool ~context)
         {:keys [~@bindings]} ~context]
     (fn [req#]
       (d/future-with pool#
                      (let [~destructure-pattern req#]
                        (do ~@body))))))

(defmethod ig/init-key :opinionated.components.web/routes [_ {:keys [execute-pool jwt] :as ctx}]
  (let [protected-middlware #(-> %
                                 (wrap-protected)
                                 (wrap-auth (:backend jwt))
                                 (wrap-ok-response execute-pool)
                                 (wrap-error-response execute-pool))
        optional-mw #(-> %
                         (wrap-auth (:backend jwt))
                         (wrap-ok-response execute-pool)
                         (wrap-error-response execute-pool))
        base-mw #(-> %
                     (wrap-ok-response execute-pool)
                     (wrap-error-response execute-pool))]
    ["/api"
     ["/articles"
      ["" {:get (-> (run ctx [db] {{user :user} :identity
                                   args         :query-params}
                          (article/get-articles db user args))
                     (wrap-params)
                     optional-mw)
           :post (-> (run ctx [db] {{article :article} :body
                                    {user :user} :identity}
                       (article/create-article db (assoc article :author user)))
                     (wrap-json-format-req execute-pool)
                     protected-middlware)}]
      ["/feed" {:get (-> (run ctx [db] {{user :user} :identity
                                        args         :query-params}
                          (article/get-feed db user args))
                         (wrap-params)
                         protected-middlware)}]
      ["/:slug"
       ["" {:get (-> (run ctx [db] {{user :user} :identity
                                    {slug :slug} :path-params}
                       (article/get-by-slug db slug user))
                     optional-mw)
            :put (-> (run ctx [db] {{user :user} :identity
                                    {slug :slug} :path-params
                                    {article :article} :body}
                      (article/update-article db (assoc article
                                                        :slug slug
                                                        :author user)))
                     (wrap-json-format-req execute-pool)
                     protected-middlware)
            :delete (-> (run ctx [db] {{user :user} :identity
                                       {slug :slug} :path-params}
                          (article/delete-article db slug user))
                        protected-middlware)}]
       ["/comments"
        ["" {:get (-> (run ctx [db] {{user :user}       :identity
                                     {slug :slug}       :path-params}
                           (comment/get-comment db slug user))
                      (wrap-json-format-req execute-pool)
                      optional-mw)
             :post (-> (run ctx [db] {{user :user}       :identity
                                      {slug :slug}       :path-params
                                      {comment :comment} :body}
                            (comment/add-comment db slug comment user))
                       (wrap-json-format-req execute-pool)
                       protected-middlware)}]
        ["/:id" {:delete (-> (run ctx [db] {{user :user}       :identity
                                            {comment-id :id}   :path-params}
                                (comment/delete-comment db comment-id user))
                             protected-middlware)}]]
       ["/favorite" {:post (-> (run ctx [db] {{user :user} :identity
                                              {slug :slug} :path-params}
                                 (article/favorite db slug user))
                               protected-middlware)
                     :delete (-> (run ctx [db] {{user :user} :identity
                                                {slug :slug} :path-params}
                                  (article/unfavorite db slug user))
                                 protected-middlware)}]]]
     ["/tags" {:get (-> (run ctx [db] _
                          (article/get-tags db))
                        base-mw)}]
     ["/profiles/:username"
      ["" {:get (-> (run ctx [db] {{user :user} :identity
                                   {username :username} :path-params}
                      (user/get-user-by-name db username user))
                    optional-mw)}]
      ["/follow" {:post (-> (run ctx [db] {{user :user} :identity
                                           {username :username} :path-params}
                              (user/follow db user username))
                            protected-middlware)
                  :delete (-> (run ctx [db] {{user :user} :identity
                                             {username :username} :path-params}
                                (user/unfollow db user username))
                              protected-middlware)}]]
     ["/user" {:get (-> (run ctx [db jwt] {{user :user} :identity}
                          (user/get-user db jwt user))
                        protected-middlware)
               :put (-> (run ctx [db jwt] {{user :user}      :identity
                                           {user-info :user} :body}
                          (user/update-user db jwt (assoc user-info
                                                          :id user)))
                        (wrap-json-format-req execute-pool)
                        protected-middlware)}]
     ["/users"
      ["" {:post (-> (run ctx [db jwt] {:keys [user]} 
                        (user/register db jwt user))
                     (wrap-spec-validate :opinionated.logic.user/register-form)
                     (wrap-json-format execute-pool)
                     base-mw)}]
      ["/login" {:post (-> (run ctx [db jwt] {:keys [user]} 
                                (user/login db jwt user))
                           (wrap-spec-validate :opinionated.logic.user/login-form)
                           (wrap-json-format execute-pool)
                           base-mw)}]]]))