(ns rcf.core
  (:require [hyperfiddle.rcf :refer [tests tap %]]))

(comment
  (hyperfiddle.rcf/enable!))


(tests
  "one plus one"
  (+ 1 1) := 3

  "equality"
  (inc 1) := 2

  "wildcards"
  {:a :b, :b [2 :b]} := {:a _, _ [2 _]}

  "unification"
  {:a :b, :b [2 :b]} := {:a ?b, ?b [2 ?b]}

  "unification on reference types"
  (def x (atom nil))
  {:a x, :b x} := {:a ?x, :b ?x}

  "multiple tests on one value"
  (def xs [:a :b :c])
  (count xs) := 3
  (last xs) := :c
  (let [xs (map identity xs)]
    (last xs) := :c
    (let [] (last xs) := :c)))