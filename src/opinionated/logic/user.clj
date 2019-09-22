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

(defn register-tx [user]
  (fn [conn]
    (let [id (-> (sql/insert! conn :user (-> (select-keys user [:email :password :username :bio :image])
                                             (update :password hashers/derive))) 
                 vals first)]
      {:user (-> (sql/get-by-id conn :user id)
                 (dissoc :user/password)
                 (assoc :user/token (jwt/sign {:id id} "secret")))})))

(s/fdef register-tx
  :args (s/cat :user ::register)
  :ret nil?)

(defprotocol UserDB
  (register [db user] "Register a new user"))

(extend-protocol UserDB
  duct.database.sql.Boundary
  (register [db user]
    (jdbc/transact (-> db :spec :datasource)
                   (register-tx user))))