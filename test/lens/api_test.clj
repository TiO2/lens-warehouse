(ns lens.api-test
  (:require [clojure.test :refer :all]
            [lens.api :refer :all]
            [lens.schema :refer [load-base-schema]]
            [datomic.api :as d]
            [clojure.core.reducers :as r]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]))

(def date (tc/to-date (t/date-time 2015)))

(defn- connect [] (d/connect "datomic:mem:test"))

(defn database-fixture [f]
  (do
    (d/create-database "datomic:mem:test")
    (load-base-schema (connect)))
  (f)
  (d/delete-database "datomic:mem:test"))

(use-fixtures :each database-fixture)

;; ---- Subject ---------------------------------------------------------------

(deftest subject-test
  (let [conn (connect)]
    (create-subject conn "id-142055")
    (testing "is found"
      (is (:db/id (subject (d/db conn) "id-142055"))))
    (testing "is not found"
      (is (nil? (subject (d/db conn) "other-id-142447"))))))

(deftest create-subject-test
  (let [conn (connect)]
    (testing "create with id only"
      (is (= "id-142850" (:subject/id (create-subject conn "id-142850")))))
    (testing "create with id and sex"
      (is (create-subject conn "id-145037" {:subject/sex :subject.sex/male}))
      (is (= :subject.sex/male (:subject/sex (subject (d/db conn) "id-145037")))))
    (testing "create with id and birth-date"
      (is (create-subject conn "id-162309" {:subject/birth-date date}))
      (is (= date (:subject/birth-date (subject (d/db conn) "id-162309")))))
    (testing "create with existing id fails"
      (create-subject conn "id-143440")
      (is (false? (create-subject conn "id-143440"))))))

(deftest retract-subject-test
  (let [conn (connect)]
    (create-subject conn "id-170655")
    (testing "is retracted"
      (is (retract-subject conn "id-170655"))
      (is (nil? (subject (d/db conn) "id-170655"))))
    (testing "is not found"
      (is (false? (retract-subject conn "other-id-142447"))))))

;; ---- Item Group ------------------------------------------------------------

(deftest item-group-test
  (let [conn (connect)]
    @(d/transact conn [[:add-form "f-184846"]])
    @(d/transact conn [[:add-item-group "ig-185204" "f-184846"]])
    (testing "is found"
      (is (:db/id (item-group (d/db conn) "ig-185204"))))
    (testing "is not found"
      (is (nil? (item-group (d/db conn) "other-ig-185411"))))))

(deftest item-test
  (let [conn (connect)]
    @(d/transact conn [[:add-form "f-184846"]])
    @(d/transact conn [[:add-item-group "ig-185204" "f-184846"]])
    @(d/transact conn [[:add-item "i-185700" "ig-185204"
                        :data-point/long-value]])
    (testing "is found"
      (is (:db/id (item (d/db conn) "i-185700"))))
    (testing "is not found"
      (is (nil? (item (d/db conn) "other-i-185730"))))))

(deftest code-list-item-test
  (let [conn (connect)]
    @(d/transact conn [[:add-form "f-184846"]])
    @(d/transact conn [[:add-item-group "ig-185204" "f-184846"]])
    @(d/transact conn [[:add-item "i-185700" "ig-185204"
                        :data-point/long-value]])
    @(d/transact conn [[:add-code-list "cl-190356" :code-list-item/long-code]])
    @(d/transact conn [[:add-code-list-to-item "i-185700" "cl-190356"]])
    @(d/transact conn [[:add-code-list-item "cl-190356"
                        :code-list-item/long-code 185935]])
    (testing "is found"
      (is (:db/id (code-list-item (item (d/db conn) "i-185700") 185935))))
    (testing "is not found"
      (is (nil? (code-list-item (item (d/db conn) "i-185700") 185947))))))

(deftest all-studies-test
  (let [conn (connect)]
    (testing "with zero studies"
      (is (= #{} (->> (all-studies (d/db conn))
                      (r/map :study/id)
                      (into #{})))))
    (testing "with one study"
      (d/transact conn [[:add-study "id-144922"]])
      (is (= #{"id-144922"} (->> (all-studies (d/db conn))
                                 (r/map :study/id)
                                 (into #{})))))
    (testing "with two studies"
      (d/transact conn [[:add-study "id-150211"]])
      (is (= #{"id-144922"
               "id-150211"} (->> (all-studies (d/db conn))
                                 (r/map :study/id)
                                 (into #{})))))))

(deftest clean-atom-test
  (are [orig cleaned] (= cleaned (clean-atom orig))
       nil nil
       [] nil
       [:invalid] nil
       [:form] nil
       [:form :a] {:type :form :id :a}
       [:form :a :b] {:type :form :id :a}))

(deftest query-disjunction-test
  (testing "nil disjunction"
    (is (= #{} (query-disjunction nil nil))))
  (testing "empty disjunction"
    (is (= #{} (query-disjunction nil [])))))

(deftest query-conjunction-test
  (testing "nil disjunction"
    (is (= #{} (query-conjunction nil nil))))
  (testing "empty disjunction"
    (is (= #{} (query-conjunction nil [])))))

(deftest nearest-rank-test
  (testing "Percentile smaller 0"
    (is (thrown? AssertionError (nearest-rank -1 1))))
  (testing "Percentile bigger 1"
    (is (thrown? AssertionError (nearest-rank 2 1))))
  (testing "n = 0"
    (is (thrown? AssertionError (nearest-rank 0.5 0)))))
