(ns opinionated.user-itest
  (:require [clojure.test :refer :all]
            [opinionated.itest-utils :refer :all]
            [opinionated.logic.user :as u]))

(use-fixtures :each fixture-with-db-tx)

(def jwt {:secret "secret"})

(deftest register-test
  (testing "user should be created"
    (u/register db jwt {:username "Emily" :email "abc@123.d" :password "123"})
    (is (= [#:user{:id 1 :email "abc@123.d" :username "Emily"}]
           (q ["SELECT id, email, username FROM user WHERE id = ?" 1])))))

(deftest register-duplicated-test
  (testing "duplicated user register should be rejected"
    (u/register db jwt {:username "Emily" :email "abc@123.d" :password "123"})
    (is (thrown? org.sqlite.SQLiteException 
                 (u/register db jwt {:username "Emily" :email "abc@123.d" :password "123"})))))

(deftest register-validation-failed-test
  (testing "should be username / email / password mandatory"
    (is (thrown? org.sqlite.SQLiteException (u/register db jwt {:username "Emily" :email "abc@123.d"})))
    (is (thrown? org.sqlite.SQLiteException (u/register db jwt {:username "Emily" :password "123"})))
    (is (thrown? org.sqlite.SQLiteException (u/register db jwt {:email "abc@123.d" :password "123"})))
    #_(is (thrown? Exception (u/register db jwt {})))))