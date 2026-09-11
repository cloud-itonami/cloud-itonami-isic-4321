(ns electrical.store-contract-test
  "Store protocol contract test: verifies that MemStore (and any other
  Store impl) satisfies the Store interface correctly. Used in both
  Clojure (JVM/Chicory) and ClojureScript (browser/nbb) contexts."
  (:require [clojure.test :refer [deftest is testing]]
            [electrical.store :as store]))

(deftest mem-store-implements-protocol
  (testing "MemStore implements Store protocol"
    (let [st (store/mem-store)]
      (is (satisfies? store/Store st))
      (is (instance? electrical.store.MemStore st)))))

(deftest project-lifecycle
  (testing "Project create, retrieve, update cycle"
    (let [st (store/mem-store)]
      ;; Verify empty store
      (is (empty? (store/all-projects st)))
      (is (nil? (store/project-by-id st "test-proj-1")))

      ;; Create project
      (let [proj (store/create-project! st
                                       {:id "test-proj-1"
                                        :jurisdiction :JPN
                                        :electrician-license "LE-JP-001"
                                        :scope-description "Test installation"
                                        :site-address "Test Site"})]
        (is (= "test-proj-1" (:id proj)))
        (is (= :JPN (:jurisdiction proj)))
        (is (:registered? proj))
        (is (not (:closed? proj)))

        ;; Verify registered
        (is (store/verify-registered st "test-proj-1"))

        ;; Retrieve project
        (let [retrieved (store/project-by-id st "test-proj-1")]
          (is (= proj retrieved)))

        ;; List all
        (is (= 1 (count (store/all-projects st)))))))
  )

(deftest hazard-flagging
  (testing "Flag and retrieve hazards"
    (let [st (store/mem-store)]
      (store/create-project! st
                            {:id "test-proj-2"
                             :jurisdiction :USA
                             :electrician-license "LE-US-001"
                             :scope-description "Commercial panel upgrade"
                             :site-address "New York"})

      ;; Flag a hazard
      (let [proj (store/flag-hazard! st "test-proj-2"
                                    {:hazard-type :arc-flash-risk
                                     :severity :high
                                     :description "Unsafe clearance"
                                     :timestamp (System/currentTimeMillis)})]
        (is (= 1 (count (:hazard-flags proj))))
        (let [hazard (first (:hazard-flags proj))]
          (is (= :arc-flash-risk (:type hazard)))
          (is (= :high (:severity hazard))))))))

(deftest progress-logging
  (testing "Log and retrieve progress"
    (let [st (store/mem-store)]
      (store/create-project! st
                            {:id "test-proj-3"
                             :jurisdiction :DEU
                             :electrician-license "LE-DE-001"
                             :scope-description "Renovation"
                             :site-address "Berlin"})

      (let [proj (store/log-progress! st "test-proj-3"
                                     {:milestone :permits-filed
                                      :description "Local authority approved"
                                      :timestamp (System/currentTimeMillis)})]
        (is (= 1 (count (:progress-records proj))))
        (let [record (first (:progress-records proj))]
          (is (= :permits-filed (:milestone record))))))))

(deftest inspection-request
  (testing "Request and track inspection"
    (let [st (store/mem-store)]
      (store/create-project! st
                            {:id "test-proj-4"
                             :jurisdiction :JPN
                             :electrician-license "LE-JP-002"
                             :scope-description "Test"
                             :site-address "Test"})

      (let [proj (store/request-inspection! st "test-proj-4"
                                           {:requested-by "advisor"
                                            :timestamp (System/currentTimeMillis)})]
        (is (:inspection-scheduled? proj))))))

(deftest crew-dispatch
  (testing "Schedule crew dispatch"
    (let [st (store/mem-store)]
      (store/create-project! st
                            {:id "test-proj-5"
                             :jurisdiction :USA
                             :electrician-license "LE-US-002"
                             :scope-description "Test"
                             :site-address "Test"})

      (let [proj (store/schedule-crew! st "test-proj-5"
                                      {:crew-type :conduit-installation
                                       :task "Install main feed"
                                       :description "Per approved specs"
                                       :scheduled-time "2026-08-01T10:00"})]
        (is (= 1 (count (:crew-dispatches proj))))))))

(deftest error-on-missing-project
  (testing "Operations on non-existent project throw"
    (let [st (store/mem-store)]
      (is (thrown? #?(:clj Exception :cljs js/Error)
                   (store/flag-hazard! st "nonexistent"
                                      {:hazard-type :test
                                       :severity :low
                                       :description "test"
                                       :timestamp (System/currentTimeMillis)})))))
  )
