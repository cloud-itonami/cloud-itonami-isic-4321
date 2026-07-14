(ns electrical.governor-test
  "Electrical Trade Governor tests: verify the independent compliance
  layer that gates proposals from the advisor."
  (:require [clojure.test :refer [deftest is testing]]
            [electrical.governor :as governor]
            [electrical.store :as store]
            [electrical.operation :as op]))

(deftest invalid-jurisdiction-rejected
  (testing "Governor rejects unknown jurisdiction"
    (let [st (store/mem-store)
          proposal (op/project-intake "test-proj" :UNKNOWN "LE-001"
                                     "Test" "Test")]
      (let [verdict (governor/check 3 "test-proj" st proposal)]
        (is (:hard? verdict))
        (is (seq (:violations verdict)))
        (is (= :invalid-jurisdiction (-> verdict :violations first :rule)))))))

(deftest license-missing-rejected
  (testing "Governor rejects missing electrician license"
    (let [st (store/mem-store)
          proposal (op/project-intake "test-proj" :JPN nil "Test" "Test")]
      (let [verdict (governor/check 3 "test-proj" st proposal)]
        (is (:hard? verdict))
        (is (some #(= :electrician-license-missing (:rule %))
                  (:violations verdict)))))))

(deftest project-must-be-registered
  (testing "Governor blocks operations on unregistered project"
    (let [st (store/mem-store)
          proposal (op/log-progress "unregistered-proj" :test-milestone "Test")]
      (let [verdict (governor/check 3 "unregistered-proj" st proposal)]
        (is (:hard? verdict))
        (is (some #(= :project-not-registered (:rule %))
                  (:violations verdict)))))))

(deftest hazard-always-escalates
  (testing "Hazard flags always escalate (high-stakes gate)"
    (let [st (store/mem-store)]
      (store/create-project! st
                            {:id "proj"
                             :jurisdiction :JPN
                             :electrician-license "LE-JP-001"
                             :scope-description "Test"
                             :site-address "Test"})

      (let [proposal (op/flag-safety-hazard "proj" :arc-flash-risk :critical "Test")]
        (let [verdict (governor/check 3 "proj" st proposal)]
          ;; No hard violations, but escalate? should be true due to high-stakes gate
          (is (not (:hard? verdict)))
          (is (:escalate? verdict)))))))

(deftest inspection-request-escalates
  (testing "Inspection requests always escalate (high-stakes)"
    (let [st (store/mem-store)]
      (store/create-project! st
                            {:id "proj"
                             :jurisdiction :USA
                             :electrician-license "LE-US-001"
                             :scope-description "Test"
                             :site-address "Test"})

      (let [proposal (op/request-inspection-review "proj" :pre-energization "Test")]
        (let [verdict (governor/check 3 "proj" st proposal)]
          (is (:escalate? verdict)))))))

(deftest crew-dispatch-escalates
  (testing "Crew dispatch proposals escalate (coordination decision)"
    (let [st (store/mem-store)]
      (store/create-project! st
                            {:id "proj"
                             :jurisdiction :DEU
                             :electrician-license "LE-DE-001"
                             :scope-description "Test"
                             :site-address "Test"})

      (let [proposal (op/schedule-crew-dispatch "proj" :conduit-installation
                                               "Install" "Test" "2026-08-01")]
        (let [verdict (governor/check 3 "proj" st proposal)]
          (is (:escalate? verdict)))))))

(deftest progress-logging-can-be-clean
  (testing "Progress logging can pass governor cleanly"
    (let [st (store/mem-store)]
      (store/create-project! st
                            {:id "proj"
                             :jurisdiction :JPN
                             :electrician-license "LE-JP-001"
                             :scope-description "Test"
                             :site-address "Test"})

      (let [proposal (op/log-progress "proj" :permits-filed "Filed")]
        (let [verdict (governor/check 3 "proj" st proposal)]
          (is (not (:hard? verdict)))
          (is (not (:escalate? verdict))))))))

(deftest low-confidence-escalates
  (testing "Low confidence always escalates"
    (let [st (store/mem-store)]
      (store/create-project! st
                            {:id "proj"
                             :jurisdiction :USA
                             :electrician-license "LE-US-001"
                             :scope-description "Test"
                             :site-address "Test"})

      (let [proposal (assoc (op/log-progress "proj" :test "Test")
                           :confidence 0.4)]
        (let [verdict (governor/check 3 "proj" st proposal)]
          (is (:escalate? verdict)))))))

