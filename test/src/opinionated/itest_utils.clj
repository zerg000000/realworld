(ns opinionated.itest-utils
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [duct.core :as duct]
            [integrant.core :as ig]
            [duct.database.sql :as sql]))

(def ^:dynamic db nil)

(duct/load-hierarchy)

(defn prep-config [profiles]
  (-> (io/resource "app/config.edn")
      (duct/read-config)
      (duct/prep-config profiles)))

(defn fixture-with-db-tx [f]
  (let [config (prep-config [:duct.profile/test])
        system (-> config
                   (ig/init [:duct.migrator/ragtime]))
        ds (get system :opinionated.components.db/write)]
    (jdbc/with-db-transaction [conn (:spec ds)]
      (jdbc/db-set-rollback-only! conn)
      (binding [db (sql/->Boundary conn)] (f)))
    (ig/halt! system)))

(defn q [query]
  (jdbc/query (:spec db) query))

(defn i [table & items]
  (jdbc/insert-multi! (:spec db) table items))