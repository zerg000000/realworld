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
      (hs/merge-select [:null :following] [:null :favorited])))

(defn nice-article [article]
  (-> (select-keys article [:slug :title :description :body :createdAt :updatedAt :favoritesCount :favorited :tag])
      (assoc :author (select-keys article [:username :bio :image :following]))
      (update-in [:author :following] boolean)
      (update :favorited boolean)
      (update :tagList #(if % 
                          (str/split "," %)
                          []))
      (update :createdAt #(Date. %))
      (update :updatedAt #(Date. %))))

(defn get-article-by-slug [conn article-slug user-id]
  (let [raw (jdbc/execute-one! conn (-> (if user-id
                                          (with-user article-base user-id)
                                          (without-user article-base))
                                        (hs/where [:= :a.slug article-slug])
                                        (h/format))
                               {:builder-fn rs/as-unqualified-maps})]
    {:article (nice-article raw)}))

(defn get-article-feed [conn user-id {:keys [limit offset]
                                      :or {limit 20
                                           offset 0}}]
  (let [sql (-> article-base
                (with-user user-id)
                (hs/where [:and [:= :uf.followingUserId :a.author]
                          [:= :uf.userId user-id]])
                (hs/order-by [:createdAt :desc])
                (hs/limit limit)
                (hs/offset offset))]
  {:articles
   (into [] (map nice-article) 
         (jdbc/plan conn (h/format sql)
                    {:builder-fn rs/as-unqualified-maps}))
   :articlesCount (-> (jdbc/execute-one! conn (-> sql
                                                  (hs/select :%count.1)
                                                  (h/format)))
                      vals first)}))

(defn find-articles [conn user-id {:keys [limit offset tag author favorited]
                                   :or {limit 20
                                        offset 0}
                                   :as opts}]
  (let [sql (cond-> article-base
              user-id (with-user user-id)
              (not user-id) (without-user)
              tag (hs/merge-where [:exists {:select [:1] 
                                            :from [:article_tag]
                                            :where [:and [:= :tag tag] 
                                                    [:= :articleId :a.id]]}])
              author (hs/merge-where [:= :author.username author])
              favorited (hs/merge-where [:exists {:select [:1]
                                                  :from [[:article_favorite :filter_fav]]
                                                  :inner-join [[:user :fav_user] [:= :fav_user.username favorited]]
                                                  :where [:= :filter_fav.articleId :a.id]}])
              true (hs/order-by [:createdAt :desc])
              limit (hs/limit limit)
              offset (hs/offset offset))]
    {:articles
     (->> (jdbc/plan conn (h/format sql)
                     {:builder-fn rs/as-unqualified-maps})
          (into [] (map nice-article)))
     :articlesCount (-> (jdbc/execute-one! conn (-> sql
                                                    (hs/select :%count.1)
                                                    (h/format)))
                        vals first)}))

(defn create-article-tx [article]
  (let [new-article 
        (-> article
            (select-keys [:title :description :body :author])
            (assoc :slug (str/uslug (:title article))
                   :tagList (str/join "," (or (:tagList article) []))
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
        (cond-> (select-keys article [:title :description :body])
          (:title article)
          (assoc :slug (str/uslug (:title article)))
          true (assoc :updatedAt (Date.)))]
    (fn [conn]
      (sql/update! conn :article update-article existing)
      (get-article-by-slug conn 
                           (or (:slug update-article) (:slug article)) 
                           (:author existing)))))

(defn delete-article-tx [article-slug user-id]
  (fn [conn]
    (when-let [id (:article/id (jdbc/execute-one! conn ["SELECT id FROM article WHERE slug = ? AND author = ?" article-slug user-id]))]
      (sql/delete! conn :article_tag {:articleId id})
      (sql/delete! conn :article_comment {:articleId id})
      (sql/delete! conn :article {:id id})
      nil)))

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
  (delete-article [db article-slug user-id])
  (favorite [db article-slug user-id])
  (unfavorite [db article-slug user-id])
  (get-by-slug [db slug user-id])
  (get-tags [db])
  (get-feed [db user-id args])
  (get-articles [db user-id args]))

(extend-protocol ArticleDB
  duct.database.sql.Boundary
  (create-article [db article]
    (jdbc/transact (-> db :spec :datasource)
                   (create-article-tx article)))
  (update-article [db article]
    (jdbc/transact (-> db :spec :datasource)
                   (update-article-tx article)))
  (delete-article [db article-slug user-id]
    (jdbc/transact (-> db :spec :datasource)
                   (delete-article-tx article-slug user-id)))
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
    (get-all-tags (-> db :spec :datasource)))
  (get-feed [db user-id args]
    (get-article-feed (-> db :spec :datasource) user-id args))
  (get-articles [db user-id args]
    (find-articles (-> db :spec :datasource) user-id args)))