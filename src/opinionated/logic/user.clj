(ns opinionated.logic.user
  (:require [clojure.spec.alpha :as s]
            [duct.database.sql]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [buddy.hashers :as hashers]
            [buddy.sign.jwt :as jwt]))

(s/def ::email string?)
(s/def ::username string?)
(s/def ::password string?)
(s/def ::bio string?)
(s/def ::image string?)

(s/def ::register
  (s/keys :req-un [::email ::username ::password]
          :opt-un [::bio ::image]))

(s/def ::login
  (s/keys :req-un [::email ::password]))

(defn get-full-user [conn jwt id]
  (-> (sql/get-by-id conn :user id)
      (dissoc :user/password)
      (assoc :user/token (jwt/sign {:user id} (:secret jwt)))))

(defn login-tx [user jwt]
  (fn [conn]
    (let [full-user (first (sql/find-by-keys conn :user (select-keys user [:email])))]
      (if (and full-user 
               (hashers/check (:password user) (:user/password full-user)))
        {:user (-> (dissoc full-user :user/password)
                   (assoc :user/token (jwt/sign {:user (:user/id full-user)} (:secret jwt))))}
        (throw (Exception. "Your email or password is not correct."))))))

(defn register-tx [user jwt]
  (fn [conn]
    (let [id (-> (sql/insert! conn :user (select-keys user [:email :password :username :bio :image])) 
                 vals first)]
      {:user (get-full-user conn jwt id)})))

(s/fdef register-tx
  :args (s/cat :user ::register)
  :ret nil?)

(defprotocol UserDB
  (register [db jwt user] "Register a new user")
  (login [db jwt user] "Login a user")
  (get-user [db jwt user-id] "Get user"))

(extend-protocol UserDB
  duct.database.sql.Boundary
  (register [db jwt user]
    (jdbc/transact (-> db :spec :datasource)
                   (register-tx user jwt)))
  (login [db jwt user]
    (jdbc/transact (-> db :spec :datasource)
                   (login-tx user jwt)))
  (get-user [db jwt user-id]
    (get-full-user (-> db :spec :datasource)
                   jwt user-id)))