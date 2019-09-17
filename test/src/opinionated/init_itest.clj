(ns opinionated.init-itest
  (:require [clojure.test :refer :all]
            [opinionated.itest-utils :refer :all]))

(use-fixtures :once fixture-with-db-tx)

(deftest ig-test
  (i :user 
     {:user_secret "1"}
     {:user_secret "2"})
  (i :user_auth
     {:user_id 1 :method 0 :ident "88881234"})
  (is (= [{:id 1 :user_secret "1"}]
         (q ["SELECT * FROM user WHERE user_secret = ?" "1"])))
  (is (= [{:id 1 :user_id 1 :method 0 :ident "88881234"}]
         (q ["SELECT * FROM user_auth WHERE user_id = ?" 1]))))