(ns opinionated.itest-utils
  (:require [clojure.java.io :as io]
            [duct.database.sql]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [duct.core :as duct]
            [integrant.core :as ig]
            [expound.alpha :as expound]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]))

(st/instrument)

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
    (jdbc/with-transaction [conn (-> ds :spec :datasource) {:rollback-only true}]
      (binding [db (duct.database.sql/->Boundary {:datasource conn})] 
        (f)))
    (ig/halt! system)))

(defn q [query]
  (jdbc/execute! (-> db :spec :datasource) query))

(defn i [table cols & items]
  (sql/insert-multi! (-> db :spec :datasource) table cols items))