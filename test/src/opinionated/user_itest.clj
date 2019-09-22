(ns opinionated.user-itest
  (:require [clojure.test :refer :all]
            [opinionated.itest-utils :refer :all]
            [opinionated.logic.user :as u]))

(use-fixtures :each fixture-with-db-tx)

(deftest register-test
  (testing "user should be created"
    (u/register db {:username "Emily" :email "abc@123.d" :password "123"})
    (is (= [#:user{:id 1 :password "123" :email "abc@123.d" :bio nil :image nil :username "Emily"}]
           (q ["SELECT * FROM user WHERE id = ?" 1])))))

(deftest register-duplicated-test
  (testing "duplicated user register should be rejected"
    (u/register db {:username "Emily" :email "abc@123.d" :password "123"})
    (is (thrown? org.sqlite.SQLiteException 
                 (u/register db {:username "Emily" :email "abc@123.d" :password "123"})))))

(deftest register-validation-failed-test
  (testing "should be username / email / password mandatory"
    (is (thrown? org.sqlite.SQLiteException (u/register db {:username "Emily" :email "abc@123.d"})))
    (is (thrown? org.sqlite.SQLiteException (u/register db {:username "Emily" :password "123"})))
    (is (thrown? org.sqlite.SQLiteException (u/register db {:email "abc@123.d" :password "123"})))
    #_(is (thrown? Exception (u/register db {})))))