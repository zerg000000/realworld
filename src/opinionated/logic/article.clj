(ns opinionated.logic.article
  (:require [clojure.spec.alpha :as s]
            [duct.database.sql]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [cuerdas.core :as str]
            [honeysql.core :as h]
            [honeysql.helpers :as hs])
  (:import [java.util Date]))

(def article-base
  {:select [:a.*
            :author.username :author.bio :author.image]
    :from [[:article :a]]
    :left-join [[:user :author] [:= :a.author :author.id]]})

(defn with-user [query user-id]
  (-> query
      (hs/merge-left-join [:user_following :uf] [:and [:= :author.id :uf.followingUserId] [:= :uf.userId user-id]]
                          [:article_favorite :af] [:and [:= :a.id :af.articleId] [:= :af.userId user-id]])
      (hs/merge-select [:uf.followingUserId :following]
                       [:af.userId :favorited])))

(defn without-user [query]
  (-> query
      (hs/merge-select [:false :following] [:false :favorited])))

(defn get-article-by-slug [conn article-slug user-id]
  (let [raw (jdbc/execute-one! conn (-> (if user-id
                                          (with-user article-base user-id)
                                          (without-user article-base))
                                        (hs/where [:= :a.slug article-slug])
                                        (h/format))
                               {:builder-fn rs/as-unqualified-maps})]
    (-> (select-keys raw [:slug :title :description :body :createdAt :updatedAt :favoritesCount :favorited])
        (assoc :author (select-keys raw [:username :bio :image :following]))
        (update-in [:author :following] boolean)
        (update :favorited boolean)
        (update :createdAt #(Date. %))
        (update :updatedAt #(Date. %)))))

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

(defn update-article-tx [article]
  (let [existing (select-keys article [:slug :author])
        update-article
        (-> article
            (select-keys [:title :description :body])
            (assoc :slug (str/uslug (:title article))
                   :updatedAt (Date.)))]
    (fn [conn]
      (sql/update! conn :article update-article existing)
      (get-article-by-slug conn (:slug update-article) (:author existing)))))

(defn get-all-tags [conn]
  {:tags (->> (jdbc/execute! conn ["SELECT DISTINCT tag FROM article_tag"] {:builder-fn rs/as-unqualified-maps})
              (map :tag))})

(defn favorite-tx [article-slug user-id]
  (fn [conn]
    (jdbc/execute-one! conn ["INSERT INTO article_favorite 
                          (userId, articleId) 
                          VALUES (?, (SELECT id FROM article WHERE slug = ? LIMIT 1))"
                         user-id article-slug])
    (jdbc/execute-one! conn ["UPDATE article SET favoritesCount = favoritesCount + 1 WHERE slug = ?" article-slug])
    (get-article-by-slug conn article-slug user-id)))

(defn unfavorite-tx [article-slug user-id]
  (fn [conn]
    (jdbc/execute! conn ["DELETE FROM article_favorite 
                           WHERE userId = ? 
                             AND articleId in (SELECT id FROM article WHERE slug = ?)"
                         user-id article-slug])
    (jdbc/execute-one! conn ["UPDATE article SET favoritesCount = favoritesCount - 1 
                               WHERE slug = ?" article-slug])
    (get-article-by-slug conn article-slug user-id)))

(defprotocol ArticleDB
  (create-article [db article])
  (update-article [db article])
  (favorite [db article-slug user-id])
  (unfavorite [db article-slug user-id])
  (get-by-slug [db slug user-id])
  (get-tags [db]))

(extend-protocol ArticleDB
  duct.database.sql.Boundary
  (create-article [db article]
    (jdbc/transact (-> db :spec :datasource)
                   (create-article-tx article)))
  (update-article [db article]
    (jdbc/transact (-> db :spec :datasource)
                   (update-article-tx article)))
  (favorite [db article-slug user-id]
    (jdbc/transact (-> db :spec :datasource)
                   (favorite-tx article-slug user-id)))
  (unfavorite [db article-slug user-id]
    (jdbc/transact (-> db :spec :datasource)
                   (unfavorite-tx article-slug user-id)))
  (get-by-slug [db slug user-id]
    (get-article-by-slug (-> db :spec :datasource)
                         slug user-id))
  (get-tags [db]
    (get-all-tags (-> db :spec :datasource))))