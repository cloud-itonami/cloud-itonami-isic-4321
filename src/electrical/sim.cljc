(ns electrical.sim
  "Demo simulation: Electrical Trade Advisor + Governor workflow.
  Shows the actor coordinating an electrical installation project from
  intake through hazard flagging and inspection request.

  Run with: clojure -M:run"
  (:require [electrical.store :as store]
            [electrical.phase :as phase]
            [electrical.governor :as governor]
            [electrical.registry :as registry]
            [electrical.electricaladvisor :as advisor]))

(defn run-demo
  "Execute a demo flow: intake -> progress -> hazard flag -> inspection request."
  []
  (println "\n=== ELECTRICAL TRADE COORDINATION DEMO ===\n")

  ;; Create store and register a project
  (let [st (store/mem-store)
        phase-num 3]

    ;; Step 1: Propose intake
    (println "Step 1: Advisor proposes project intake")
    (let [intake-proposal (advisor/mock-intake-suggestion)
          verdict (governor/check phase-num "demo-project-001" st intake-proposal)
          disposition (phase/verdict->disposition verdict)
          gated (phase/gate phase-num intake-proposal disposition)]
      (println (str "  Proposal: " (:op intake-proposal)))
      (println (str "  Governor violations: " (count (:violations verdict))))
      (println (str "  Gated disposition: " (:disposition gated)))

      (if (= :hold (:disposition gated))
        (println "  BLOCKED by governor.")
        (do
          (println "  -> APPROVED. Creating project.")
          (store/create-project! st
                               {:id "demo-project-001"
                                :jurisdiction :JPN
                                :electrician-license "LE-JP-20240715"
                                :scope-description "Residential rewiring, 200A service upgrade"
                                :site-address "Tokyo, Japan"})

          ;; Step 2: Log progress milestones
          (println "\nStep 2: Advisor logs progress milestones")
          (doseq [progress-prop (advisor/mock-progress-suggestions)]
            (let [verdict (governor/check phase-num "demo-project-001" st progress-prop)
                  disposition (phase/verdict->disposition verdict)
                  gated (phase/gate phase-num progress-prop disposition)]
              (println (str "  " (:milestone (:value progress-prop)) ": "
                           (:disposition gated)))

              (when (not (= :hold (:disposition gated)))
                (store/log-progress! st "demo-project-001"
                                    {:milestone (:milestone (:value progress-prop))
                                     :description (:description (:value progress-prop))
                                     :timestamp (System/currentTimeMillis)}))))

          ;; Step 3: Flag a hazard
          (println "\nStep 3: Advisor flags a safety hazard")
          (let [hazard-props (advisor/mock-hazard-suggestions)
                hazard-prop (first hazard-props)
                verdict (governor/check phase-num "demo-project-001" st hazard-prop)
                disposition (phase/verdict->disposition verdict)
                gated (phase/gate phase-num hazard-prop disposition)]
            (println (str "  Hazard: " (-> hazard-prop :value :hazard-type)))
            (println (str "  Severity: " (-> hazard-prop :value :severity)))
            (println (str "  Governor disposition: " (:disposition gated)))
            (println (str "  Reason: " (get gated :reason (when (= :escalate (:disposition gated))
                                                             "HIGH-STAKES GATE (always escalates)"))))

            (when (not (= :hold (:disposition gated)))
              (store/flag-hazard! st "demo-project-001"
                                 {:hazard-type (-> hazard-prop :value :hazard-type)
                                  :severity (-> hazard-prop :value :severity)
                                  :description (-> hazard-prop :value :description)
                                  :timestamp (System/currentTimeMillis)})))

          ;; Step 4: Request inspection
          (println "\nStep 4: Advisor requests inspection review")
          (let [insp-prop (advisor/mock-inspection-request)
                verdict (governor/check phase-num "demo-project-001" st insp-prop)
                disposition (phase/verdict->disposition verdict)
                gated (phase/gate phase-num insp-prop disposition)]
            (println (str "  Inspection scope: " (-> insp-prop :value :scope)))
            (println (str "  Governor disposition: " (:disposition gated)))
            (println (str "  Reason: " (get gated :reason (when (= :escalate (:disposition gated))
                                                             "HIGH-STAKES GATE (always escalates)"))))

            (when (not (= :hold (:disposition gated)))
              (store/request-inspection! st "demo-project-001"
                                        {:requested-by "advisor"
                                         :timestamp (System/currentTimeMillis)})))

          ;; Step 5: Display final project record
          (println "\n" (registry/render-project-record
                         (store/project-by-id st "demo-project-001") true)))))))

(defn -main [& _args]
  (run-demo))
