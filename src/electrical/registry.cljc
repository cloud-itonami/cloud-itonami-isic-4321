(ns electrical.registry
  "Registry of electrical coordination proposals and records. Renders
  human-readable summaries of proposals, approvals, and audit records.

  This actor generates proposals (coordination suggestions); all real
  electrical decisions (execution, energization, certification) are
  made by licensed electricians and inspectors. Records are audit-
  backed and include human sign-off."
  (:require [electrical.facts :as facts]))

(defn render-proposal
  "Render a proposal as human-readable text for review.
  Returns a string describing the proposal, its rationale, and any
  legal basis citations."
  [proposal]
  (let [{:keys [op subject value confidence cites]} proposal
        basis-text (if (seq cites)
                     (str "\nLegal basis: " (clojure.string/join ", " cites))
                     "")]
    (str "Proposal: " (name op) " for project " subject
         "\nConfidence: " (format "%.0f%%" (* 100 confidence))
         "\n\nDetails:\n" (pr-str value)
         basis-text)))

(defn render-project-record
  "Render a project's current state and audit trail as text.
  Includes all registered facts, hazard flags, progress milestones,
  and pending approvals."
  [project jurisdiction-basis]
  (let [{:keys [id jurisdiction electrician-license scope-description
                site-address registered? closed? hazard-flags
                progress-records crew-dispatches inspection-scheduled?]} project
        basis (if jurisdiction-basis
                (facts/legal-basis-of jurisdiction)
                {})
        legal-note (if basis
                     (str "Legal basis: " (:basis-name basis) " (authorities: "
                          (clojure.string/join ", " (:authorities basis)) ")")
                     "Legal basis: Not found in catalog")]
    (str "=== ELECTRICAL INSTALLATION PROJECT RECORD ===\n"
         "Project ID: " id "\n"
         "Jurisdiction: " jurisdiction " (" legal-note ")\n"
         "Electrician License: " electrician-license "\n"
         "Scope: " scope-description "\n"
         "Site Address: " site-address "\n"
         "Status: " (if closed? "CLOSED" "ACTIVE") " (registered=" registered? ")\n"
         "\n--- HAZARD FLAGS ---\n"
         (if (seq hazard-flags)
           (clojure.string/join "\n" (map #(str "  [" (:severity %) "] " (:type %)
                                              ": " (:description %)) hazard-flags))
           "  (none)")
         "\n\n--- PROGRESS MILESTONES ---\n"
         (if (seq progress-records)
           (clojure.string/join "\n" (map #(str "  " (:milestone %) ": " (:description %))
                                          progress-records))
           "  (none)")
         "\n\n--- CREW DISPATCHES (PROPOSED) ---\n"
         (if (seq crew-dispatches)
           (clojure.string/join "\n" (map #(str "  [" (:crew-type %) "] "
                                              (:task %) ": " (:description %))
                                          crew-dispatches))
           "  (none)")
         "\n\n--- INSPECTION ---\n"
         (if inspection-scheduled? "Inspection review: SCHEDULED" "Inspection review: not yet requested")
         "\n\n=== END RECORD ===\n")))

(defn render-hazard-escalation
  "Render a hazard flag as a formal escalation notice (what a human
  electrician/inspector would immediately see)."
  [project hazard]
  (let [{:keys [id jurisdiction]} project
        {:keys [type severity description timestamp]} hazard
        severity-level (case severity
                         :critical "CRITICAL SAFETY HAZARD"
                         :high "HIGH PRIORITY"
                         :medium "MEDIUM PRIORITY"
                         :low "LOW PRIORITY"
                         "HAZARD")]
    (str "=== ELECTRICAL SAFETY ESCALATION ===\n"
         severity-level "\n"
         "Project: " id "\n"
         "Jurisdiction: " jurisdiction "\n"
         "Hazard Type: " (name type) "\n"
         "Timestamp: " timestamp "\n"
         "\nDescription:\n" description "\n"
         "\n*** IMMEDIATE HUMAN REVIEW REQUIRED ***\n"
         "Licensed electrician or inspector must assess this hazard "
         "and determine corrective action.\n"
         "=== END ESCALATION ===\n")))

(defn render-inspection-request
  "Render an inspection request as a formal proposal to schedule review."
  [project scope reason]
  (let [{:keys [id jurisdiction electrician-license scope-description]} project]
    (str "=== INSPECTION REVIEW REQUEST ===\n"
         "Project: " id "\n"
         "Jurisdiction: " jurisdiction "\n"
         "Licensed Electrician: " electrician-license "\n"
         "Project Scope: " scope-description "\n"
         "\nInspection Scope: " (name scope) "\n"
         "Reason: " reason "\n"
         "\n*** HUMAN APPROVAL REQUIRED ***\n"
         "Licensed electrician or authorized inspector approves this "
         "inspection schedule. Project cannot proceed to next phase "
         "without inspection clearance.\n"
         "=== END REQUEST ===\n")))
