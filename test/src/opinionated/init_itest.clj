(ns opinionated.init-itest
  (:require [clojure.test :refer :all]
            [opinionated.itest-utils :refer :all]))

(use-fixtures :once fixture-with-db-tx)

(deftest ig-test
  (i :user
     [:password :email :username]
     ["123" "abc@123.d" "howdy"]
     ["234" "abd@123.d" "diva"])
  (is (= [#:user{:id 1 :password "123" :email "abc@123.d" :username "howdy" :image nil :bio nil}]
         (q ["SELECT * FROM user WHERE email = ?" "abc@123.d"]))))