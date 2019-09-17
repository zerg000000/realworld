(ns opinionated.init-itest
  (:require [clojure.test :refer :all]
            [opinionated.itest-utils :refer :all]))

(use-fixtures :once fixture-with-db-tx)

(deftest ig-test
  (is (= '({:c1 2}) (q "VALUES (2)"))))