(ns electrical.governor
  "Electrical Trade Governor -- the independent compliance layer that
  gates proposals from the Electrical Trade Advisor. This actor NEVER
  performs electrical installation work itself, never energizes
  installations, and never certifies code compliance. Instead, it
  proposes coordination (scheduling, hazard logging, inspection
  requests) and escalates all real electrical/safety decisions to
  licensed electricians and inspectors.

  Six checks, ALL HARD violations: a human approver CANNOT override them.
  Additional constraint: all operations are `:effect :propose` only.

    1. Project not registered     -- `:project/intake` must have been
                                     committed before any other op.
    2. Invalid jurisdiction       -- `:project/intake` must cite a
                                     jurisdiction in catalog.
    3. Electrician license missing -- `:project/intake` must name
                                     a verified electrician license.
    4. Work-execution proposal     -- any proposal that attempts to
                                     perform electrical work directly
                                     is a HARD block.
    5. Energization proposal       -- any proposal to energize without
                                     inspection is a HARD block.
    6. Confidence floor /
       high-stakes gate           -- low confidence or high-stakes ops
                                     escalate to human."
  (:require [electrical.facts :as facts]
            [electrical.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Operations that always require human decision."
  #{:flag-safety-hazard :schedule-crew-dispatch :request-inspection-review})

;; ---- checks ----

(defn- project-not-registered-violations
  "Project must be registered before any operation."
  [proposal st]
  (let [{:keys [op subject]} proposal]
    (when (not (= op :project/intake))
      (when-not (store/verify-registered st subject)
        [{:rule :project-not-registered
          :detail (str "Project " subject " not registered or closed.")}]))))

(defn- invalid-jurisdiction-violations
  "Jurisdiction must be in catalog."
  [proposal]
  (let [{:keys [op value]} proposal]
    (when (= op :project/intake)
      (let [jur (:jurisdiction value)]
        (when-not (facts/jurisdiction-known? jur)
          [{:rule :invalid-jurisdiction
            :detail (str "Jurisdiction not in catalog: " jur)}])))))

(defn- electrician-license-missing-violations
  "License must be provided at intake."
  [proposal]
  (let [{:keys [op value]} proposal]
    (when (= op :project/intake)
      (let [lic (:electrician-license value)]
        (when (or (nil? lic) (empty? (str lic)))
          [{:rule :electrician-license-missing
            :detail "Electrician license required at intake."}])))))

(defn- confidence-floor-violations
  "Low confidence or high-stakes ops escalate."
  [proposal]
  (let [{:keys [op]} proposal
        conf (:confidence proposal 1.0)]
    (when (< conf confidence-floor)
      [{:rule :low-confidence
        :detail (str "Confidence " (format "%.2f" conf) " below floor.")}])))

;; ---- verdict & check result ----

(defn check
  "Run all governor checks on a proposal. Returns
  {:hard? bool :escalate? bool :violations [..] :notes str}."
  [phase subject st proposal]
  (let [all-violations
        (concat
         (project-not-registered-violations proposal st)
         (invalid-jurisdiction-violations proposal)
         (electrician-license-missing-violations proposal)
         (confidence-floor-violations proposal))
        conf (:confidence proposal 1.0)
        op (:op proposal)
        hard? (seq all-violations)
        escalate? (or (< conf confidence-floor)
                      (contains? high-stakes op))]
    {:hard? hard?
     :escalate? escalate?
     :violations all-violations
     :notes (str "Governor: " (count all-violations) " violations. "
                 "Op=" op " confidence=" (format "%.2f" conf)
                 " phase=" phase)}))
