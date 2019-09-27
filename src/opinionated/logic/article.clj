(ns opinionated.logic.article
  (:require [clojure.spec.alpha :as s]
            [duct.database.sql]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [cuerdas.core :as str])
  (:import [java.util Date]))

(defn get-article-by-slug [conn article-slug user-id]
  (let [raw (jdbc/execute-one! conn (if user-id
                                      ["SELECT a.*, u.username, u.bio, u.image, uf.followingUserId as following 
                              FROM article a
                              LEFT JOIN user u
                                ON a.author = u.id
                              LEFT JOIN user_following uf
                                ON u.id = uf.followingUserId
                               AND uf.userId = ? 
                             WHERE a.slug = ? " user-id article-slug]
                                      ["SELECT a.*, u.username, u.bio, u.image, null as following 
                              FROM article a
                              LEFT JOIN user u
                                ON a.author = u.id 
                             WHERE a.slug = ? " article-slug])
                               {:builder-fn rs/as-unqualified-maps})]
    (-> (select-keys raw [:slug :title :description :body :createdAt :updatedAt])
        (assoc :author (select-keys raw [:username :bio :image :following]))
        (update-in [:author :following] boolean)
        (update :createdAt #(Date. %))
        (update :updatedAt #(Date. %)))
    ))

(defn create-article-tx [article]
  (let [new-article 
        (-> article
            (select-keys [:title :description :body :author])
            (assoc :slug (str/uslug (:title article))
                   :createdAt (Date.)
                   :updatedAt (Date.)))]
    (fn [conn]
      (let [id (-> (sql/insert! conn :article new-article) vals first)]
        (when-let [tags (seq (:tagList article))]
          (sql/insert-multi! conn :article_tag [:articleId :tag] (for [tag tags]
                                                                   [id tag])))
        (get-article-by-slug conn (:slug new-article) (:author new-article))))))

(defn get-all-tags [conn]
  {:tags (->> (jdbc/execute! conn ["SELECT DISTINCT tag FROM article_tag"] {:builder-fn rs/as-unqualified-maps})
              (map :tag))})

(defprotocol ArticleDB
  (create-article [db article])
  (get-by-slug [db slug user-id])
  (get-tags [db]))

(extend-protocol ArticleDB
  duct.database.sql.Boundary
  (create-article [db article]
    (jdbc/transact (-> db :spec :datasource)
                   (create-article-tx article)))
  (get-by-slug [db slug user-id]
    (get-article-by-slug (-> db :spec :datasource)
                         slug user-id))
  (get-tags [db]
    (get-all-tags (-> db :spec :datasource))))