(ns dev
  (:refer-clojure :exclude [test])
  (:require 
   ; reloadable workflow
   [clojure.repl :refer :all]
   [clojure.tools.namespace.repl :refer [refresh]]
   [integrant.core :as ig]
   [integrant.repl :refer [clear halt go init prep reset]]
   [integrant.repl.state :refer [config system]]
   [duct.core :as duct]
   ; better error message
   [clojure.spec.alpha :as s]
   [expound.alpha :as expound]
   #_[com.walmartlabs.lacinia.expound]
   ; useful namespace
   [clojure.java.jdbc :as jdbc]
   [clojure.java.io :as io]))

; enable reflection warning, to avoid trivial performance problem
(set! *warn-on-reflection* true)

; adding a better error message printer
(set! s/*explain-out* (expound/custom-printer {:show-valid-values? true :print-specs? false :theme :figwheel-theme}))

; only project source should be refreshed, I don't want to reload my dev/*.clj
; If I need to reload my dev/*.clj, we can either use 'load-file' or eval in REPL
(clojure.tools.namespace.repl/set-refresh-dirs  "src" "resources")

; duct/integrant is our component library
; load duct component hierarchy
(duct/load-hierarchy)

; load duct config
(defn read-config 
  "read the app config"
  []
  (duct/read-config (io/resource "app/config.edn")))

; development profiles
(def profiles
  [:duct.profile/dev :duct.profile/local])

; setup integrant repl, so that we can restart REPL
(integrant.repl/set-prep! #(duct/prep-config (read-config) profiles))

; useful functions on your own workbench
(defn db []
  (-> (ig/find-derived-1 system [:opinionated.components.db/write])
      second
      :spec))

(defn q 
  "query current db. e.g. (q [\"select top 10 * from table where id = ?\" 1])"
  [query]
  (jdbc/query (db) query))

(defn i
  "insert record to db"
  [table record]
  (jdbc/insert! (db) table record))

(comment
  (reset)
  (i :user {:user_secret "no_secret"})
  (q "SELECT * from user"))