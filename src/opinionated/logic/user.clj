(ns opinionated.logic.user
  (:require [clojure.spec.alpha :as s]
            [duct.database.sql]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [next.jdbc.result-set :as rs]
            [buddy.hashers :as hashers]
            [buddy.sign.jwt :as jwt]))

(s/def ::id pos-int?)
(s/def ::email string?)
(s/def ::username string?)
(s/def ::password string?)
(s/def ::bio (s/nilable string?))
(s/def ::image (s/nilable string?))
(s/def ::token string?)
(s/def ::following boolean?)

(s/def ::register
  (s/keys :req-un [::email ::username ::password]
          :opt-un [::bio ::image]))

(s/def ::user ::register)

(s/def ::register-form
  (s/keys :req-un [::user]))

(s/def :login/user
  (s/keys :req-un [::email ::password]))

(s/def ::login-form
  (s/keys :req-un [:login/user]))

(s/def ::secret string?)

(s/def ::jwt
  (s/keys :req-un [::secret]))

(s/def ::db
  #(instance? duct.database.sql.Boundary %))

(s/def :single/user
  (s/keys :req-un [::email ::username ::bio ::image ::token]))

(s/def :single/profile
  (s/keys :req-un [::username ::bio ::image ::following]))

(s/def :output/user
  (s/keys :req-un [:single/user]))

(s/def :output/profile
  (s/keys :req-un [:single/profile]))

(defn get-full-user [conn jwt id]
  {:user (-> (sql/get-by-id conn :user id {:builder-fn rs/as-unqualified-maps})
           (dissoc :password)
           (assoc :token (jwt/sign {:user id} (:secret jwt))))})

(defn get-full-user-by-name [conn username id]
  (when-let [user (jdbc/execute-one! conn
                                     (if id
                                       ["SELECT username, bio, image, user_following.id following
                          FROM user 
                          LEFT JOIN user_following
                            ON user.id = user_following.followingUserId
                           AND user_following.userId = ?
                         WHERE user.username = ? " id username]
                                       ["SELECT username, bio, image FROM user WHERE username = ? " username])
                                     {:builder-fn rs/as-unqualified-maps})]
    {:profile (update user :following boolean)}))

(defn login-tx [user jwt]
  (fn [conn]
    (let [full-user (first (sql/find-by-keys conn :user (select-keys user [:email]) {:builder-fn rs/as-unqualified-maps}))]
      (if (and full-user 
               (hashers/check (:password user) (:password full-user)))
        {:user (-> (dissoc full-user :password)
                   (assoc :token (jwt/sign {:user (:id full-user)} (:secret jwt))))}
        (throw (Exception. "Your email or password is not correct."))))))

(defn register-tx [user jwt]
  (fn [conn]
    (let [id (-> (sql/insert! conn :user (-> (select-keys user [:email :password :username :bio :image])
                                             (update :password hashers/derive {:alg :pbkdf2+sha1}))) 
                 vals first)]
      (get-full-user conn jwt id))))

(defn update-tx [user jwt]
  (fn [conn]
    (sql/update! conn :user
                 (select-keys user [:email :bio :image])
                 (select-keys user [:id]))
    (get-full-user conn jwt (:id user))))

(defn follow-tx [user-id following-username]
  (fn [conn]
    (jdbc/execute! conn ["INSERT INTO user_following 
                          (userId, followingUserId) 
                          VALUES (?, (SELECT id FROM user WHERE username = ? LIMIT 1))" 
                         user-id following-username])
    (get-full-user-by-name conn following-username user-id)))

(defn unfollow-tx [user-id following-username]
  (fn [conn]
    (jdbc/execute! conn ["DELETE FROM user_following 
                           WHERE userId = ? 
                             AND followingUserId in (SELECT id FROM user WHERE username = ?)"
                         user-id following-username])
    (get-full-user-by-name conn following-username user-id)))

(defprotocol UserDB
  (register [db jwt user] "Register a new user")
  (login [db jwt user] "Login a user")
  (update-user [db jwt user] "Update user")
  (get-user [db jwt user-id] "Get user")
  (get-user-by-name [db username id] "Get user")
  (follow [db user-id following-user-id] "Follow a user")
  (unfollow [db user-id following-user-id] "Unfollow a user"))

(extend-protocol UserDB
  duct.database.sql.Boundary
  (register [db jwt user]
    (jdbc/transact (-> db :spec :datasource)
                   (register-tx user jwt)))
  (login [db jwt user]
    (jdbc/transact (-> db :spec :datasource)
                   (login-tx user jwt)))
  (update-user [db jwt user]
    (jdbc/transact (-> db :spec :datasource)
                   (update-tx user jwt)))
  (get-user [db jwt user-id]
    (get-full-user (-> db :spec :datasource)
                   jwt user-id))
  (get-user-by-name [db username id]
    (get-full-user-by-name 
      (-> db :spec :datasource)
      username id))
  (follow [db user-id following-username]
    (jdbc/transact (-> db :spec :datasource)
                   (follow-tx user-id following-username)))
  (unfollow [db user-id following-username]
    (jdbc/transact (-> db :spec :datasource)
                   (unfollow-tx user-id following-username))))

(s/fdef register
  :args (s/cat :db ::db :jwt ::jwt :user ::user)
  :ret :output/user)

(s/fdef login
  :args (s/cat :db ::db :jwt ::jwt :login-form :login/user)
  :ret :output/user)

(s/fdef get-user
  :args (s/cat :db ::db :jwt ::jwt :user ::id)
  :ret :output/user)

(s/fdef get-user-by-name
  :args (s/cat :db ::db :username ::username :user ::id)
  :ret :output/profile)

(s/fdef follow
  :args (s/cat :db ::db :follower ::id :followee ::username)
  :ret :output/profile)

(s/fdef unfollow
  :args (s/cat :db ::db :follower ::id :followee ::username)
  :ret :output/profile)