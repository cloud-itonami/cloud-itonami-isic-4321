(ns electrical.operation
  "Operation shapes for the Electrical Trade Coordination actor.
  All operations have `:effect :propose` -- this actor recommends
  coordination actions, does not execute electrical work.

  Five core operations:
  1. `:project/intake` -- register a new electrical installation project
  2. `:log-progress` -- record installation milestone (low-risk)
  3. `:schedule-crew-dispatch` -- propose electrician crew dispatch
  4. `:flag-safety-hazard` -- surface an electrical hazard (escalates)
  5. `:request-inspection-review` -- propose inspection by licensed electrician (escalates)
  ")

(defn project-intake
  "Register a new electrical installation project.
  Returns {:op :project/intake :effect :propose :value {...} :subject project-id
           :confidence :cites}.

  value: {:jurisdiction :electrician-license :scope-description :site-address}
  subject: project-id (unique identifier)
  effect: :propose (proposes registration, human approves)
  confidence: 0.0-1.0 from advisor"
  [project-id jurisdiction electrician-license scope-description site-address
   & {:keys [confidence cites] :or {confidence 0.8 cites []}}]
  {:op :project/intake
   :effect :propose
   :subject project-id
   :value {:jurisdiction jurisdiction
           :electrician-license electrician-license
           :scope-description scope-description
           :site-address site-address}
   :confidence confidence
   :cites cites})

(defn log-progress
  "Record a progress milestone in project timeline.
  Returns {:op :log-progress :effect :propose :value {...} :subject project-id
           :confidence :cites}.

  value: {:milestone :description}
  subject: project-id
  effect: :propose (can auto-commit at phase 3 if governor-clean)
  confidence: 0.0-1.0"
  [project-id milestone description
   & {:keys [confidence cites] :or {confidence 0.9 cites []}}]
  {:op :log-progress
   :effect :propose
   :subject project-id
   :value {:milestone milestone :description description}
   :confidence confidence
   :cites cites})

(defn schedule-crew-dispatch
  "Propose dispatching an electrician crew for a specific task.
  Returns {:op :schedule-crew-dispatch :effect :propose :value {...}
           :subject project-id :confidence :cites}.

  value: {:crew-type :task :description :scheduled-time}
  crew-type: e.g. :conduit-installation, :panel-assembly, :inspection-prep
  subject: project-id
  effect: :propose (proposal, human electrician approves dispatch)
  confidence: 0.0-1.0"
  [project-id crew-type task description scheduled-time
   & {:keys [confidence cites] :or {confidence 0.75 cites []}}]
  {:op :schedule-crew-dispatch
   :effect :propose
   :subject project-id
   :value {:crew-type crew-type :task task :description description
           :scheduled-time scheduled-time}
   :confidence confidence
   :cites cites})

(defn flag-safety-hazard
  "Surface an electrical safety hazard (arc-flash, overload risk, code
  violation, fire hazard, etc.). Always escalates to human for immediate
  action.

  value: {:hazard-type :severity :description}
  hazard-type: e.g. :arc-flash-risk, :overload-risk, :code-violation, :fire-hazard
  severity: :critical, :high, :medium, :low
  subject: project-id
  effect: :propose (proposes hazard flag, human electrician/inspector responds)
  confidence: 0.0-1.0"
  [project-id hazard-type severity description
   & {:keys [confidence cites] :or {confidence 0.95 cites []}}]
  {:op :flag-safety-hazard
   :effect :propose
   :subject project-id
   :value {:hazard-type hazard-type :severity severity :description description}
   :confidence confidence
   :cites cites})

(defn request-inspection-review
  "Propose scheduling an inspection review by a licensed electrician or
  inspector. Always escalates to human for approval.

  value: {:scope :reason}
  scope: e.g. :pre-energization, :post-installation, :annual-compliance
  reason: explanation for why inspection is requested
  subject: project-id
  effect: :propose (proposes inspection, electrician/inspector approves)
  confidence: 0.0-1.0"
  [project-id scope reason
   & {:keys [confidence cites] :or {confidence 0.85 cites []}}]
  {:op :request-inspection-review
   :effect :propose
   :subject project-id
   :value {:scope scope :reason reason}
   :confidence confidence
   :cites cites})
