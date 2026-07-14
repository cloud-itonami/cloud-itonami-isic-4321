(ns electrical.phase
  "Phase 0->3 staged rollout for electrical-trade coordination actor.
  Unlike the construction/disaster actor which performs actuation
  (alerts, work-resume authorization, legal reporting), this electrical
  actor is PROPOSE-ONLY: it proposes coordination actions to a human
  electrician/inspector who decides whether to execute.

  All operations in this actor are `:effect :propose` -- no direct
  electrical work, no energization/de-energization, no certification
  of code compliance/electrical safety. That's the licensed
  electrician's and inspector's domain, exclusively.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- site/project intake allowed, every write
                                 needs human approval.
    Phase 2  assisted-plan    -- adds progress-logging, crew-dispatch
                                 proposals, still approval.
    Phase 3  supervised-auto  -- governor-clean, high-confidence
                                 `:project/intake` (no capital risk) and
                                 `:log-progress` may auto-commit.

  ## Propose-only gate

  `:flag-safety-hazard`, `:request-inspection-review` and
  `:schedule-crew-dispatch` are deliberately ABSENT from every phase's
  `:auto` set, including phase 3 -- permanent structural facts, not
  rollout milestones. These are real human-escalation points: hazard
  flagging requires immediate human attention (safety-critical), and
  crew dispatch/inspection request are coordination acts the electrical
  contractor and inspector must approve. No automation of those
  decisions.

  Every operation's `:effect` is `:propose` -- this actor recommends,
  does not execute. The licensed electrician/inspector is the sole
  authority for electrical safety and code compliance decisions."
  )

(def read-ops  #{})
(def write-ops #{:project/intake :log-progress :schedule-crew-dispatch
                 :flag-safety-hazard :request-inspection-review})

;; NOTE the invariant: `:flag-safety-hazard`,
;; `:schedule-crew-dispatch`, and `:request-inspection-review` are
;; members of `write-ops` (governor-gated like any write) but are NEVER
;; members of any phase's `:auto` set. Do not add them there. They are
;; always human decisions. `:log-progress` may auto-commit at phase 3
;; when governor-clean (low-risk progress milestone recording).
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"        :writes #{}                                                          :auto #{}}
   1 {:label "assisted-intake"  :writes #{:project/intake}                                              :auto #{}}
   2 {:label "assisted-plan"    :writes #{:project/intake :log-progress :schedule-crew-dispatch}      :auto #{}}
   3 {:label "supervised-auto"  :writes write-ops
      :auto #{:project/intake :log-progress}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:flag-safety-hazard`, `:schedule-crew-dispatch`, and
    `:request-inspection-review` are never auto-eligible at any phase,
    so they always escalate once the governor clears them (or hold if
    the governor doesn't). `:log-progress` MAY auto-commit at phase 3
    when governor-clean and site is registered."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map an Electrical Trade Governor verdict to a base disposition before
  the phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
