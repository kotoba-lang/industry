export const meta = {
  name: 'design-quality-score',
  description: 'Score kotoba-lang uikit/appkit/kotoba-ui/liquid-glass-ui UI quality into queryable EDN (ADR-2607132300)',
  phases: [
    { title: 'Lint', detail: 'deterministic token-compliance / dark-mode / layering checks per library' },
    { title: 'Judge', detail: '3 independent LLM judges per library score an Apple-HIG-derived rubric' },
    { title: 'Synthesize', detail: 'write base catalog + append-only ledger EDN (DataScript/Datomic transactable)' },
  ],
}

// Placeholders substituted by the write-edn agent at write time via Bash `date`
// (workflow scripts cannot call Date.now()/new Date() themselves — see tool docs).
// This avoids depending on the caller passing args.runId/args.timestamp correctly:
// the first run of this workflow (2026-07-13) silently got empty run-id/at because
// that threading failed for an unknown reason — placeholder+Bash-substitution at
// write time removes the dependency on args entirely. See ADR-2607132300.
const RUN_ID = '__RUN_ID__'
const AT = '__AT__'

const LIBS = [
  {
    id: 'uikit',
    name: 'uikit',
    repo: 'kotoba-lang/uikit',
    role: 'touch/mobile/card-first platform binding on top of kotoba-ui.core',
    srcDir: 'orgs/kotoba-lang/uikit/src',
    files: ['orgs/kotoba-lang/uikit/src/uikit/core.cljc', 'orgs/kotoba-lang/uikit/docs/design.md', 'orgs/kotoba-lang/uikit/README.md'],
    entryDisciplineInstruction: "Check whether any file under orgs/kotoba-lang/uikit/src :requires a shitsuke.* or liquid-glass.* namespace directly (bypassing kotoba-ui.core). Count such requires as single_entry_violation_count. Expected 0 per uikit's own docstring (it should require only kotoba-ui.core).",
  },
  {
    id: 'appkit',
    name: 'appkit',
    repo: 'kotoba-lang/appkit',
    role: 'desktop/dense-data platform binding on top of kotoba-ui.core',
    srcDir: 'orgs/kotoba-lang/appkit/src',
    files: ['orgs/kotoba-lang/appkit/src/appkit/core.cljc', 'orgs/kotoba-lang/appkit/docs/design.md', 'orgs/kotoba-lang/appkit/README.md'],
    entryDisciplineInstruction: "Check whether any file under orgs/kotoba-lang/appkit/src :requires a shitsuke.* or liquid-glass.* namespace directly (bypassing kotoba-ui.core). Count such requires as single_entry_violation_count. Expected 0 per appkit's own docstring (it should require only kotoba-ui.core).",
  },
  {
    id: 'kotoba-ui',
    name: 'kotoba-ui',
    repo: 'kotoba-lang/kotoba-ui',
    role: 'single-entry shell/theme/->page integration layer over shitsuke + liquid-glass-ui',
    srcDir: 'orgs/kotoba-lang/kotoba-ui/src',
    files: [
      'orgs/kotoba-lang/kotoba-ui/src/kotoba_ui/core.cljc',
      'orgs/kotoba-lang/kotoba-ui/src/kotoba_ui/shell.cljc',
      'orgs/kotoba-lang/kotoba-ui/src/kotoba_ui/theme.cljc',
      'orgs/kotoba-lang/kotoba-ui/src/kotoba_ui/shell/style.cljc',
      'orgs/kotoba-lang/kotoba-ui/docs/agent-guide.md',
      'orgs/kotoba-lang/kotoba-ui/docs/design.md',
      'orgs/kotoba-lang/kotoba-ui/README.md',
    ],
    entryDisciplineInstruction: "kotoba-ui.core is the integration layer and is EXPECTED to require shitsuke.*/liquid-glass.* directly — that is its designed role, not a violation. Report single_entry_violation_count = 0 with note 'integration layer; requiring shitsuke/liquid-glass directly is by design'.",
  },
  {
    id: 'liquid-glass-ui',
    name: 'liquid-glass-ui',
    repo: 'kotoba-lang/liquid-glass-ui',
    role: 'bottom material/token layer (glass surfaces, elevation, motion, tokens)',
    srcDir: 'orgs/kotoba-lang/liquid-glass-ui/src',
    files: [
      'orgs/kotoba-lang/liquid-glass-ui/src/liquid_glass/tokens.cljc',
      'orgs/kotoba-lang/liquid-glass-ui/src/liquid_glass/components.cljc',
      'orgs/kotoba-lang/liquid-glass-ui/src/liquid_glass/style.cljc',
      'orgs/kotoba-lang/liquid-glass-ui/docs/design.md',
      'orgs/kotoba-lang/liquid-glass-ui/README.md',
    ],
    entryDisciplineInstruction: "liquid-glass-ui is the bottom material layer with no lower layer to defer to. Report single_entry_violation_count = 0 with note 'leaf layer; check does not apply'.",
    extraNote: 'orgs/kotoba-lang/liquid-glass-ui/docs/index.html is a real self-contained rendered demo page. Do NOT screenshot or visually judge it in this pass — this workflow only scores library source/docs; sample-page visual scoring is a separate, manual pass (see ADR-2607132300 and 90-docs/design-quality/samples/).',
  },
]

const SIBLING_CONVENTION_SUMMARY = "Known shared conventions across this design-system stack (for the \"consistency\" axis — you are only reading ONE library's files, use this as the fixed baseline to compare against):\n" +
  "- Every library exposes a single entry namespace (uikit.core / appkit.core / kotoba_ui.core / liquid_glass.*) — apps are expected to require only that, not lower layers.\n" +
  "- appkit/uikit both follow the exact same shape: default-panel-opts + default-list-view-opts maps of token keywords (e.g. {:surface :clear :elevation :floating}), and panel/list-view fns that (merge default-opts caller-opts) before delegating to kotoba-ui.core — caller opts always win.\n" +
  "- liquid-glass-ui emits design tokens as CSS custom properties inside \"@layer kotoba.hig, kotoba.glass\" (light tokens under :root, dark-mode overrides re-declare the SAME custom property name under a prefers-color-scheme media query rather than minting new -dark variable names).\n" +
  "- kotoba-ui.core/shell/theme wire shitsuke.hig semantic tokens + liquid-glass material together behind ->page/shell so apps never require shitsuke.*/liquid-glass.* directly.\n" +
  "Score \"consistency\" by how well the library you are reading actually matches this shared shape (naming, opts-merge pattern, token-var pattern) — not by literally reading the other 3 libraries."

const RUBRIC_TEXT = "Score the library on 5 axes, each an integer or one-decimal float from 1 (poor) to 5 (excellent), based ONLY on what you can verify by reading the given files (source + docs). Do not assume rendered visual output you cannot see — score the API/token/design-doc quality, not imagined pixels. If a file gives no evidence for an axis, score it 3 and say so explicitly in that axis's note.\n\n" +
  "1. clarity (Apple HIG \"Clarity\"): is the API/token surface legible and unambiguous — clear naming, low incidental complexity, would a reader immediately understand what each function/token does?\n" +
  "2. deference (Apple HIG \"Deference\"): does the library's design defer to content over chrome — does the API make it easy to keep UI chrome minimal, or does it push toward heavy decoration/fixed defaults that fight content?\n" +
  "3. depth (Apple HIG \"Depth\"): does the token/API model communicate a coherent sense of layering/elevation/motion (e.g. named elevation levels, z-order, motion/spring tokens) rather than a flat, undifferentiated surface model?\n" +
  "4. consistency: given the SIBLING_CONVENTION_SUMMARY baseline below, is this library's naming/shape/opts-merge pattern consistent with it — would a developer who learned one of the sibling libraries correctly guess this one's shape?\n" +
  "5. token_discipline: qualitatively (not by counting) — does token usage look systematic and intentional (layered token groups, documented rationale for values) rather than a pile of one-off magic values?\n\n" +
  SIBLING_CONVENTION_SUMMARY

const LINT_SCHEMA = {
  type: 'object',
  properties: {
    lib: { type: 'string' },
    files_scanned: { type: 'integer' },
    consumer_files_scanned: { type: 'integer' },
    token_reference_count: { type: 'integer' },
    raw_hex_violation_count: { type: 'integer' },
    raw_px_violation_count: { type: 'integer' },
    dark_mode_override_present: { type: 'boolean' },
    single_entry_violation_count: { type: 'integer' },
    notes: { type: 'string' },
  },
  required: ['lib', 'files_scanned', 'consumer_files_scanned', 'token_reference_count', 'raw_hex_violation_count', 'raw_px_violation_count', 'dark_mode_override_present', 'single_entry_violation_count', 'notes'],
}

const JUDGE_SCHEMA = {
  type: 'object',
  properties: {
    lib: { type: 'string' },
    clarity: { type: 'number' }, clarity_note: { type: 'string' },
    deference: { type: 'number' }, deference_note: { type: 'string' },
    depth: { type: 'number' }, depth_note: { type: 'string' },
    consistency: { type: 'number' }, consistency_note: { type: 'string' },
    token_discipline: { type: 'number' }, token_discipline_note: { type: 'string' },
  },
  required: ['lib', 'clarity', 'clarity_note', 'deference', 'deference_note', 'depth', 'depth_note', 'consistency', 'consistency_note', 'token_discipline', 'token_discipline_note'],
}

function lintPrompt(lib) {
  return "You are running a deterministic design-token lint pass on the \"" + lib.id + "\" library (" + lib.repo + ") at " + lib.srcDir + ", inside the com-junkawasaki superproject working tree (relative paths, cwd is the repo root).\n\n" +
    "Step 1 — identify the token/theme source-of-truth file(s) in " + lib.srcDir + " (name typically contains \"token\" or \"theme\" — e.g. tokens.cljc / theme.cljc). If none exists, there is no source-of-truth file.\n" +
    "Step 2 — in the \"consumer\" files (every file in " + lib.srcDir + " EXCEPT the token/theme source-of-truth file), use Bash/grep to count:\n" +
    "  - raw_hex_violation_count: occurrences matching the regex #[0-9a-fA-F]{3,8}\\b (raw hex color literals) — run e.g. grep -rEo '#[0-9a-fA-F]{3,8}\\b' <consumer files>\n" +
    "  - raw_px_violation_count: occurrences of raw dimension literals used directly as a value, e.g. matching \"[0-9]+(\\.[0-9]+)?(px|rem|em)\" as a quoted string literal — run e.g. grep -rEo '\"[0-9]+(\\.[0-9]+)?(px|rem|em)\"' <consumer files>\n" +
    "  - token_reference_count: occurrences of references INTO the token system in consumer files — count :require forms pulling in a token/theme namespace, calls into token lookup (e.g. get-in on a tokens map, a 'token' helper fn), CSS var(--...) usage, and keyword-based token-variant options (e.g. :surface :clear, :elevation :floating) that select a token variant rather than hardcoding a value. Use judgment but report an actual count from grep, not a guess.\n" +
    "files_scanned = total files in " + lib.srcDir + ". consumer_files_scanned = files_scanned minus the token/theme source-of-truth file(s) (0 if there is no such file, i.e. all files count as consumer files).\n" +
    "Step 3 — dark_mode_override_present: true if grep for \"dark\" (case-insensitive) or \"prefers-color-scheme\" anywhere under " + lib.srcDir + " finds an actual dark-mode token override (not just the word \"dark\" in prose/docstring incidentally).\n" +
    "Step 4 — single_entry_violation_count: " + lib.entryDisciplineInstruction + "\n" +
    "Step 5: notes — 1-2 sentences summarizing what you found, including which file (if any) you treated as the token/theme source-of-truth." + (lib.extraNote ? ' Also mention: ' + lib.extraNote : '') + "\n\n" +
    "Report exact integers/booleans from your actual grep output, not estimates. lib field = \"" + lib.id + "\"."
}

function judgePrompt(lib) {
  return "You are one independent judge scoring the \"" + lib.id + "\" library (" + lib.repo + ", role: " + lib.role + ") against a fixed rubric. Read these files with the Read tool before scoring:\n" +
    lib.files.map(f => '- ' + f).join('\n') + "\n\n" +
    RUBRIC_TEXT + "\n\n" +
    "Score honestly and independently — do not anchor on a \"safe\" middle score; use the full 1-5 range where the evidence supports it. lib field = \"" + lib.id + "\"."
}

phase('Lint')
const lintResults = await pipeline(LIBS, lib => agent(lintPrompt(lib), { phase: 'Lint', schema: LINT_SCHEMA, label: `lint:${lib.id}` }))

phase('Judge')
const judgeResults = await pipeline(LIBS, lib => parallel([1, 2, 3].map(n => () =>
  agent(judgePrompt(lib), { phase: 'Judge', schema: JUDGE_SCHEMA, label: `judge:${lib.id}:j${n}` })
)))

function mean(xs) { return xs.reduce((a, b) => a + b, 0) / xs.length }
function stdev(xs) { const m = mean(xs); return Math.sqrt(mean(xs.map(x => (x - m) * (x - m)))) }
function ednStr(s) { return '"' + String(s == null ? '' : s).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\r?\n/g, ' ') + '"' }

phase('Synthesize')
let seq = 0
const ledgerLines = []
const libSummaries = []
const JUDGE_AXES = ['clarity', 'deference', 'depth', 'consistency', 'token_discipline']
const AXIS_ID = { clarity: 'axis/clarity', deference: 'axis/deference', depth: 'axis/depth', consistency: 'axis/consistency', token_discipline: 'axis/token-discipline-judged' }

for (let i = 0; i < LIBS.length; i++) {
  const lib = LIBS[i]
  const lint = lintResults[i]
  const judges = (judgeResults[i] || []).filter(Boolean)
  const summary = { id: lib.id }

  if (lint) {
    const denom = Math.max(1, lint.token_reference_count + lint.raw_hex_violation_count + lint.raw_px_violation_count)
    const tokenCompliance = lint.token_reference_count / denom
    const darkMode = lint.dark_mode_override_present ? 1.0 : 0.0
    const singleEntry = 1.0 - Math.min(1, lint.single_entry_violation_count / Math.max(1, lint.consumer_files_scanned))
    seq++; ledgerLines.push(`{:eval/lib :${lib.id}, :eval/axis :axis/token-compliance, :eval/layer :lint, :eval/score ${tokenCompliance.toFixed(3)}, :eval/judge "lint-script", :eval/run-id ${ednStr(RUN_ID)}, :eval/at ${ednStr(AT)}, :eval/seq ${seq}, :eval/note ${ednStr(`token_reference=${lint.token_reference_count} raw_hex=${lint.raw_hex_violation_count} raw_px=${lint.raw_px_violation_count}; ${lint.notes}`)}}`)
    seq++; ledgerLines.push(`{:eval/lib :${lib.id}, :eval/axis :axis/dark-mode-coverage, :eval/layer :lint, :eval/score ${darkMode.toFixed(3)}, :eval/judge "lint-script", :eval/run-id ${ednStr(RUN_ID)}, :eval/at ${ednStr(AT)}, :eval/seq ${seq}, :eval/note ${ednStr('dark_mode_override_present=' + lint.dark_mode_override_present)}}`)
    seq++; ledgerLines.push(`{:eval/lib :${lib.id}, :eval/axis :axis/single-entry-discipline, :eval/layer :lint, :eval/score ${singleEntry.toFixed(3)}, :eval/judge "lint-script", :eval/run-id ${ednStr(RUN_ID)}, :eval/at ${ednStr(AT)}, :eval/seq ${seq}, :eval/note ${ednStr('violations=' + lint.single_entry_violation_count + '/' + lint.consumer_files_scanned + ' consumer files')}}`)
    summary.lint = { tokenCompliance, darkMode, singleEntry, raw: lint }
  } else {
    summary.lint = null
    summary.lintFailed = true
  }

  summary.judges = {}
  for (const axis of JUDGE_AXES) {
    const scores = judges.map(j => j[axis]).filter(v => typeof v === 'number')
    if (scores.length === 0) { summary.judges[axis] = null; continue }
    const m = mean(scores), sd = stdev(scores)
    for (let jn = 0; jn < judges.length; jn++) {
      const j = judges[jn]
      if (typeof j[axis] !== 'number') continue
      seq++; ledgerLines.push(`{:eval/lib :${lib.id}, :eval/axis :${AXIS_ID[axis]}, :eval/layer :llm-judge, :eval/score ${j[axis].toFixed(2)}, :eval/judge "llm-judge-${jn + 1}", :eval/run-id ${ednStr(RUN_ID)}, :eval/at ${ednStr(AT)}, :eval/seq ${seq}, :eval/note ${ednStr(j[axis + '_note'])}}`)
    }
    seq++; ledgerLines.push(`{:eval/lib :${lib.id}, :eval/axis :${AXIS_ID[axis]}, :eval/layer :llm-judge, :eval/score ${m.toFixed(3)}, :eval/stdev ${sd.toFixed(3)}, :eval/judge "llm-judge-mean", :eval/run-id ${ednStr(RUN_ID)}, :eval/at ${ednStr(AT)}, :eval/seq ${seq}, :eval/note ${ednStr(`mean of ${scores.length} independent judges`)}}`)
    summary.judges[axis] = { mean: m, stdev: sd, n: scores.length }
  }
  libSummaries.push(summary)
}

const ledgerAppendTemplate = ledgerLines.join('\n') + '\n'

const writePrompt = "You are appending a new scoring run to the design-quality ledger under 90-docs/design-quality/ in the com-junkawasaki superproject (cwd = repo root).\n\n" +
  "Step 1 — compute real values via Bash (do NOT invent these):\n" +
  "  AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)\n" +
  "  RUN_ID=\"design-quality-lib-$(date -u +%Y%m%d-%H%M)\"\n\n" +
  "Step 2 — the text block below (between -----BEGIN APPEND----- and -----END APPEND-----) contains the literal placeholder tokens __RUN_ID__ and __AT__ in every line. Substitute EVERY occurrence of __RUN_ID__ with the RUN_ID you computed and every occurrence of __AT__ with the AT you computed (e.g. via sed 's/__RUN_ID__/'\"$RUN_ID\"'/g; s/__AT__/'\"$AT\"'/g'), then append the substituted lines to 90-docs/design-quality/design-quality-ledger.edn (create the file with the header shown below first if it does not exist yet). Do NOT touch any existing lines in that file — only append after them, byte-for-byte for the substituted content (no reformatting/reordering).\n\n" +
  "Header (only write this if the ledger file does not already exist):\n" +
  "-----BEGIN HEADER-----\n" +
  ";; design-quality-ledger.edn — append-only score events (one EDN map per line), DataScript/Datomic-transactable.\n" +
  ";; References :lib/id / :axis/id / :sample/id declared in the sibling base file design-quality.datoms.edn — load both when querying.\n" +
  ";; Do NOT hand-edit existing lines; only append new lines from future workflow runs. See ADR-2607132300.\n" +
  "-----END HEADER-----\n\n" +
  "Lines to substitute-and-append:\n" +
  "-----BEGIN APPEND-----\n" + ledgerAppendTemplate + "-----END APPEND-----\n\n" +
  "Step 3 — confirm 90-docs/design-quality/design-quality.datoms.edn already exists (it should — it's the base schema/catalog file, created once by ADR-2607132300 and not regenerated by this workflow). If it is missing, stop and report that as an error rather than trying to recreate it from scratch.\n\n" +
  "Step 4 — validate the ledger file still parses as a sequence of EDN maps, e.g.:\n" +
  "  nbb -e '(let [lines (->> (slurp \"90-docs/design-quality/design-quality-ledger.edn\") clojure.string/split-lines (remove #(clojure.string/starts-with? % \";\")) (remove clojure.string/blank?))] (doseq [l lines] (clojure.edn/read-string l)) (println \"OK\" (count lines) \"lines\"))'\n\n" +
  "Report the computed RUN_ID/AT, the final line count, and the validation result as your final answer."

const writeResult = await agent(writePrompt, { phase: 'Synthesize', label: 'write-edn' })

log(`Appended ${ledgerLines.length} ledger lines for this run.`)

return {
  baseFile: '90-docs/design-quality/design-quality.datoms.edn',
  ledgerFile: '90-docs/design-quality/design-quality-ledger.edn',
  ledgerLinesAppended: ledgerLines.length,
  writeAgentReport: writeResult,
  libs: libSummaries,
  deferredLayers: [
    'Lighthouse (Performance/Accessibility/Best-Practices/SEO 0-100) — not run: no build/serve step wired up for these libraries.',
    'axe-core automated a11y violation scan — not run, same reason.',
    'Vision-based LLM screenshot judging of the libraries themselves — not run by this workflow. Rendered sample-page visual scoring is a separate manual pass — see 90-docs/design-quality/samples/generate-samples.cljs and ADR-2607132300.',
    'WCAG contrast-ratio computation on actual token color pairs — not computed numerically; the llm-judge layer comments on token discipline qualitatively but does not compute exact ratios.',
  ],
}
