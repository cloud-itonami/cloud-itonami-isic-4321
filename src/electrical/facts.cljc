(ns electrical.facts
  "Electrical-trade-specific legal basis and safety standards for
  electrical installation work (ISIC 4321). Electrical installation work
  is conducted under licensed electrician supervision and is subject to
  building codes (arc-flash, load calculations, permit/energization
  clearance) and occupational safety regulations in every jurisdiction.

  This actor does NOT perform electrical installation work itself, and
  does NOT certify work as code-compliant or electrically safe. Instead,
  it proposes coordination actions (scheduling inspections, logging
  hazards, requesting formal review by a licensed electrician/inspector)
  and escalates them as high-stakes human decisions.

  The `catalog` below seeds a minimal representative set of jurisdictions
  (JPN, USA, DEU/EU) with official-source citations to establish a
  real legal-basis ground. As with `construction.facts`, this is DATA
  (sourced, cited EDN), not logic buried in code.

  Electrical safety is always `:qualitative` in its risk assessment
  (no universal numeric thresholds across jurisdictions) -- load
  calculations, arc-flash analysis, and scope determinations vary by
  installation. This actor's gate will REFUSE any proposal to auto-
  certify, energize, or approve installation work without human
  (licensed electrician / inspector) sign-off, ALWAYS."
  )

(def catalog
  "Minimal representative catalog of electrical-trade legal basis by
  jurisdiction. All sources verified, cited from official government/
  standards bodies.

  Each entry is [jurisdiction-key {:basis-name .. :authorities ..
  :source-url .. :cites (list of specific regulations)}].

  Electrical safety is `:qualitative` by definition in every
  jurisdiction: risk assessment and scope determination require human
  expert judgment (licensed electrician), not just numeric triggers."
  {
   :JPN
   {:basis-name "Japanese Electrical Installation Standards"
    :authorities ["経済産業省 (METI)" "電気事業連合会 (FEPC)"]
    :source-urls ["https://www.meti.go.jp/english/" "https://www.fepc.or.jp/english/"]
    :cites ["電気事業法 (Electricity Business Act)"
            "電気設備技術基準 (Technical Standards for Electrical Installations)"
            "労働安全衛生規則 Article 362 (OSH Rules - electrical hazard prevention)"]
    :threshold-model :qualitative}

   :USA
   {:basis-name "US National Electrical Code & OSHA"
    :authorities ["OSHA (Occupational Safety and Health Administration)" "NEC (National Electrical Code)"]
    :source-urls ["https://www.osha.gov/" "https://www.nfpa.org/nec"]
    :cites ["29 CFR 1910.301-330 (Electrical - General Requirements)"
            "29 CFR 1926.500-955 (Construction - Electrical Safety)"
            "NEC Article 110 (General Requirements for Electrical Installation)"]
    :threshold-model :qualitative}

   :DEU
   {:basis-name "German/EU Electrical Safety & Construction Standards"
    :authorities ["VDE (Verband der Elektrotechnik)" "European Commission"]
    :source-urls ["https://www.vde.com/" "https://eur-lex.europa.eu/"]
    :cites ["DIN VDE 0100 (Electrical Installations of Buildings)"
            "Directive 2014/35/EU (Low Voltage Directive)"
            "Construction Sites Directive 92/57/EEC (Safety & Health)"
            "Berufsgenossenschaft Energie Textil Elektro Medienerzeugnisse (BGETEM)"]
    :threshold-model :qualitative}
   })

(defn legal-basis-of
  "Look up the legal basis (authorities, source URLs, cited regulations)
  for a given jurisdiction (keyword like :JPN, :USA, :DEU). Returns a
  map of {:basis-name :authorities :source-urls :cites}, or nil if
  jurisdiction not found."
  [jurisdiction]
  (get catalog jurisdiction))

(defn jurisdiction-known?
  "True if this jurisdiction is in the catalog."
  [jurisdiction]
  (contains? catalog jurisdiction))

(defn threshold-model
  "Return the risk-assessment model for a jurisdiction: :qualitative
  (human expert judgment required, no numeric thresholds) or :quantitative
  (numeric bright-line threshold). Electrical installation is always
  :qualitative: scope, hazard assessment, and readiness to energize
  require licensed electrician/inspector expert judgment, never just
  numeric calculation."
  [jurisdiction]
  (get-in catalog [jurisdiction :threshold-model] :qualitative))
