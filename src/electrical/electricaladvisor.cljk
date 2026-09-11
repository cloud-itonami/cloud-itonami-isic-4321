(ns electrical.electricaladvisor
  "Electrical Trade Advisor -- the LLM-driven proposal side. Observes
  project state and proposes coordination actions to the Electrical
  Trade Governor. The advisor has NO authority to execute electrical
  work, energize installations, or certify code compliance -- all such
  decisions are reserved for licensed electricians and inspectors.

  The advisor's role is COORDINATION: scheduling crew dispatch,
  proposing hazard investigations, requesting inspections. The governor
  gates all proposals; real execution is human domain."
  (:require [electrical.operation :as op]
            [electrical.store :as store]))

(defn- make-proposal
  "Assemble a proposal for the governor to evaluate. Includes the
  operation itself plus metadata (confidence, legal citations)."
  [operation-fn & args]
  (let [proposal (apply operation-fn args)]
    (assoc proposal :timestamp (System/currentTimeMillis))))

(defn propose-project-intake
  "Advisor proposes registering a new electrical installation project.
  Returns an operation map for governor evaluation."
  [project-id jurisdiction electrician-license scope-description site-address]
  (make-proposal op/project-intake project-id jurisdiction electrician-license
                 scope-description site-address :confidence 0.8))

(defn propose-log-progress
  "Advisor proposes recording a progress milestone."
  [project-id milestone description]
  (make-proposal op/log-progress project-id milestone description :confidence 0.9))

(defn propose-crew-dispatch
  "Advisor proposes scheduling an electrician crew for a task."
  [project-id crew-type task description scheduled-time]
  (make-proposal op/schedule-crew-dispatch project-id crew-type task
                 description scheduled-time :confidence 0.75))

(defn propose-hazard-flag
  "Advisor flags an electrical hazard. This always escalates to human
  for immediate safety review."
  [project-id hazard-type severity description]
  (make-proposal op/flag-safety-hazard project-id hazard-type severity
                 description :confidence 0.95))

(defn propose-inspection-request
  "Advisor proposes scheduling an inspection by licensed electrician."
  [project-id scope reason]
  (make-proposal op/request-inspection-review project-id scope reason
                 :confidence 0.85))

;; Mock advisor implementation (deterministic for testing/demo)
(def mock-advisor
  "Mock advisor that makes simple, deterministic proposals for demo purposes."
  {:name "MockElectricalAdvisor"
   :version "0.1.0"
   :confidence-bias 0.8})

(defn mock-intake-suggestion
  "Demo: suggest project intake with fixed values."
  []
  (propose-project-intake "demo-project-001" :JPN "LE-JP-20240715"
                         "Residential rewiring, 200A service upgrade"
                         "Tokyo, Japan"))

(defn mock-progress-suggestions
  "Demo: suggest a series of progress milestones."
  []
  [(propose-log-progress "demo-project-001" :site-prep "Inspection of existing installation")
   (propose-log-progress "demo-project-001" :permits-filed "Submitted to local authority")
   (propose-log-progress "demo-project-001" :materials-staged "Conduit and wire staged at site")])

(defn mock-hazard-suggestions
  "Demo: flag a sample hazard."
  []
  [(propose-hazard-flag "demo-project-001" :overload-risk :high
                        (str "Existing panel load appears to exceed 80% capacity. "
                             "Recommend full load analysis before adding new circuits."))])

(defn mock-inspection-request
  "Demo: propose pre-energization inspection."
  []
  (propose-inspection-request "demo-project-001" :pre-energization
                             (str "Verify installation is complete, grounding intact, "
                                  "no code violations before energization.")))
