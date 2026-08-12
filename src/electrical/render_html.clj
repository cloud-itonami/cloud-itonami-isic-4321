(ns electrical.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave 6): this repo previously had NO demo page and no generator at all.

  Unlike `cloud-itonami-isic-9522`, this repo has **no langgraph wiring
  and no `run-operation` entry point** -- `electrical.sim` open-codes the
  advisor -> governor -> phase -> store pipeline inline in its `println`
  driver, so there was nothing reusable to call. `run-operation!` below
  is therefore the pipeline itself, lifted out of `sim` verbatim in
  shape (`governor/check` -> `phase/verdict->disposition` ->
  `phase/gate` -> real `electrical.store` mutator) and given the
  append-only decision-fact ledger that this repo's `Store` protocol
  does not carry. Proposals come from the real proposal side
  (`electrical.electricaladvisor`, and `electrical.operation` directly
  where a non-default confidence is needed).

  EVERY number, id, disposition and hold reason on the generated page is
  read back out of that live run -- the governor's own violation
  `:rule`/`:detail`, the store's own project records, and the phase
  matrix computed by calling `phase/gate` for real across all four
  phases. Nothing on the page is a hand-typed literal describing
  behaviour; the only hand-written strings are section prose and the
  human-readable op labels.

  Determinism: no timestamps appear in page content, no random or
  nanoTime-derived ids are rendered (this repo's store mints
  `_hazard_<nanoTime>` / `_progress_<nanoTime>` ids, so the page reports
  counts and payloads, never those ids), the store clock is the fixed
  `demo-epoch-ms` constant passed in from here, and all map/set
  iteration is sorted. Two consecutive runs are byte-identical.

  Build-time invariant: `-main` REFUSES to write the file if the run
  produced zero `:governor-hold` facts. A console that shows only happy
  paths would misrepresent this actor, whose entire point is that six
  HARD checks cannot be overridden by a human approver. (Precedent:
  cloud-itonami-isic-2513.)

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin :as skin]
            [electrical.electricaladvisor :as advisor]
            [electrical.facts :as facts]
            [electrical.governor :as governor]
            [electrical.operation :as op]
            [electrical.phase :as phase]
            [electrical.store :as store]))

(def ^:private demo-epoch-ms
  "Fixed store clock for the demo run (2026-01-01T00:00:00Z). Passed in
  from the caller so the store never reads a wall clock -- required for
  byte-identical reruns. Never rendered."
  1767225600000)

(def ^:private approver
  "The human on the other side of every escalation in this scenario."
  "inspector-jp-0042")

;; --------------------------- the actor pipeline ---------------------------

(defn- record! [ledger fact]
  (swap! ledger conj fact)
  fact)

(defn- commit!
  "Apply a cleared proposal to the REAL store. This is the only place
  the actor writes; each branch is the store mutator named by the op."
  [st {:keys [op subject value]}]
  (case op
    :project/intake            (store/create-project! st (assoc value :id subject))
    :log-progress              (store/log-progress! st subject
                                                    (assoc value :timestamp demo-epoch-ms))
    :flag-safety-hazard        (store/flag-hazard! st subject
                                                   (assoc value :timestamp demo-epoch-ms))
    :request-inspection-review (store/request-inspection! st subject
                                                          {:requested-by "electrical-advisor"
                                                           :timestamp demo-epoch-ms})
    :schedule-crew-dispatch    (store/schedule-crew! st subject value)))

(defn- fact-base [phase-num {:keys [op subject value confidence]}]
  ;; Explicit whitelist -- advisor proposals carry a wall-clock
  ;; `:timestamp` that must never reach the ledger or the page.
  {:op op :subject subject :phase phase-num :confidence confidence :value value})

(defn run-operation!
  "One turn of this repo's actor: governor -> phase gate -> store.

  `ctx` is `{:store <Store> :ledger <atom vector> :phase <0..3>}`.
  Appends exactly one decision fact and returns it:

    :governor-hold      one or more HARD governor violations. Terminal --
                        never reaches a human, cannot be approved.
    :phase-hold         governor was clean but the rollout phase does not
                        enable this op yet. Also terminal.
    :approval-requested cleared the governor, not auto-eligible ->
                        escalated to a licensed electrician/inspector.
    :committed          cleared the governor AND auto-eligible in this
                        phase -> written straight to the store."
  [{:keys [store ledger phase]} proposal]
  (let [verdict (governor/check phase (:subject proposal) store proposal)
        gated   (phase/gate phase proposal (phase/verdict->disposition verdict))
        disp    (:disposition gated)
        base    (assoc (fact-base phase proposal) :notes (:notes verdict))]
    (cond
      (and (= :hold disp) (:hard? verdict))
      (record! ledger (assoc base :t :governor-hold :disposition :hold
                             :violations (vec (:violations verdict))))

      (= :hold disp)
      (record! ledger (assoc base :t :phase-hold :disposition :hold
                             :reason (:reason gated)))

      (= :escalate disp)
      (record! ledger (assoc base :t :approval-requested :disposition :escalate
                             :reason (or (:reason gated)
                                         (when (contains? governor/high-stakes (:op proposal))
                                           :high-stakes-gate))))

      :else
      (do (commit! store proposal)
          (record! ledger (assoc base :t :committed :disposition :commit))))))

(defn approve!
  "A licensed electrician/inspector approves an escalated proposal.

  Refuses outright unless the pipeline actually escalated it. That
  refusal is what makes 'a HARD hold never reaches a human' structural
  rather than conventional: held proposals are not in an approvable
  state, so this scenario cannot accidentally launder one."
  [{:keys [store ledger phase]} proposal]
  (let [pending (last (filter #(and (= (:op %) (:op proposal))
                                    (= (:subject %) (:subject proposal)))
                              @ledger))]
    (when-not (= :approval-requested (:t pending))
      (throw (ex-info "not awaiting approval -- a held proposal never reaches a human"
                      {:op (:op proposal) :subject (:subject proposal)
                       :last-fact (:t pending)})))
    (commit! store proposal)
    (record! ledger (assoc (fact-base phase proposal)
                           :t :approval-granted :disposition :commit
                           :approved-by approver
                           :notes (:notes pending)))))

;; ------------------------------- scenario ---------------------------------

(defn run-demo!
  "Drives a fresh `electrical.store/mem-store` through every disposition
  this actor can reach, so the console shows both sides of the gate.

  Clean / approved paths (phase 3, `supervised-auto`):
    proj-tokyo-001   intake auto-commits (governor-clean, `:project/intake`
                     is auto-eligible at phase 3, no capital risk); three
                     `:log-progress` milestones auto-commit; then the three
                     permanently-human ops -- crew dispatch, hazard flag and
                     inspection request -- each escalate and are approved.
    proj-nagoya-006  runs at phase 1 (`assisted-intake`): intake is
                     governor-clean but NOT auto-eligible there, so it
                     escalates and is approved by a human.

  HARD governor holds (never reach a human, cannot be overridden):
    proj-austin-002    intake with a blank electrician license
                       -> :electrician-license-missing
    proj-atlantis-003  intake citing a jurisdiction absent from
                       `electrical.facts/catalog` -> :invalid-jurisdiction
    proj-osaka-005     a safety hazard flagged against a project that was
                       never registered -> :project-not-registered
    proj-berlin-004    registers cleanly, then an inspection request the
                       advisor is only 0.35 confident of -> :low-confidence

  Rollout hold (governor clean, phase not there yet):
    proj-nagoya-006    `:log-progress` at phase 1 -> :phase-disabled

  Returns `{:store .. :ledger <vector of facts>}` -- everything the
  renderer reads."
  []
  (let [st     (store/mem-store)
        ledger (atom [])
        p3     {:store st :ledger ledger :phase 3}
        p1     {:store st :ledger ledger :phase 1}]

    ;; --- proj-tokyo-001: full clean lifecycle at phase 3 ---
    (run-operation! p3 (advisor/propose-project-intake
                        "proj-tokyo-001" :JPN "LE-JP-20240715"
                        "Residential rewiring, 200A service upgrade"
                        "Tokyo, Japan"))
    (run-operation! p3 (advisor/propose-log-progress
                        "proj-tokyo-001" :site-prep
                        "Inspection of existing installation"))
    (run-operation! p3 (advisor/propose-log-progress
                        "proj-tokyo-001" :permits-filed
                        "Submitted to local authority"))
    (run-operation! p3 (advisor/propose-log-progress
                        "proj-tokyo-001" :materials-staged
                        "Conduit and wire staged at site"))

    (let [crew (advisor/propose-crew-dispatch
                "proj-tokyo-001" :panel-assembly "200A panel swap"
                "Two-electrician crew, service disconnect required"
                "2026-01-08 08:00 JST")]
      (run-operation! p3 crew)
      (approve! p3 crew))

    (let [hazard (advisor/propose-hazard-flag
                  "proj-tokyo-001" :overload-risk :high
                  (str "Existing panel load appears to exceed 80% capacity. "
                       "Recommend full load analysis before adding new circuits."))]
      (run-operation! p3 hazard)
      (approve! p3 hazard))

    (let [insp (advisor/propose-inspection-request
                "proj-tokyo-001" :pre-energization
                (str "Verify installation is complete, grounding intact, "
                     "no code violations before energization."))]
      (run-operation! p3 insp)
      (approve! p3 insp))

    ;; --- proj-austin-002: HARD hold, blank electrician license ---
    (run-operation! p3 (advisor/propose-project-intake
                        "proj-austin-002" :USA ""
                        "Commercial fit-out, 3-phase subpanel"
                        "Austin, TX, USA"))

    ;; --- proj-atlantis-003: HARD hold, jurisdiction not in catalog ---
    (run-operation! p3 (advisor/propose-project-intake
                        "proj-atlantis-003" :ATL "XX-0000-0000"
                        "Seafront substation tie-in"
                        "Atlantis"))

    ;; --- proj-osaka-005: HARD hold, hazard flagged before intake ---
    (run-operation! p3 (advisor/propose-hazard-flag
                        "proj-osaka-005" :arc-flash-risk :critical
                        "Exposed busbar reported at the service entrance."))

    ;; --- proj-berlin-004: clean intake, then a low-confidence request ---
    (run-operation! p3 (advisor/propose-project-intake
                        "proj-berlin-004" :DEU "VDE-DE-2025-0413"
                        "Warehouse lighting retrofit, DIN VDE 0100 scope"
                        "Berlin, Germany"))
    ;; `advisor/propose-inspection-request` hard-codes confidence 0.85, so
    ;; the raw operation constructor is used to express advisor doubt.
    (run-operation! p3 (op/request-inspection-review
                        "proj-berlin-004" :post-installation
                        "Unclear whether the retrofit scope triggers a formal review."
                        :confidence 0.35))

    ;; --- proj-nagoya-006: phase-1 rollout -- approval, then a phase hold ---
    (let [intake (advisor/propose-project-intake
                  "proj-nagoya-006" :JPN "LE-JP-20251102"
                  "Factory floor circuit extension"
                  "Nagoya, Japan")]
      (run-operation! p1 intake)
      (approve! p1 intake))
    (run-operation! p1 (advisor/propose-log-progress
                        "proj-nagoya-006" :site-prep
                        "Walkthrough with the plant electrician"))

    {:store st :ledger @ledger}))

;; ------------------------------- rendering --------------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw [v] (esc (if (keyword? v) (name v) v)))

(defn- edn [m]
  ;; sorted so map iteration order can never move between runs
  (esc (pr-str (into (sorted-map) m))))

(def ^:private disposition-cell
  {:governor-hold      "<span class=\"critical\">HARD hold</span>"
   :phase-hold         "<span class=\"err\">phase hold</span>"
   :approval-requested "<span class=\"warn\">awaiting human</span>"
   :approval-granted   "<span class=\"ok\">approved &amp; committed</span>"
   :committed          "<span class=\"ok\">auto-committed</span>"})

(defn- fact-status [f]
  (get disposition-cell (:t f) "<span class=\"muted\">in progress</span>"))

(defn- last-fact-for [ledger subject]
  (last (filter #(= (:subject %) subject) ledger)))

(def ^:private op-labels
  "Human-readable gloss for each op in this actor's closed contract.
  Prose only -- the gate columns beside them are computed by calling
  `electrical.phase/gate` for real."
  {:project/intake            "Register an electrical installation project"
   :log-progress              "Record an installation milestone"
   :schedule-crew-dispatch    "Propose an electrician crew dispatch"
   :flag-safety-hazard        "Surface an electrical safety hazard"
   :request-inspection-review "Propose review by a licensed inspector"})

(defn- gate-cell
  "Ask the REAL phase gate what happens to a governor-clean proposal for
  `op` at `phase-num`. Every cell in the rollout matrix is this call."
  [phase-num op]
  (let [{:keys [disposition reason]} (phase/gate phase-num {:op op} :commit)]
    (case disposition
      :commit   "<span class=\"ok\">auto-commit</span>"
      :escalate (str "<span class=\"warn\">human approval</span>"
                     (when reason (str " <span class=\"muted\">&middot; " (kw reason) "</span>")))
      :hold     (str "<span class=\"err\">blocked</span>"
                     (when reason (str " <span class=\"muted\">&middot; " (kw reason) "</span>")))
      (kw disposition))))

(defn- gate-row [phase-nums op]
  (str "        <tr><td><code>" (esc (str op)) "</code></td><td>"
       (esc (get op-labels op ""))
       "</td>"
       (str/join "" (map #(str "<td>" (gate-cell % op) "</td>") phase-nums))
       "<td>"
       (if (contains? governor/high-stakes op)
         "<span class=\"critical\">always</span>"
         "<span class=\"muted\">no</span>")
       "</td></tr>"))

(defn- project-row [ledger p]
  (let [{:keys [id jurisdiction electrician-license scope-description site-address
                hazard-flags progress-records crew-dispatches inspection-scheduled?]} p
        f (last-fact-for ledger id)]
    (str "        <tr><td><code>" (esc id) "</code></td>"
         "<td>" (kw jurisdiction) "</td>"
         "<td><code>" (esc electrician-license) "</code></td>"
         "<td>" (esc scope-description) "<br><span class=\"muted\">" (esc site-address) "</span></td>"
         "<td class=\"num\">" (count hazard-flags) "</td>"
         "<td class=\"num\">" (count progress-records) "</td>"
         "<td class=\"num\">" (count crew-dispatches) "</td>"
         "<td>" (if inspection-scheduled?
                  "<span class=\"ok\">scheduled</span>"
                  "<span class=\"muted\">not requested</span>") "</td>"
         "<td>" (fact-status f) " <span class=\"muted\">&middot; "
         (kw (:op f)) "</span></td></tr>")))

(defn- hold-row [f]
  (str "        <tr><td><code>" (esc (:subject f)) "</code></td>"
       "<td><code>" (esc (str (:op f))) "</code></td>"
       "<td class=\"num\">" (esc (:phase f)) "</td>"
       "<td>" (fact-status f) "</td>"
       "<td>"
       (if (= :governor-hold (:t f))
         (str/join "<br>"
                   (map (fn [v]
                          (str "<code>" (kw (:rule v)) "</code> &middot; " (esc (:detail v))))
                        (:violations f)))
         (str "<code>" (kw (:reason f)) "</code> &middot; "
              "rollout phase " (esc (:phase f)) " does not enable this op"))
       "</td></tr>"))

(defn- hazard-row [p h]
  (str "        <tr><td><code>" (esc (:id p)) "</code></td>"
       "<td><code>" (kw (:type h)) "</code></td>"
       "<td><span class=\"critical\">" (kw (:severity h)) "</span></td>"
       "<td>" (esc (:description h)) "</td>"
       "<td>" (if (:escalated? h)
                "<span class=\"ok\">escalated to human</span>"
                "<span class=\"err\">not escalated</span>") "</td></tr>"))

(defn- basis-row [jur]
  (let [b (facts/legal-basis-of jur)]
    (str "        <tr><td>" (kw jur) "</td>"
         "<td>" (if b (esc (:basis-name b))
                    "<span class=\"critical\">not in catalog &middot; intake blocked</span>") "</td>"
         "<td>" (esc (str/join ", " (:authorities b))) "</td>"
         "<td><code>" (kw (facts/threshold-model jur)) "</code></td>"
         "<td>" (str/join "<br>" (map esc (:cites b))) "</td></tr>")))

(defn- ledger-row [i f]
  (str "        <tr><td class=\"num\">" i "</td>"
       "<td>" (fact-status f) "</td>"
       "<td><code>" (esc (str (:op f))) "</code></td>"
       "<td><code>" (esc (:subject f)) "</code></td>"
       "<td class=\"num\">" (esc (:phase f)) "</td>"
       "<td class=\"num\">" (esc (format "%.2f" (double (:confidence f 1.0)))) "</td>"
       "<td>" (esc (:notes f)) "</td>"
       "<td><code>" (edn (:value f)) "</code></td></tr>"))

(defn render
  "Render the whole operator console from the result of `run-demo!`."
  [{:keys [store ledger]}]
  (let [projects   (sort-by :id (store/all-projects store))
        phase-nums (sort (keys phase/phases))
        ops        (sort-by str phase/write-ops)
        holds      (filter #(#{:governor-hold :phase-hold} (:t %)) ledger)
        gov-holds  (filter #(= :governor-hold (:t %)) ledger)
        approvals  (filter #(= :approval-granted (:t %)) ledger)
        autos      (filter #(= :committed (:t %)) ledger)
        hazards    (for [p projects h (:hazard-flags p)] [p h])
        jurs       (->> ledger
                        (filter #(= :project/intake (:op %)))
                        (map #(-> % :value :jurisdiction))
                        distinct
                        (sort-by name))]
    (str
     "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
     "<title>cloud-itonami-isic-4321 &middot; electrical installation &mdash; Operator Console</title>\n"
     "<style>\n" (skin/dds+skin) "\n</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Electrical installation (ISIC 4321) &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; propose-only actor &middot; governor-gated</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"banner\">\n"
     "    <p>This actor <strong>never performs electrical work</strong>, never energizes an installation and never"
     " certifies code compliance. It proposes coordination only; a licensed electrician or inspector decides.</p>\n"
     "    <p class=\"muted\">Build-time snapshot generated from <code>electrical.store</code> by"
     " <code>electrical.render-html</code> (<code>clojure -M:dev:render-html</code>). Every figure below is read back"
     " out of a live advisor &rarr; governor &rarr; phase-gate &rarr; store run &mdash; nothing on this page is a"
     " hand-written literal. Deterministic: no timestamps, no minted ids, byte-identical across reruns.</p>\n"
     "    <p><span class=\"ok\">" (count autos) " auto-committed</span> &middot; "
     "<span class=\"ok\">" (count approvals) " approved by a human</span> &middot; "
     "<span class=\"critical\">" (count gov-holds) " HARD governor holds</span> &middot; "
     "<span class=\"err\">" (- (count holds) (count gov-holds)) " rollout holds</span> &middot; "
     "<span class=\"muted\">" (count ledger) " ledger facts</span></p>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Registered projects</h2>\n"
     "    <p class=\"muted\">Only projects whose <code>:project/intake</code> actually cleared the governor exist in"
     " the store. Blocked intakes never became projects &mdash; see the next section.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Project</th><th>Jurisdiction</th><th>Electrician licence</th><th>Scope / site</th>"
     "<th>Hazards</th><th>Progress</th><th>Crew</th><th>Inspection</th><th>Last decision</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial project-row ledger) projects)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Blocked proposals (" (count holds) ")</h2>\n"
     "    <p class=\"muted\">A HARD governor hold is terminal: it is never surfaced for approval, and no human"
     " approver can override it. Rules and details below are the governor's own"
     " <code>:rule</code>/<code>:detail</code> output. A rollout hold means the governor was clean but the"
     " staged-rollout phase does not enable that op yet.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Project</th><th>Op</th><th>Phase</th><th>Disposition</th><th>Reason</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map hold-row holds)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Rollout &amp; action gate</h2>\n"
     "    <p class=\"muted\">Each cell is the result of calling <code>electrical.phase/gate</code> with a"
     " governor-clean proposal at that phase &mdash; computed, not described. Hazard flagging, crew dispatch and"
     " inspection requests are absent from every phase's auto set by design, so they escalate at every phase"
     " including phase 3.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Meaning</th>"
     (str/join "" (map #(str "<th>Phase " % "<br><span class=\"muted\">"
                             (esc (:label (get phase/phases %))) "</span></th>")
                       phase-nums))
     "<th>Always human?</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial gate-row phase-nums) ops)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Hazard flags on record (" (count hazards) ")</h2>\n"
     "    <p class=\"muted\">Flagged hazards are permanent, always escalated records. The store mints a"
     " <code>nanoTime</code>-derived id for each, which is deliberately not rendered here so this page stays"
     " byte-identical across reruns.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Project</th><th>Type</th><th>Severity</th><th>Description</th><th>Escalation</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial apply hazard-row) hazards)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Legal basis of the jurisdictions in this run</h2>\n"
     "    <p class=\"muted\">Read from <code>electrical.facts/catalog</code> for every jurisdiction an intake in"
     " this run cited. Electrical risk assessment is <code>:qualitative</code> everywhere: there is no numeric"
     " bright line an actor could compute its way past.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Jurisdiction</th><th>Basis</th><th>Authorities</th><th>Risk model</th><th>Cited regulation</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map basis-row jurs)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log &mdash; every proposal, hold, escalation, approval and"
     " commit the scenario produced, in order. <code>Governor notes</code> is the governor's own verdict string.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>#</th><th>Fact</th><th>Op</th><th>Project</th><th>Phase</th><th>Conf.</th>"
     "<th>Governor notes</th><th>Payload</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map-indexed (fn [i f] (ledger-row (inc i) f)) ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-4321 &middot; electrical installation coordination actor &middot; generated by"
     " <code>electrical.render-html</code>. Regenerate with <code>clojure -M:dev:render-html</code>.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out    (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        ledger (:ledger result)
        gov    (filter #(= :governor-hold (:t %)) ledger)
        appr   (filter #(= :approval-granted (:t %)) ledger)]
    ;; Build-time invariant, not a convention: a console that shows only
    ;; happy paths would misrepresent an actor whose entire purpose is a
    ;; set of HARD checks no human can override.
    (when (zero? (count gov))
      (throw (ex-info (str "refusing to write " out
                           " -- the scenario produced ZERO :governor-hold facts. "
                           "The operator console must demonstrate at least one HARD hold "
                           "that never reaches a human. Fix the scenario in run-demo!, "
                           "not this check.")
                      {:out out
                       :ledger-facts (count ledger)
                       :governor-holds 0
                       :fact-kinds (frequencies (map :t ledger))})))
    (let [html (render result)]
      (spit out html)
      (println "wrote" out
               (str "(" (count ledger) " ledger facts, "
                    (count gov) " HARD governor holds, "
                    (count appr) " human approvals, "
                    (count (store/all-projects (:store result))) " registered projects, "
                    (count html) " bytes)")))))
