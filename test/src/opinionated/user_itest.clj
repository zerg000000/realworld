(ns opinionated.user-itest
  (:require [clojure.test :refer :all]
            [opinionated.itest-utils :refer :all]
            [opinionated.logic.user :as u]
            [clojure.spec.alpha :as s]
            [clojure.test.check.generators :as gen]
            [clojure.spec.test.alpha :as check]
            [orchestra.spec.test :as st]))

(st/instrument)

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

#_(deftest register-validation-failed-test
  (testing "should be username / email / password mandatory"
    (let [uniq-email (atom #{})
          results (check/check `u/register {:clojure.spec.test.check/opts {:num-tests 100}
                                            :gen {:opinionated.logic.user/db #(gen/return db)
                                                  :opinionated.logic.user/jwt #(gen/return jwt)
                                                  :opinionated.logic.user/email #(gen/such-that (fn [v]
                                                                                                  (let [rs (boolean (@uniq-email v))]
                                                                                                    (swap! uniq-email conj v)
                                                                                                    (not rs))) 
                                                                                                gen/string-alphanumeric)}})]
      (prn results)
      (is (-> results first :clojure.spec.test.check/ret :pass?)))))

#_(deftest abc
  (let [command-specs (map (fn [sp]
                             (gen/tuple
                              (gen/return sp)
                              (s/gen (:args (s/get-spec sp))
                                     {:opinionated.logic.user/db #(gen/return db)
                                      :opinionated.logic.user/jwt #(gen/return jwt)
                                      :opinionated.logic.user/id #(gen/elements (range 100))
                                      :opinionated.logic.user/password #(gen/elements ["abc" "edf" "123213"])
                                      :opinionated.logic.user/username #(gen/elements ["kdfj" "dfdf" "dsfsdf"])
                                      :opinionated.logic.user/email #(gen/elements ["kdfj" "dfdf" "dsfsdf"])})))
                           [`u/register `u/login `u/follow `u/unfollow `u/get-user-by-name])
        cmds (gen/sample (gen/one-of command-specs)
                         100)]
    (is (= [] (map (fn [[f args]]
                     [f (rest args)
                      (try (apply (resolve f) args)
                           (catch Exception ex
                             ex))]) 
                   cmds)))))