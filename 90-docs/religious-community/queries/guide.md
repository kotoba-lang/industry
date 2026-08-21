# Religious-Community Mental Model Framework: Query Guide

This guide demonstrates how to query the religious-community framework to perform comparative analysis across traditions, organizations, and governance models.

## Data Organization

- **Schema entities**: `/religious-community/religious-community.datoms.edn` (`:tradition/*`, `:axis/*`, `:org/*`, `:doctrine/*`, `:actor/*`, `:charter/*`, `:lexicon/*`)
- **Sample organizations**: `/religious-community/sample-orgs.edn` (12 orgs: 7 religious + 5 secular)
- **Query runner**: `/religious-community/queries/comparison.cljs` (nbb script)

## Query Examples

### Query 1: Mental Model Axis Distribution by Tradition

**Goal**: Compare how different traditions score across the 14 axes (e.g., which traditions prioritize governance vs. economic sustainability?).

**Pseudo-code**:
```clojure
[:find ?tradition ?axis-name ?avg-value
 :where
 [?org :org/tradition ?trad-ref]
 [?trad-ref :tradition/name ?tradition]
 [?org-axis :org-axis/org ?org]
 [?org-axis :org-axis/axis ?axis]
 [?axis :axis/name ?axis-name]
 [?org-axis :org-axis/current-value ?value]
 ; aggregate by tradition + axis, compute average
]
```

**Expected output** (sample):
```
Tradition                      | Axis                         | Avg Value
-------------------------------|------------------------------|----------
Christianity - Reformed        | Governance Legitimacy        | 85
Christianity - Reformed        | Sustainable Funding          | 80
Christianity - Reformed        | Member Identity Coverage     | 70
...
Theravada Buddhism             | Governance Legitimacy        | 75
Theravada Buddhism             | Sustainable Funding          | 65
Islam - Sunni                  | Governance Legitimacy        | 70
Islam - Sunni                  | Sustainable Funding          | 65
```

**Visualization**: Radar chart with 14 axes, one series per tradition (or org).

---

### Query 2: Doctrinal Conflict Graph

**Goal**: Understand which doctrines from different traditions conflict, and identify bridging doctrines (doctrines that synthesize opposing views).

**Pseudo-code**:
```clojure
[:find ?doctrine-a ?tradition-a ?doctrine-b ?tradition-b
 :where
 [?doc-a :doctrine/id ?doc-a-id]
 [?doc-a :doctrine/name ?doctrine-a]
 [?doc-a :doctrine/tradition ?trad-a]
 [?trad-a :tradition/name ?tradition-a]
 [?doc-a :doctrine/conflicting-with ?doc-b]
 [?doc-b :doctrine/name ?doctrine-b]
 [?doc-b :doctrine/tradition ?trad-b]
 [?trad-b :tradition/name ?tradition-b]
 [(not= ?trad-a ?trad-b)]
]
```

**Expected output** (sample):
```
Doctrine A                 | Tradition A                | Doctrine B          | Tradition B
---------------------------|----------------------------|---------------------|---------------------------
Trinity                    | Christianity - Reformed    | Monotheism (Allah)  | Islam - Sunni
Sola Scriptura             | Christianity - Reformed    | Papal Infallibility | Christianity - Orthodox
Eight Hundred Myriads      | Synthetic - etzhayyim      | Strict Monotheism   | Islam - Sunni
```

**Visualization**: Directed graph showing conflict edges; nodes = doctrines, colored by tradition; edges = conflicts.

---

### Query 3: Actor Structure Patterns (Governance Roles)

**Goal**: Compare who holds decision-making authority across organizations (council members, priests, elected reps, meritocratic maintainers).

**Pseudo-code**:
```clojure
[:find ?org-name ?tier ?authority (count ?actor) ?avg-team-size
 :where
 [?org :org/name ?org-name]
 [?actor :actor/belongs-to-org ?org]
 [?actor :actor/tier ?tier]
 [?actor :actor/authority ?authority]
 [?actor :actor/team-size ?team-size]
 :group-by [?org-name ?tier ?authority]
 :aggregates {(avg ?team-size) ?avg-team-size}
]
```

**Expected output** (sample):
```
Organization                              | Tier          | Authority        | # Actors | Avg Team Size
-----------------------------------------|---------------|------------------|----------|---------------
Lutheran Reformation                     | tier-a        | decision         | 7        | 15
Lutheran Reformation                     | tier-b        | execution        | 250      | 3
Orthodox Christian Church                | tier-a        | decision         | 3        | 50 (Patriarch + Synod)
Theravada Buddhism                       | tier-c        | community        | ∞        | 1 (individual monks)
Mondragon Cooperative                    | tier-a        | decision         | 1        | 80000 (General Assembly)
Linux Community                          | tier-a        | decision         | 1        | 1000+ (Linus + LTSC)
```

**Visualization**: Heatmap or stacked bar chart showing distribution of tiers and authorities by org.

---

### Query 4: Charter Amendment Authority Comparison

**Goal**: Understand how easy (or hard) it is for each organization to change its foundational documents—proxy for organizational flexibility vs. stability.

**Pseudo-code**:
```clojure
[:find ?org-name ?charter-name ?authority-level
 :where
 [?org :org/name ?org-name]
 [?charter :charter/org ?org]
 [?charter :charter/name ?charter-name]
 [?charter :charter/authority-level ?authority-level]
]
```

**Expected output** (sample):
```
Organization                  | Charter Name              | Amendment Authority
------------------------------|---------------------------|--------------------
etzhayyim                     | CHARTER-RIDER v3.5        | lv7-unanimity (5-of-7 Council)
Lutheran Reformation          | Augsburg Confession       | lv7-unanimity (Ecumenical Council)
Mondragon Cooperative         | Bylaws                    | simple-majority (General Assembly)
Sunni Islam (ISNA)            | Shura Principle           | simple-majority (Delegate Assembly)
Linux Community               | N/A (GPLv2 Benevolent)    | governor-discretion (Linus decides)
Harvard University            | N/A (Trustee Charter)     | governor-discretion (Trustees)
```

**Insight**: Organizations with high amendment authority (easier changes) may adapt faster; organizations with immutable charters (Sharia, Quran) preserve stability but risk obsolescence.

---

### Query 5: Lexicon Language Coverage (Doctrinal Knowledge Gaps)

**Goal**: Identify which traditions have multilingual doctrinal glossaries (good for diaspora/translation) vs. single-language canonical texts (creates translation gatekeepers).

**Pseudo-code**:
```clojure
[:find ?tradition-name (distinct ?language) (count ?entry)
 :where
 [?lex :lexicon/tradition ?trad]
 [?trad :tradition/name ?tradition-name]
 [?lex :lexicon/language ?language]
 [?lex :lexicon/entries ?entry]
 :group-by [?tradition-name ?language]
]
```

**Expected output** (sample):
```
Tradition                        | Languages              | # Glossary Entries
---------------------------------|------------------------|-------------------
Christianity - Reformed         | [:en :de :fr :nl]     | 250+
Islam - Sunni                   | [:ar :en :ur :tr]     | 150+ (Pillars, contracts)
Theravada Buddhism              | [:pi :en :th :my]     | 200+ (Pali Canon)
Synthetic - etzhayyim           | [:ja :en :he]         | 500+ (hybrid lexicons)
Linux Community                 | [:en]                 | 1000+ (code comments, docs)
```

**Insight**: Multilingual traditions (e.g., Christianity, Islam) have better translation capacity and diaspora reach. Single-language traditions (Linux, etzhayyim starting) have higher knowledge concentration.

---

## Running Queries

### Using `comparison.cljs` (nbb runner)

```bash
# Run all queries
nbb /Users/junkawasaki/github/com-junkawasaki/90-docs/religious-community/queries/comparison.cljs

# Run specific query
nbb /Users/junkawasaki/github/com-junkawasaki/90-docs/religious-community/queries/comparison.cljs query-1

# Output to CSV
nbb /Users/junkawasaki/github/com-junkawasaki/90-docs/religious-community/queries/comparison.cljs query-2 --format csv > axis-comparison.csv
```

### Manual DataScript/Datomic (if using database)

If loaded into DataScript:

```clojure
(require '[datascript.core :as d])

(def conn (d/create-conn {:org/id {:db/unique :db.unique/identity}}))

(d/transact conn
  (edn/read-string (slurp "/path/to/religious-community.datoms.edn")))

; Run Query 1
(d/q '[:find ?tradition ?axis-name (avg ?value)
       :where
       [?org :org/tradition ?trad-ref]
       [?trad-ref :tradition/name ?tradition]
       ...] 
     @conn)
```

---

## Interpretation Notes

1. **Axis values are model-dependent**: All `:org-axis/current-value` entries include `:org-axis/measurement-basis` (fact, survey, document-analysis, simulation, or assumption) and `:org-axis/caveat`. **Do not treat any axis value as ground truth.** Use caveats when comparing.

2. **Doctrinal conflicts are curated, not inferred**: The `:doctrine/conflicting-with` edges are manually entered based on theological analysis. No automated conflict inference occurs.

3. **Secular vs. religious axes**: Some axes (`:axis-doctrine-clarity`) are weighted toward religious organizations. Queries may want to filter by `:axis/universality` (`:universal` vs. `:tradition-specific` vs. `:secular-only`).

4. **Lexicon incompleteness**: Only sampled lexicons are included, not comprehensive glossaries. Queries will show coverage **gaps** (intentional feature, not bug).

5. **Scale sensitivity**: The 0–100 scale is ordinal, not interval. Use **ranges and quartiles** for analysis, not precise numeric differences (e.g., "Governance 85 vs. 70" → "Leadership legitimacy is substantially higher" not "15 points higher").

---

## Future Query Examples

- **Longitudinal change**: Track axis values over time (requires `:org-axis/measurement-date` tracking).
- **Governance model correlation**: Do `:governance-model :hierarchical` organizations have lower `:axis-member-engagement`?
- **Economic sustainability**: Correlate `:axis-economic` vs. `:axis-public-trust` (do well-funded orgs have higher public trust?).
- **Doctrinal evolution**: Compare `:doctrine/historical-evolution` across traditions to identify reform patterns.
- **Hybrid orgs**: Query etzhayyim's unique position (Christian + Japanese values) and find other hybrid traditions.

---

## References

- Framework design: `/90-docs/adr/2608180100-religious-community-mental-model-framework.edn`
- Sample organizations: `/90-docs/religious-community/sample-orgs.edn`
- Query runner: `/90-docs/religious-community/queries/comparison.cljs`
