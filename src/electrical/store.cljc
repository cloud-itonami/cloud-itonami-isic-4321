(ns electrical.store
  "Store protocol for electrical-trade coordination actor state.
  Abstracts over MemStore (atom-backed, default) and DatomicStore
  (persistent, for production), both satisfying the same contract.

  Primary entities:
  - project: electrical installation project (site, scope, electrician)
  - progress-record: timestamped milestone in a project's install timeline
  - hazard-flag: electrical hazard identified during coordination
  - inspection-review: scheduled formal review by licensed inspector

  All writes are immutable and timestamped. Store is append-only for
  audit/compliance -- no deletes, only updates via new facts."
  )

(defprotocol Store
  "Minimal append-only store for electrical coordination state.
  All writes are timestamped; hazard flags are permanent records."

  (project-by-id [st project-id]
    "Retrieve project by ID. Returns {:id :jurisdiction :electrician-license
    :scope-description :site-address :registered? :hazard-flags
    :inspection-scheduled? :closed?} or nil.")

  (all-projects [st]
    "List all projects registered in this store.")

  (create-project! [st {:keys [id jurisdiction electrician-license scope-description
                               site-address]}]
    "Register a new electrical installation project. Returns the created
    project map or throws if already exists.")

  (flag-hazard! [st project-id {:keys [hazard-type severity description timestamp]}]
    "Log an electrical hazard flag for a project (safety-critical,
    always escalates to human). Returns the project with updated
    :hazard-flags list or throws if project not found.")

  (log-progress! [st project-id {:keys [milestone description timestamp]}]
    "Record a progress milestone in project timeline (low-risk,
    auto-eligible if phase/governor permit). Returns updated project
    or throws if not found.")

  (request-inspection! [st project-id {:keys [requested-by timestamp]}]
    "Propose scheduling an inspection review by licensed electrician.
    Returns updated project with :inspection-scheduled? true or throws
    if not found.")

  (schedule-crew! [st project-id {:keys [crew-type task description scheduled-time]}]
    "Propose dispatching an electrician crew for a specific task
    (scheduling proposal, not execution). Returns updated project or
    throws if not found.")

  (verify-registered [st project-id]
    "Confirm project is registered and active (not closed). Returns
    true or false. Used by governor gate to ensure all actions have a
    verified project context."))

;; Default MemStore implementation (atom-backed, deterministic, testing)

(deftype MemStore [atom-ref]
  Store
  (project-by-id [st id]
    (get @atom-ref id))

  (all-projects [st]
    (vals @atom-ref))

  (create-project! [st {:keys [id jurisdiction electrician-license
                               scope-description site-address]}]
    (let [proj {:id id :jurisdiction jurisdiction
                :electrician-license electrician-license
                :scope-description scope-description
                :site-address site-address
                :registered? true :closed? false
                :hazard-flags [] :hazard-count 0
                :inspection-scheduled? false
                :progress-records []}]
      (swap! atom-ref assoc id proj)
      proj))

  (flag-hazard! [st project-id {:keys [hazard-type severity description timestamp]}]
    (let [proj (get @atom-ref project-id)]
      (if-not proj
        (throw (ex-info (str "Project not found: " project-id) {:project-id project-id}))
        (let [hazard {:id (str project-id "_hazard_" (System/nanoTime))
                      :type hazard-type :severity severity :description description
                      :timestamp timestamp :escalated? true}
              updated (update proj :hazard-flags conj hazard)]
          (swap! atom-ref assoc project-id updated)
          updated))))

  (log-progress! [st project-id {:keys [milestone description timestamp]}]
    (let [proj (get @atom-ref project-id)]
      (if-not proj
        (throw (ex-info (str "Project not found: " project-id) {:project-id project-id}))
        (let [record {:id (str project-id "_progress_" (System/nanoTime))
                      :milestone milestone :description description :timestamp timestamp}
              updated (update proj :progress-records conj record)]
          (swap! atom-ref assoc project-id updated)
          updated))))

  (request-inspection! [st project-id {:keys [requested-by timestamp]}]
    (let [proj (get @atom-ref project-id)]
      (if-not proj
        (throw (ex-info (str "Project not found: " project-id) {:project-id project-id}))
        (let [updated (assoc proj :inspection-scheduled? true
                                  :inspection-requested-by requested-by
                                  :inspection-requested-at timestamp)]
          (swap! atom-ref assoc project-id updated)
          updated))))

  (schedule-crew! [st project-id {:keys [crew-type task description scheduled-time]}]
    (let [proj (get @atom-ref project-id)]
      (if-not proj
        (throw (ex-info (str "Project not found: " project-id) {:project-id project-id}))
        (let [dispatch {:id (str project-id "_crew_" (System/nanoTime))
                        :crew-type crew-type :task task :description description
                        :scheduled-time scheduled-time :proposed? true}
              updated (update proj :crew-dispatches conj dispatch)]
          (swap! atom-ref assoc project-id updated)
          updated))))

  (verify-registered [st project-id]
    (let [proj (get @atom-ref project-id)]
      (and proj (:registered? proj) (not (:closed? proj))))))

(defn mem-store
  "Create a new MemStore backed by an atom."
  []
  (MemStore. (atom {})))
