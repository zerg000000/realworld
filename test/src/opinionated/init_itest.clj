(ns opinionated.init-itest
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [duct.core :as duct]
            [integrant.core :as ig]
            [opinionated.components.db :as sql]))

(def ^:dynamic db nil)

(duct/load-hierarchy)

(defn prep-config [profiles]
  (-> (io/resource "app/config.edn")
      (duct/read-config)
      (duct/prep-config profiles)))

(defn fixture-with-db-tx [f]
  (let [config (prep-config [:duct.profile/test])
        system (-> config
                   (ig/init [:opinionated.components.db/write]))
        ds (get system :opinionated.components.db/write)]
    (jdbc/with-db-transaction [conn (:spec ds)]
      (jdbc/db-set-rollback-only! conn)
      (binding [db (sql/->Boundary conn)] (f)))
    (ig/halt! system)))

(defn q [query]
  (jdbc/query (:spec db) query))

(use-fixtures :once fixture-with-db-tx)

(deftest ig-test
  (is (= '({:c1 2}) (q "VALUES (2)"))))