# Religious-Community Mental Model Framework

A unified Datomic/DataScript schema for comparative analysis of religious organizations, secular communities, and their mental models (governance, doctrine, actor structures, lexicons).

## Overview

This framework extends etzhayyim's sophisticated mental model (11-axis evaluation system, Tier-A/B/C governance actors, charter-based authority, domain-specific lexicons) into a **comparative schema** that can represent any religious tradition, secular community, or hybrid organization.

### Key Insight

**etzhayyim is not alone.** It is one instance of a broader class of organizations—religious and secular—that share structural needs:
- Governance legitimacy and decision-making authority
- Sustainable funding and economic models
- Member identity and formal membership
- Real-world service delivery
- Educational transmission of doctrine/knowledge
- Conflict resolution mechanisms

This framework captures these dimensions systematically, enabling queries like:
- "Which traditions score highest on governance transparency?"
- "How do hierarchical vs. consensus governance correlate with member engagement?"
- "Where are the doctrinal conflicts between traditions, and which organizations attempt to bridge them?"

---

## Files

### Schema & Data

- **`religious-community.datoms.edn`** — Core schema definition + etzhayyim retroactive mapping
  - `:tradition/*` — 13 tradition categories (Christian/Reformed, Orthodox, Sunni/Shia Islam, Theravada/Mahayana Buddhism, Hindu Brahmo Samaj, Labor/Cooperative/University/Technical)
  - `:axis/*` — 14-axis evaluation framework (governance, economic, identity, agency, social delivery, physical presence, public trust, legal contract, land resource, education, dispute resolution, external recognition, member engagement, doctrine clarity)
  - `:org/*` — Organization entities (org info, mission, governance model, charter, actors, lexicons)
  - `:org-axis/*` — Instantiation of each axis for each org (with measurement basis and caveats)
  - `:doctrine/*` — Foundational beliefs, with conflict relationships
  - `:actor-pattern/*` — Governance roles (Council, Bishop, Imam, Abbot, Steward, Maintainer, etc.)
  - `:charter/*` — Constitutional documents and amendment processes
  - `:lexicon/*` — Tradition-specific terminology (Sacraments, Pillars, Sutras, Labor terminology, etc.)

- **`sample-orgs.edn`** — 12 instantiated organizations
  - **Religious (7)**: etzhayyim, Lutheran Reformation, Orthodox Christianity, Sunni Islam, Shia Islam, Theravada Buddhism, Mahayana Buddhism, Brahmo Samaj
  - **Secular (5)**: AFSCME Labor Union, Mondragon Cooperative, Harvard University, Linux Foundation/Community

### Query Tools

- **`queries/guide.md`** — Query usage guide with 5 example scenarios
  - Query 1: Axis distribution by tradition (radar chart data)
  - Query 2: Doctrinal conflict graph (cross-tradition doctrinal clashes)
  - Query 3: Actor structure patterns (governance role counts)
  - Query 4: Charter amendment authority (organizational flexibility)
  - Query 5: Lexicon language coverage (translation capacity)

- **`queries/comparison.cljs`** — nbb DataScript query runner
  - Load and transact EDN data
  - Execute 5 comparison queries
  - Output as text tables (CSV support planned)

### Documentation

- **`/90-docs/adr/2608180100-religious-community-mental-model-framework.edn`** — ADR (Architecture Decision Record)
  - Rationale for schema design
  - Trade-offs and open questions
  - Integration with etzhayyim

---

## The 14-Axis Framework

Each organization is evaluated on 14 dimensions (0–100 scale):

| # | Axis | Description | Example: High | Example: Low |
|---|------|-------------|---|---|
| 1 | **Governance** | Council seats filled & functional | Mondragon (90) | Linux LTSC (65) |
| 2 | **Economic** | Sustainable funding pipeline | Harvard (95) | etzhayyim (10) |
| 3 | **Identity** | Member identity coverage (formal records) | Linux (40) | Theravada (50) |
| 4 | **Agency** | Org. delegates decisions to agents (AI or autonomous) | etzhayyim (95) | Orthodox (5) |
| 5 | **Social Delivery** | Real-world services (hospitals, food, education) | Shia Islam (75) | Linux (95) |
| 6 | **Physical Presence** | Temples, hospitals, schools, robotics | Orthodox (85) | Linux (30) |
| 7 | **Public Trust** | Community reputation & external trust | Linux (90) | AFSCME (60) |
| 8 | **Legal Contract** | Charter & formal constitutional framework | Harvard (95) | Theravada (70) |
| 9 | **Land Resource** | Land holdings & trust registry | Harvard (95) | Linux (0) |
| 10 | **Education** | Doctrine/knowledge transmission capacity | Theravada (80) | AFSCME (75) |
| 11 | **Dispute Resolution** | Conflict resolution mechanisms | Mondragon (90) | Linux (55) |
| 12 | **External Recognition** | Peer/interfaith/institutional recognition | Linux (100) | etzhayyim (0) |
| 13 | **Member Engagement** | Active participation rate | Mondragon (80) | Luther (30) |
| 14 | **Doctrine Clarity** | Foundational belief coherence | Theravada (95) | Linux (80) |

---

## Running the Queries

### Prerequisites

```bash
# Ensure nbb is installed
npm install -g nbb

# Ensure datascript is available
npm install datascript
```

### Execute

```bash
cd /Users/junkawasaki/github/com-junkawasaki/90-docs/religious-community/queries

# Run all queries
nbb comparison.cljs

# Run specific query
nbb comparison.cljs query-1   # Axis distribution
nbb comparison.cljs query-2   # Doctrinal conflicts
nbb comparison.cljs query-3   # Actor structure
nbb comparison.cljs query-4   # Charter amendment authority
nbb comparison.cljs query-5   # Lexicon language coverage

# Future: CSV output
nbb comparison.cljs query-1 --format csv > axis-comparison.csv
```

---

## Interpretation Notes

⚠️ **Critical caveat**: **All axis values are model-dependent, not ground truth.**

- Every `:org-axis/current-value` includes `:org-axis/measurement-basis` (fact, survey, document-analysis, simulation, or assumption) and `:org-axis/caveat`.
- Use **caveats first** when interpreting results.
- Treat values as **ordinal** (ranking), not **interval** (precise differences). Example: "Governance 85 vs. 70 → substantially higher," not "15 points higher."

### Measurement Basis

- **`fact`** — Observational or documented (e.g., Harvard's $50B endowment is fact)
- **`survey`** — Empirical survey data (opinion, membership participation)
- **`document-analysis`** — Charter/bylaws analysis
- **`simulation`** — Model-based estimation (e.g., etzhayyim's hypothetical agent scale)
- **`assumption`** — Placeholder pending real data

Example caveats:
```
etzhayyim/axis-agency: value=95, basis=simulation, caveat="LLM agent architecture designed; Tier-B ops not yet live"
Lutheran/axis-doctrine-clarity: value=95, basis=fact, caveat="Augsburg Confession canonical; theology well-documented for 500 years"
Linux/axis-governance: value=65, basis=survey, caveat="Linus + LTSC model; benevolent dictatorship consensus"
```

---

## Use Cases

### 1. Comparative Theology

**Q: Which doctrines conflict across traditions?**

Query 2 returns a conflict graph. Example result:
```
Trinity (Christianity) ←→ Strict Monotheism (Islam)
Sola Scriptura (Reformation) ←→ Papal Infallibility (Catholicism)
Eight Hundred Myriads (etzhayyim) ←→ Strict Monotheism (Islam)
```

**Insight**: etzhayyim's doctrinal synthesis (Christian + Japanese) inherits conflicts from both lineages but explicitly bridges them.

### 2. Governance Model Analysis

**Q: How do decision-making structures vary?**

Query 3 returns actor counts by tier and authority:
```
Mondragon: Tier-A (1 General Assembly, 80k vote) → democratic
Orthodox: Tier-A (3 actors: Patriarch, Synod, n bishops) → hierarchical
Linux: Tier-A (1 actor: Linus, advisors) → meritocratic benevolent dictatorship
```

**Insight**: Democratic orgs (Mondragon, AFSCME) have slower amendment cycles; benevolent dictators (Linus, Harvard Trustees) move faster but risk legitimacy gaps.

### 3. Organizational Health Assessment

**Q: Which organizations are most "well-balanced" across the 14 axes?**

Compute **variance** and **mean** per org:
```
Mondragon: mean=84, stdev=6 (balanced, well-rounded)
Harvard: mean=87, stdev=12 (strong in trust/endowment, weak in member engagement)
etzhayyim: mean=54, stdev=42 (nascent, uneven maturity)
Linux: mean=78, stdev=25 (strong in code/trust, weak in physical presence)
```

**Insight**: Older, larger orgs (Harvard, Orthodox) have higher means and variance. Emergent orgs (etzhayyim, Linux) show high variance as they scale.

### 4. Translation & Knowledge Accessibility

**Q: Which traditions have multilingual doctrinal texts?**

Query 5 shows language coverage:
```
Christianity: [:en :de :fr :nl :es :it :pl :ru] — high accessibility
Islam: [:ar :en :ur :tr :fa] — Qur'an central authority in Arabic
Theravada: [:pi :en :th :my] — Pali Canon preserved, translations emerging
Linux: [:en] — English-first documentation, some localization efforts
```

**Insight**: Traditions with high language coverage democratize knowledge. Single-language traditions create gatekeepers (Quranic Arabic scholars, Pali experts).

---

## Extending the Framework

### Adding a New Organization

1. **Define `:org/*` entity** with basic metadata (name, tradition, founding date, governance model, mission)
2. **Add `:org-axis/*` entries** (14 per org, with measurement basis and caveat)
3. **Define key `:actor/*` roles** (Council members, Bishops, Stewards, etc.)
4. **Link `:charter/*` and `:lexicon/*`** if applicable
5. **Add `:doctrine/*` conflicts** where relevant

Example: Registering "Zen Buddhism" (Mahayana variant):
```clojure
{:db/id -12201
 :org/id :org-zen-buddhism
 :org/name "Zen Buddhism (Soto & Rinzai Schools)"
 :org/tradition [:tradition/id :tradition-buddhist-mahayana]
 :org/founding-date 1100
 :org/governance-model :hierarchical
 :org/mission-statement "Direct experience of Buddha-nature; Zazen (sitting meditation); transmission by lineage."
 :org/primary-language :ja}

; Add 14 axis values (measurement-basis + caveat each)
; Add key actors: Roshi (Zen master), sangha (community)
; Add lexicon: Satori (enlightenment), Koans, etc.
```

### Querying New Data

Use the same 5 queries; they will automatically include new orgs.

---

## Well-Balanced Design

**12 sample organizations chosen for analytical balance:**

| Aspect | Coverage |
|--------|----------|
| **Size** | 1K–1.6B members (scale diversity, comparability within 2–3x) |
| **History** | 100–2500 years (enough written documentation) |
| **Geography** | All continents except Antarctica |
| **Governance** | Hierarchical (5), Democratic (4), Consensus/Synodal (3) |
| **Tradition** | Religious (7), Secular (5) |
| **Axis maturity** | Well-established (traditions 100+ yrs) to nascent (etzhayyim 2026) |

**NOT comprehensive**: Does not cover 4,000+ world religions, indigenous traditions, or new religious movements. Extensible by design.

---

## References

- **ADR** (Architecture Decision Record): `/90-docs/adr/2608180100-religious-community-mental-model-framework.edn`
- **Schema**: `religious-community.datoms.edn` (Datomic/DataScript entities)
- **Data**: `sample-orgs.edn` (12 instantiated organizations)
- **Query Guide**: `queries/guide.md` (5 example scenarios with interpretation)
- **Query Runner**: `queries/comparison.cljs` (nbb executable)

---

## Future Work

- [ ] Longitudinal tracking (axis values over time, with dates)
- [ ] Automated conflict detection (NLP on doctrine summaries)
- [ ] Economic sustainability forecasting (endowment depletion models)
- [ ] Visualization dashboard (Recharts radar charts, conflict graphs, actor hierarchies)
- [ ] Extend to 50+ organizations (representative coverage per tradition)
- [ ] Add `:actor-performance/*` (track individual leaders/maintainers)
- [ ] Member journey mapping (lifecycle from novice to elder/authority)
- [ ] Integration with external data sources (Wikipedia, canonical texts via APIs)

---

## License & Attribution

- **Schema design & etzhayyim mapping**: Jun Kawasaki (Anthropic, 2026)
- **Sample organization data**: Synthesized from public sources (Wikipedia, canonical texts, organizational documents)
- **Measurement methodology**: Hybrid (fact, survey, document-analysis, simulation)

---

## Questions?

Refer to:
- **Query guide**: `queries/guide.md`
- **ADR & rationale**: `/90-docs/adr/2608180100-religious-community-mental-model-framework.edn`
- **Run queries**: `nbb queries/comparison.cljs [query-N]`
