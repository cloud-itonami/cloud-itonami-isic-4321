(ns electrical.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Drives the REAL actor stack of this repo -- the Electrical Trade
  Advisor (`electrical.electricaladvisor`) proposing operation shapes
  (`electrical.operation`), the independent Electrical Trade Governor
  (`electrical.governor/check`), the rollout phase gate
  (`electrical.phase/gate`) and the append-only store
  (`electrical.store`) -- and renders whatever those produced.

  This repo has no langgraph dependency and no `run-operation` entry
  point: its own demo driver `electrical.sim` (`clojure -M:dev:run`,
  run BEFORE writing this file) wires advisor -> governor/check ->
  phase/verdict->disposition -> phase/gate -> store write inline. The
  `step!` function below is that same loop, factored so each step's
  outcome can be appended to an audit ledger. Every ledger fact's type
  is computed from the governor verdict and the phase gate -- no fact
  is ever hand-appended.

  Subject provenance: `electrical.store/mem-store` is EMPTY on
  construction -- this repo ships no seed data, so (exactly as
  `electrical.sim` does) every project id driven below is created by
  an `:project/intake` op inside this demo itself. Ids that never get
  registered (because their intake HARD-held) are deliberately kept in
  the scenario: the follow-up op against them is what exercises the
  governor's `:project-not-registered` rule for real.

  Nothing on the page is written by hand except the labelled static
  contract note in `contract-note` below; every table row, count and
  status is read back out of the store or off the ledger, and every
  HARD-hold rule name and detail string is the governor's own
  `:violations` entry.

  Deterministic: no clock, no randomness, no network, no timestamps in
  page content. The store stamps `System/nanoTime` into record ids and
  carries whatever `:timestamp` it is handed; this renderer passes a
  fixed sentinel and never renders record ids or timestamps, so
  re-running writes a byte-identical file.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [electrical.store :as store]
            [electrical.operation :as op]
            [electrical.phase :as phase]
            [electrical.facts :as facts]
            [electrical.governor :as governor]
            [electrical.electricaladvisor :as advisor]))

(def ^:private demo-phase
  "Rollout phase this console demonstrates -- the repo's own default."
  phase/default-phase)

(def ^:private fixed-ts
  "Fixed sentinel handed to every store write in place of a clock, so
  the run is deterministic. Never rendered."
  :build-time)

;; ------------------------- the actor loop ----------------------------

(defn- commit!
  "Apply a cleared proposal to the store using this repo's own Store
  protocol. Dispatches on the operation's `:op`, exactly as
  `electrical.sim` does."
  [st {:keys [op subject value]}]
  (case op
    :project/intake
    (store/create-project! st {:id subject
                               :jurisdiction (:jurisdiction value)
                               :electrician-license (:electrician-license value)
                               :scope-description (:scope-description value)
                               :site-address (:site-address value)})

    :log-progress
    (store/log-progress! st subject {:milestone (:milestone value)
                                     :description (:description value)
                                     :timestamp fixed-ts})

    :schedule-crew-dispatch
    (store/schedule-crew! st subject {:crew-type (:crew-type value)
                                      :task (:task value)
                                      :description (:description value)
                                      :scheduled-time (:scheduled-time value)})

    :flag-safety-hazard
    (store/flag-hazard! st subject {:hazard-type (:hazard-type value)
                                    :severity (:severity value)
                                    :description (:description value)
                                    :timestamp fixed-ts})

    :request-inspection-review
    (store/request-inspection! st subject {:requested-by "advisor"
                                           :timestamp fixed-ts})))

(defn- step!
  "One turn of the real actor loop: governor check -> phase gate ->
  (optional human approval) -> store write. Appends the resulting
  decision facts to `ledger` and returns nothing useful -- everything
  the console shows is read back afterwards.

  `approver` nil means no human is available this run: an escalated
  proposal then stays `:approval-requested` (pending) and is NOT
  committed."
  [st ledger proposal approver]
  (let [{:keys [op subject]} proposal
        verdict (governor/check demo-phase subject st proposal)
        gated (phase/gate demo-phase proposal (phase/verdict->disposition verdict))
        disposition (:disposition gated)
        base {:op op :subject subject :confidence (:confidence proposal)}]
    (cond
      ;; HARD governor violation -- never reaches a human, never commits.
      (and (= :hold disposition) (:hard? verdict))
      (swap! ledger conj
             (assoc base :t :governor-hold
                    :violations (vec (:violations verdict))
                    :basis (mapv :rule (:violations verdict))))

      ;; Held by the rollout phase gate rather than the governor.
      (= :hold disposition)
      (swap! ledger conj
             (assoc base :t :phase-hold :basis [(or (:reason gated) :phase-hold)]))

      ;; Governor clear but not auto-eligible -> human decision point.
      (= :escalate disposition)
      (do (swap! ledger conj
                 (assoc base :t :approval-requested
                        :basis [(if (contains? governor/high-stakes op)
                                  :high-stakes-gate
                                  (or (:reason gated) :phase-approval))]))
          (when approver
            (swap! ledger conj (assoc base :t :approval-granted :approved-by approver))
            (commit! st proposal)
            (swap! ledger conj (assoc base :t :committed :basis [:human-approved]))))

      ;; Governor clean and auto-eligible at this phase.
      :else
      (do (commit! st proposal)
          (swap! ledger conj
                 (assoc base :t :committed :basis [:governor-clean :phase-auto]))))))

(defn run-demo!
  "Runs a fresh (empty) store through a scenario that reaches every
  disposition this actor can produce, and genuinely violates each of
  the Electrical Trade Governor's HARD rules:

    tokyo-001   full clean lifecycle -- JPN intake with a verified
                electrician license auto-commits at phase 3, three
                progress milestones auto-commit, then a crew dispatch,
                a hazard flag and an inspection request each escalate
                (permanently never auto at any phase) and are approved
                by a named human.
    osaka-002   intake cites jurisdiction :FRA, which is NOT in
                `electrical.facts/catalog` -> HARD hold
                `:invalid-jurisdiction`; the follow-up progress log
                against that never-registered project then HARD-holds
                on `:project-not-registered`.
    berlin-003  intake with an empty electrician license -> HARD hold
                `:electrician-license-missing`.
    newyork-004 clean USA intake commits, then a low-certainty hazard
                reading (confidence 0.42, under
                `electrical.governor/confidence-floor`) HARD-holds on
                `:low-confidence`; its inspection request is left
                pending with no approver, to show the awaiting-approval
                state.

  Returns `{:st store :ledger [facts]}`."
  []
  (let [st (store/mem-store)
        ledger (atom [])
        run! (fn ([p] (step! st ledger p nil))
               ([p approver] (step! st ledger p approver)))]

    ;; --- tokyo-001: clean lifecycle -------------------------------
    (run! (advisor/propose-project-intake
           "proj-tokyo-001" :JPN "LE-JP-20240715"
           "Residential rewiring, 200A service upgrade" "Tokyo, Japan"))
    (run! (advisor/propose-log-progress
           "proj-tokyo-001" :site-prep "Inspection of existing installation"))
    (run! (advisor/propose-log-progress
           "proj-tokyo-001" :permits-filed "Submitted to local authority"))
    (run! (advisor/propose-log-progress
           "proj-tokyo-001" :materials-staged "Conduit and wire staged at site"))
    (run! (advisor/propose-crew-dispatch
           "proj-tokyo-001" :conduit-installation "Run conduit for new sub-panel"
           "Two-electrician crew, existing service left de-energized"
           "day-3 morning slot")
          "electrician-tanaka")
    (run! (advisor/propose-hazard-flag
           "proj-tokyo-001" :overload-risk :high
           (str "Existing panel load appears to exceed 80% capacity. "
                "Recommend full load analysis before adding new circuits."))
          "inspector-yamada")
    (run! (advisor/propose-inspection-request
           "proj-tokyo-001" :pre-energization
           (str "Verify installation is complete, grounding intact, "
                "no code violations before energization."))
          "inspector-yamada")

    ;; --- osaka-002: unknown jurisdiction, then orphaned follow-up --
    (run! (advisor/propose-project-intake
           "proj-osaka-002" :FRA "LE-FR-99887"
           "Retail fit-out, three-phase distribution board" "Osaka, Japan"))
    (run! (advisor/propose-log-progress
           "proj-osaka-002" :site-prep "Survey of existing distribution board"))

    ;; --- berlin-003: no electrician license -----------------------
    (run! (advisor/propose-project-intake
           "proj-berlin-003" :DEU ""
           "Workshop sub-distribution, DIN VDE 0100 scope" "Berlin, Germany"))

    ;; --- newyork-004: clean intake, low-certainty hazard reading ---
    (run! (advisor/propose-project-intake
           "proj-newyork-004" :USA "NY-ME-44120"
           "Commercial tenant fit-out, NEC Article 110 scope" "New York, USA"))
    ;; Built through `electrical.operation` directly rather than the
    ;; advisor's fixed-confidence wrapper, because the point of this
    ;; step is an advisor reading whose own confidence is under the
    ;; governor's floor.
    (run! (op/flag-safety-hazard
           "proj-newyork-004" :arc-flash-risk :critical
           (str "Possible arc-flash exposure at the existing switchgear; "
                "label data is illegible and incident energy is unverified.")
           :confidence 0.42))
    (run! (advisor/propose-inspection-request
           "proj-newyork-004" :post-installation
           "Confirm terminations and labelling before the tenant takes occupancy."))

    {:st st :ledger @ledger}))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-name [v] (if (keyword? v) (name v) (str v)))

(defn- joined [xs] (str/join ", " (map kw-name xs)))

(defn- holds
  "Every HARD governor hold on the ledger."
  [ledger]
  (filterv #(= :governor-hold (:t %)) ledger))

(defn- ledger-subjects
  "Every subject this run touched, in first-appearance order -- derived
  from the ledger, so held-at-intake projects (which never reach the
  store) are still listed."
  [ledger]
  (vec (distinct (map :subject ledger))))

(defn- last-fact-for [ledger subject]
  (last (filter #(= (:subject %) subject) ledger)))

(defn- status-cell [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (case (:t f)
      :committed "<span class=\"ok\">committed</span>"
      :approval-granted "<span class=\"ok\">approved</span>"
      :approval-requested "<span class=\"warn\">awaiting human approval</span>"
      :phase-hold (str "<span class=\"warn\">phase hold &middot; "
                       (esc (joined (:basis f))) "</span>")
      :governor-hold (str "<span class=\"critical\">HARD hold &middot; "
                          (esc (joined (:basis f))) "</span>")
      "<span class=\"muted\">no activity</span>")))

(defn- subject-row [ledger st subject]
  (let [p (store/project-by-id st subject)
        basis (some-> (:jurisdiction p) facts/legal-basis-of :basis-name)]
    (str "        <tr><td><code>" (esc subject) "</code></td>"
         "<td>" (if p (esc (kw-name (:jurisdiction p)))
                    "<span class=\"muted\">&mdash;</span>") "</td>"
         "<td>" (if basis (esc basis) "<span class=\"muted\">not registered</span>") "</td>"
         "<td>" (if p (esc (:electrician-license p))
                    "<span class=\"muted\">&mdash;</span>") "</td>"
         "<td>" (if p (count (:progress-records p)) 0) "</td>"
         "<td>" (if p (count (:hazard-flags p)) 0) "</td>"
         "<td>" (cond (nil? p) "<span class=\"muted\">not registered</span>"
                      (:inspection-scheduled? p) "<span class=\"ok\">scheduled</span>"
                      :else "<span class=\"muted\">not requested</span>") "</td>"
         "<td>" (status-cell ledger subject) "</td></tr>")))

(defn- hold-row [{:keys [op subject violations]}]
  (str "        <tr><td><code>" (esc (kw-name op)) "</code></td>"
       "<td><code>" (esc subject) "</code></td>"
       "<td><span class=\"critical\">" (esc (joined (map :rule violations))) "</span></td>"
       "<td>" (esc (str/join " " (map :detail violations))) "</td></tr>"))

(defn- ledger-row [{:keys [t op subject basis approved-by]}]
  (str "        <tr><td>" (esc (kw-name t)) "</td>"
       "<td><code>" (esc (kw-name op)) "</code></td>"
       "<td><code>" (esc subject) "</code></td>"
       "<td>" (esc (cond approved-by (str "approved by " approved-by)
                         (seq basis) (joined basis)
                         :else "")) "</td></tr>"))

(defn- gate-row
  "Gate posture for one op, derived from `electrical.phase/phases` and
  `electrical.governor/high-stakes` -- not hand-typed."
  [op]
  (let [auto? (contains? (get-in phase/phases [demo-phase :auto]) op)
        write? (contains? (get-in phase/phases [demo-phase :writes]) op)
        stakes? (contains? governor/high-stakes op)]
    (str "        <tr><td><code>" (esc (kw-name op)) "</code></td>"
         "<td>propose</td>"
         "<td>"
         (cond
           (not write?) "<span class=\"critical\">not enabled at this phase</span>"
           auto? (str "<span class=\"ok\">auto-commit at phase " demo-phase
                      " when governor-clean</span>")
           stakes? "<span class=\"warn\">ALWAYS human approval &middot; never auto at any phase</span>"
           :else "<span class=\"warn\">human approval required</span>")
         "</td></tr>")))

(defn- basis-row [[jur {:keys [basis-name authorities threshold-model]}]]
  (str "        <tr><td><code>" (esc (kw-name jur)) "</code></td>"
       "<td>" (esc basis-name) "</td>"
       "<td>" (esc (str/join ", " authorities)) "</td>"
       "<td><code>" (esc (kw-name threshold-model)) "</code></td></tr>"))

(def ^:private contract-note
  ;; STATIC hand-written description of this actor's fixed op-gate
  ;; contract (README `Core Contract`, `electrical.phase` docstring,
  ;; `electrical.governor` docstring). This is documentation of fixed
  ;; behaviour, not runtime telemetry -- it is the only hand-written
  ;; content on the page. Every other cell is derived from the run.
  (str "Every operation this actor emits has <code>:effect :propose</code>: it coordinates, "
       "it never performs electrical work, energizes an installation, or certifies code "
       "compliance. All governor violations are HARD &mdash; a human approver cannot override "
       "them. Hazard flagging, crew dispatch and inspection requests are deliberately absent "
       "from every phase&rsquo;s auto set, including the highest, and always escalate to a "
       "licensed electrician or inspector."))

(defn render
  "Renders the operator console from a completed `run-demo!` result.
  Every row, number and status below comes from the store or the
  ledger that run produced."
  [{:keys [st ledger]}]
  (let [hs (holds ledger)
        subject-rows (str/join "\n" (map #(subject-row ledger st %) (ledger-subjects ledger)))
        hold-rows (str/join "\n" (map hold-row hs))
        ledger-rows (str/join "\n" (map ledger-row ledger))
        gate-rows (str/join "\n" (map gate-row (sort-by kw-name phase/write-ops)))
        basis-rows (str/join "\n" (map basis-row (sort-by (comp kw-name key) facts/catalog)))
        registered (count (store/all-projects st))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-4321 &middot; electrical-installation</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Electrical installation (ISIC 4321) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · propose-only · hazard/dispatch/inspection always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Installation projects</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated by <code>electrical.render-html</code> (<code>clojure -M:dev:render-html</code>) by driving <code>electrical.electricaladvisor</code> → <code>electrical.governor</code> → <code>electrical.phase</code> → <code>electrical.store</code>. "
     (esc (str registered)) " of " (esc (str (count (ledger-subjects ledger))))
     " subjects reached the store; the rest were stopped by the governor before registration.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Project</th><th>Jurisdiction</th><th>Legal basis</th><th>Electrician license</th><th>Progress records</th><th>Hazard flags</th><th>Inspection</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     subject-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>HARD holds (Electrical Trade Governor)</h2>\n"
     "    <p class=\"muted\">" (esc (str (count hs)))
     " proposal(s) were stopped outright this run. A HARD hold never reaches a human approver and never writes to the store — the rule name and the detail text below are the governor&rsquo;s own <code>:violations</code> output.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Project</th><th>Rule</th><th>Governor detail</th></tr></thead>\n"
     "      <tbody>\n"
     hold-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Op gate (phase " (esc (str demo-phase)) " &middot; "
     (esc (str (get-in phase/phases [demo-phase :label]))) ")</h2>\n"
     "    <p class=\"muted\">" contract-note " Confidence floor: <code>"
     (esc (str governor/confidence-floor)) "</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Effect</th><th>Gate at this phase</th></tr></thead>\n"
     "      <tbody>\n"
     gate-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Legal basis catalog</h2>\n"
     "    <p class=\"muted\">Jurisdictions the governor will accept at intake — an intake citing anything else is a HARD hold. Sourced data from <code>electrical.facts/catalog</code>.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Jurisdiction</th><th>Basis</th><th>Authorities</th><th>Risk model</th></tr></thead>\n"
     "      <tbody>\n"
     basis-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold, approval and commit this scenario produced, in order ("
     (esc (str (count ledger))) " facts).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Project</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [ledger] :as result} (run-demo!)
        hs (holds ledger)]
    ;; A console that shows no real HARD hold is not evidence of a governor.
    (when (empty? hs)
      (throw (ex-info "no :governor-hold fact on the ledger — refusing to write a console that shows no real hold"
                      {:ledger-facts (count ledger)
                       :fact-types (vec (distinct (map :t ledger)))})))
    (let [f (java.io.File. ^String out)]
      (when-let [p (.getParentFile f)] (.mkdirs p))
      (spit f (render result)))
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, "
                  (count hs) " HARD holds, "
                  (count (distinct (map :rule (mapcat :violations hs)))) " distinct rules violated)"))))
