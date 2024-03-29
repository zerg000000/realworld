(ns opinionated.components.web.routes
  (:require [integrant.core :as ig]
            [clojure.spec.alpha :as s]
            [promesa.core :as p]
            [buddy.auth :refer [authenticated?]]
            [opinionated.logic.user :as user]
            [opinionated.logic.article :as article]
            [opinionated.logic.comment :as comment]))

(def protected-interceptor
  {:enter (fn [ctx]
            (if-not (authenticated? (:request ctx))
              (assoc ctx :queue [] :response {:status 401 :body "Unauthorized"})
              ctx))})

(defn ok-response [body]
  {:status 200
   :body body})

(defn error-response [error]
  {:status 400
   :body {:errors {:body [(.getMessage error)]}}})

(defn not-exists-in-map [context names]
  (->> names
       (map name)
       (map keyword)
       (filterv (fn [n]
                  (nil? (n context))))
       seq))

(defmacro run
  "A helper that help make things a bit more concise. 
   1. Wrap the handler into p/future
   2. Pick related services from context
   3. Add normal/exception response handling"
  [context bindings destructure-pattern & body]
  `(do (when-let [missed-bindings# (not-exists-in-map ~context '~bindings)]
         (throw (ex-info (str "Missing binding " missed-bindings#) {})))
       (let [{:keys [~@bindings]} ~context]
         (fn [req#]
           (-> (p/future (let [~destructure-pattern req#]
                           (do ~@body)))
               (p/chain' ok-response)
               (p/catch' error-response))))))

(defmethod ig/init-key :opinionated.components.web/routes [_ {:keys [jwt] :as ctx}]
  ["/api"
   ["/articles"
    ["" {:get (run ctx [db] {{user :user} :identity
                             args         :query-params}
                   (article/get-articles db user args))
         :post {:handler (run ctx [db] {{article :article} :body-params
                                        {user :user} :identity}
                              (article/create-article db (assoc article :author user)))
                :interceptors [protected-interceptor]}}]
    ["/feed" {:get {:handler (run ctx [db] {{user :user} :identity
                                            args         :query-params}
                                  (article/get-feed db user args))
                    :interceptors [protected-interceptor]}}]
    ["/:slug"
     ["" {:get (run ctx [db] {{user :user} :identity
                              {slug :slug} :path-params}
                    (article/get-by-slug db slug user))
          :put {:handler (run ctx [db] {{user :user} :identity
                                        {slug :slug} :path-params
                                        {article :article} :body-params}
                              (article/update-article db (assoc article
                                                                :slug slug
                                                                :author user)))
                :interceptors [protected-interceptor]}
          :delete {:handler (run ctx [db] {{user :user} :identity
                                           {slug :slug} :path-params}
                                 (article/delete-article db slug user))
                   :interceptors [protected-interceptor]}}]
     ["/comments"
      ["" {:get (run ctx [db] {{user :user}       :identity
                               {slug :slug}       :path-params}
                     (comment/get-comment db slug user))
           :post {:handler (run ctx [db] {{user :user}       :identity
                                          {slug :slug}       :path-params
                                          {comment :comment} :body-params}
                                (comment/add-comment db slug comment user))
                  :interceptors [protected-interceptor]}}]
      ["/:id" {:delete {:handler (run ctx [db] {{user :user}       :identity
                                                {comment-id :id}   :path-params}
                                      (comment/delete-comment db comment-id user))
                        :interceptors [protected-interceptor]}}]]
     ["/favorite" {:post {:handler (run ctx [db] {{user :user} :identity
                                                  {slug :slug} :path-params}
                                        (article/favorite db slug user))
                          :interceptors [protected-interceptor]}
                   :delete {:handler (run ctx [db] {{user :user} :identity
                                                    {slug :slug} :path-params}
                                          (article/unfavorite db slug user))
                            :interceptors [protected-interceptor]}}]]]
   ["/tags" {:get (run ctx [db] _
                       (article/get-tags db))}]
   ["/profiles/:username"
    ["" {:get (run ctx [db] {{user :user} :identity
                             {username :username} :path-params}
                   (user/get-user-by-name db username user))}]
    ["/follow" {:post {:handler (run ctx [db] {{user :user} :identity
                                               {username :username} :path-params}
                                     (user/follow db user username))
                       :interceptors [protected-interceptor]}
                :delete {:handler (run ctx [db] {{user :user} :identity
                                                 {username :username} :path-params}
                                       (user/unfollow db user username))
                         :interceptors [protected-interceptor]}}]]
   ["/user" {:get {:handler (run ctx [db jwt] {{user :user} :identity}
                                 (user/get-user db jwt user))
                   :interceptors [protected-interceptor]}
             :put {:handler (run ctx [db jwt] {{user :user}      :identity
                                               {user-info :user} :body-params}
                                 (user/update-user db jwt (assoc user-info
                                                                 :id user)))
                   :interceptors [protected-interceptor]}}]
   ["/users"
    ["" {:post (run ctx [db jwt] {{user :user} :body-params}
                    (user/register db jwt user))}]
    ["/login" {:post (run ctx [db jwt] {{user :user} :body-params}
                          (user/login db jwt user))}]]])