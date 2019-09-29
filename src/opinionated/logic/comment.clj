(ns opinionated.logic.comment
  (:require [clojure.spec.alpha :as s]
            [duct.database.sql]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [honeysql.core :as h]
            [honeysql.helpers :as hs])
  (:import [java.util Date]))

(def comment-base
  {:select [:c.* :author.username :author.bio :author.image]
   :from [[:article_comment :c]]
   :left-join [[:user :author] [:= :c.commenter :author.id]]})

(defn with-user [query user-id]
  (-> query
      (hs/merge-select [:uf.followingUserId :following])
      (hs/merge-left-join [:user_following :uf] [:and [:= :author.id :uf.followingUserId]
                                                      [:= :uf.userId user-id]])))

(defn without-user [query]
  (-> query
      (hs/merge-select [:null :following])))

(defn nice-comment [c]
  (-> (select-keys c [:id :body :createdAt :updatedAt])
      (assoc :author (select-keys c [:username :bio :image :following]))
      (update-in [:author :following] boolean)
      (update :createdAt #(Date. %))
      (update :updatedAt #(Date. %))))

(defn get-comment-by-id [conn comment-id commenter]
  {:comment 
   (-> (jdbc/execute-one! conn (-> (with-user comment-base commenter)
                                   (hs/merge-where [:= :c.id comment-id])
                                   (h/format))
                          {:builder-fn rs/as-unqualified-maps})
       (nice-comment))})

(defn get-comment-by-slug [conn article-slug user-id]
  {:comments
   (->> (jdbc/plan conn  (-> (if user-id
                               (with-user comment-base user-id)
                               (without-user comment-base))
                             (hs/merge-where [:in :c.articleId {:select [:id] 
                                                                :from [:article] 
                                                                :where [:= :slug article-slug]}])
                             (h/format))
                          {:builder-fn rs/as-unqualified-maps})
       (into [] (map nice-comment)))})

(defn add-comment-tx [article-slug comment commenter]
  (fn [conn]
    (let [id (-> (jdbc/execute-one! conn
                                    ["INSERT INTO article_comment (articleId, body, createdAt, updatedAt, commenter)
                         VALUES ((SELECT id FROM article WHERE slug = ?), ?, ?, ?, ?);"
                                     article-slug (:body comment) (Date.) (Date.) commenter]
                                    {:return-keys true})
                 vals first)]
      (get-comment-by-id conn id commenter))))

(defprotocol CommentDB
  (add-comment [db article-slug comment commenter])
  (delete-comment [db comment-id user-id])
  (get-comment [db article-slug user-id]))

(extend-protocol CommentDB
  duct.database.sql.Boundary
  (get-comment [db article-slug user-id]
    (get-comment-by-slug (-> db :spec :datasource)
                         article-slug
                         user-id))
  (add-comment [db article-slug comment commenter]
    (jdbc/transact (-> db :spec :datasource)
                   (add-comment-tx article-slug comment commenter)))
  (delete-comment [db comment-id user-id]
    (jdbc/execute-one! (-> db :spec :datasource)
                       ["DELETE FROM article_comment WHERE id = ? AND commenter = ?;" comment-id user-id])
    nil))