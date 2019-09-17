(ns opinionated.components.db
  (:require [integrant.core :as ig]
            [hikari-cp.core :as hikari-cp]))

; simplified version db init code
; you can find full version in duct.module/db
(defrecord Boundary [spec])

(defn new-db [{:keys [connection-uri jdbc-url] :as options}]
  (->Boundary {:datasource
               (-> {:jdbc-url (or jdbc-url connection-uri)}
                   (hikari-cp/make-datasource))}))

(defmethod ig/init-key :opinionated.components.db/read [_ options]
  (new-db options))

(defmethod ig/halt-key! :opinionated.components.db/read [_ {:keys [spec]}]
  (hikari-cp/close-datasource (:datasource spec)))

(defmethod ig/init-key :opinionated.components.db/write [_ options]
  (new-db options))

(defmethod ig/halt-key! :opinionated.components.db/write [_ {:keys [spec]}]
  (hikari-cp/close-datasource (:datasource spec)))