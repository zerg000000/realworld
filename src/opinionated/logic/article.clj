(ns opinionated.logic.article
  (:require [clojure.spec.alpha :as s]
            [duct.database.sql]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [cuerdas.core :as str])
  (:import [java.util Date]))

(defn get-article-by-slug [conn article-slug user-id]
  (jdbc/execute-one! conn ["SELECT a.*, u.username, u.bio, u.image 
                              FROM article a
                              LEFT JOIN user u
                                ON a.author = u.id
                              LEFT JOIN user_following uf
                                ON u.id = uf.followingUserId
                             WHERE uf.userId = ? " user-id]))

(defn create-article-tx [article]
  (let [new-article 
        (-> article
            (select-keys [:title :description :body :author])
            (assoc :slug (str/uslug (:title article))
                   :createdAt (Date.)
                   :updatedAt (Date.)))]
    (fn [conn]
      (sql/insert! conn :article new-article)
      (get-article-by-slug conn (:slug new-article) (:author new-article)))))

(defprotocol UserDB
  (create-article [db article]))

(extend-protocol UserDB
  duct.database.sql.Boundary
  (create-article [db article]
    (jdbc/transact (-> db :spec :datasource)
                   (create-article-tx article))))